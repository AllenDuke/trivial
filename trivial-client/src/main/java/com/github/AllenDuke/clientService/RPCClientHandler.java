package com.github.AllenDuke.clientService;

import com.github.AllenDuke.constant.LOG;
import com.github.AllenDuke.dto.ClientMessage;
import com.github.AllenDuke.dto.ServerMessage;
import com.github.AllenDuke.event.TimeOutEvent;
import com.github.AllenDuke.exception.MsgSendingFailException;
import com.github.AllenDuke.exception.UnknownException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * @author 杜科
 * @description rpc消费者的业务处理器，注意每条pipeline都有自己的RPCClientHandler
 * 所有caller的消息经此发出，因此caller在此park。
 * 当业务处理器收到服务端的结果时，会根据结果中的线程id unpark相应的caller。
 *
 * 这里多处用到HashMap而不是ConcurrentHashMap是因为，虽然存在并发行为，但彼此只操作自己的数据并没有影响他人。
 * 要发送的信息会封装成任务，加入到eventLoop的TaskQueue
 * 这里的超时机制采用的是：超时后向原目的主机重发原信息，重试仍失败，原目的主机将进入该次调用的服务的黑名单，
 * 下次调用该服务将过滤此目的主机
 * @contact AllenDuke@163.com
 * @since 2020/2/11
 */
@Slf4j
public class RPCClientHandler extends SimpleChannelInboundHandler {

    //对应的ChannelHandlerContext
    private volatile ChannelHandlerContext context;//一个线程连上或断连后，其他线程要感知到

    //以下static数据为各pipeline共有
    //调用者线程在各自的条件上等待，并发性能差，要先抢夺锁
//    private static final Map<Long, Condition> waitingThreadMap=new HashMap();

    //rpcId与caller，park后加入，unpark后删除
    private static final Map<Long, Thread> waiterMap=new ConcurrentHashMap<>();

    /**
     * K: rpcId
     * V: result
     * 如果使用ConcurrentHashMap，有可能caller在进行异步调用时，不去获取结果，那么这个结果会一直保存在resultMap中，
     * 而使用的ResultMap，可以记录进入map的时间，在有人来get时，会淘汰一个超过存储时限的元素
     */
    private static final Map<Long,Object> resultMap=new ResultMap<>();

    private static final Map<Long,ResultFuture> resultFutureMap=new ConcurrentHashMap<>();

    //超时观察队列
    private static BlockingQueue<TimeOutEvent> waiterQueue;
    static {if(RPCClient.timeout!=-1)waiterQueue=new LinkedBlockingQueue<>();}

    //超时观察者，原子变量避免并发创建
    private static final AtomicReference<Thread> watcher=new AtomicReference<>();

    //与服务器的连接创建后，就会被调用, 这个方法是第一个被调用
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        if(RPCClient.LOG_LEVEL<= LOG.LOG_INFO) log.info(ctx.channel()+" 已连接");
        this.context=ctx;
        //设置消息高水位，防止发送队列积压而OOM
        ctx.channel().config().setWriteBufferHighWaterMark(RPCClient.writeBufferHighWaterMark);
    }

    /**
     * @description: netty线程收到信息后调用，解析信息，
     * 若正确，唤醒相应caller，从waiterMap，countMap删除相应的thread和count
     * 若错误，抛弃信息，callers继续等待
     * @param ctx 当前channelHandler所在的环境（重量级对象）
     * @param msg netty线程读取到的信息
     * @return: void
     * @author: 杜科
     * @date: 2020/2/28
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        if(RPCClient.LOG_LEVEL<= LOG.LOG_INFO) log.info("收到信息："+msg+"，准备解码，返回结果");
        ServerMessage serverMessage= (ServerMessage) msg;
        long rpcId=serverMessage.getRpcId();
        Object result=serverMessage.getReselut();
        resultMap.put(rpcId,result);

        if(resultFutureMap.containsKey(rpcId)){ /* 这是一个异步调用 */
            ResultFuture resultFuture = resultFutureMap.get(rpcId);
            synchronized (resultFuture){
                /* 唤醒可能异步调用了resultFuture.get的caller */
                resultFuture.notifyAll();
            }
            return;
        }

        if(waiterMap.containsKey(rpcId)){ /* 这是一个同步调用 */
            Thread caller = waiterMap.get(rpcId);
            if(caller==null) {
                throw new UnknownException("出现严重的未知错误，原在等待队列里的线程——等待的调用 "+rpcId+" 现消失！！！");
            }

            long callerId=caller.getId();
            if(serverMessage.isSucceed()) {
                if(RPCClient.LOG_LEVEL<= LOG.LOG_INFO) log.info("收到发送给线程——"+callerId+" 的成功信息，即将返回结果");
            } else {
                if(RPCClient.LOG_LEVEL<= LOG.LOG_ERROR) log.error("线程——"+callerId+" 第 "+rpcId+" 次调用失败，" +serverMessage.getReselut()+" 即将返回错误提示");
            }

            waiterMap.remove(rpcId);
            LockSupport.unpark(caller);
            return;
        }

        if(RPCClient.LOG_LEVEL<= LOG.LOG_ERROR) log.error("接收到一个超时的调用结果，该调用为———"+rpcId+"，即将抛弃！");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if(RPCClient.LOG_LEVEL<= LOG.LOG_ERROR) log.error("捕获到异常，即将关闭连接！",cause);
        ctx.close();
    }

    /**
     * @description: 当断开连接时,当前RPCClientHandler要从Connector的connectedServiceHandlerMap和
     * connectedChannelHandlerMap中移除
     * pipeline加入到idlePipelineMap
     * @param ctx
     * @return: void
     * @author: 杜科
     * @date: 2020/3/11
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if(RPCClient.LOG_LEVEL<= LOG.LOG_ERROR) log.error(ctx.channel()+" 连接已断开，即将加入空闲连接池");
        Connector.getConnectedChannelHandlerMap().remove(ctx.channel().remoteAddress().toString().substring(1));
        Connector.getIdlePipelineQueue().add(this.getContext().pipeline());
        /**
         * 提醒其他后来的线程不要使用马上要断开连接。
         * 如果是异常断开，那么使用该连接的其他线程将得不到结果（不过会被超时扫描线程唤醒）
         */
        context=null;
        Map<String, RPCClientHandler> connectedServiceHandlerMap = Connector.getConnectedServiceHandlerMap();
        Set<String> keySet = connectedServiceHandlerMap.keySet();
        Set<String> deleteSet=new HashSet<>();
        for (String s : keySet) {//移除所有有关此连接的clientHandler
            if(connectedServiceHandlerMap.get(s)==this)
                deleteSet.add(s);
        }
        for (String s : deleteSet) {
            connectedServiceHandlerMap.remove(s);
        }
        /**
         * 当断开连接后也要把已经解码好的信息fireChannelRead
         */
        super.channelInactive(ctx);
    }



    /**
     * @description: caller调用后，记录相关信息（waiterMap,countMap等），在此阻塞(超时等待结果)，
     * 由netty线程发送信息。
     * @param clientMessage 要发送的消息
     * @return: void
     * @author: 杜科
     * @date: 2020/2/27
     */
    public void sendMsg(ClientMessage clientMessage){
        send(clientMessage,false);
        Thread currentThread = Thread.currentThread();
        boolean interrupted = currentThread.isInterrupted();
        if(interrupted) {
            if(RPCClient.LOG_LEVEL<= LOG.LOG_ERROR) log.error("线程"+currentThread.getId()+" 已被设置中断，park即将失效！");
        }
        /**
         * park可能会毫无理由地返回，caller在getResult的时候要确认已有结果
         * todo 不完全确定真的是这个原因
         */
        LockSupport.park();
    }

    /**
     * @description: 异步发送消息，不阻塞caller
     * @param clientMessage 要发送的消息
     * @return: void
     * @author: 杜科
     * @date: 2020/3/27
     */
    public void sendMsgAsy(ClientMessage clientMessage,ResultFuture resultFuture){
        send(clientMessage,true);
        resultFutureMap.put(clientMessage.getRpcId(),resultFuture);
    }

    /**
     * @description: 发送信息，主要用于设置一些参数。
     * 同步或是异步都把caller加入到waiterMap当中，让netty收到信息后唤醒caller（或者给caller一个消费许可）
     * 如果已达消息高水位，将抛出异常
     * @param clientMessage 要发送的信息
     * @param isAsy 异步标志 true为异步，false为同步
     * @return: void
     * @author: 杜科
     * @date: 2020/3/27
     */
    private void send(ClientMessage clientMessage,boolean isAsy){
        long rpcId=clientMessage.getRpcId();

        /**
         * 同步调用，caller都加入waiterMap当中，
         * 异步调用不需要加入waiterMap，因为不需要通过park 与unpark。在要获取异步调用结果时，另外有同步机制。
         */
        if(!isAsy) waiterMap.put(rpcId,Thread.currentThread());
        /**
         * 细节优化
         * 把自旋移到这里是为了，减少自旋发生的可能性，在真正要用的时候才去判断自旋，让context尽可能已经连接。
         * 如果已达消息高水位，发送队列积压过多消息，那么就把刚刚设置的waiterMap和countMap的参数remove然后抛异常，
         * 异常在caller线程抛出
         */
        while(context==null);//这里直接自旋等待，因为马上就连接上了

        if(!context.channel().isWritable()){
            waiterMap.remove(rpcId);
            throw new MsgSendingFailException("当前发送队列积压过多信息！");
        }

        if(RPCClient.timeout!=-1) doWatch(clientMessage);//进行超时观察
        context.writeAndFlush(clientMessage);//加到任务队列，netty线程发送json文本

        long callerId=Thread.currentThread().getId();
        if(!isAsy) {
            if(RPCClient.LOG_LEVEL<= LOG.LOG_INFO) log.info("线程——"+callerId+"，要同步发送信息"+clientMessage);
        } else {
            if(RPCClient.LOG_LEVEL<= LOG.LOG_INFO) log.info("线程——"+callerId+"，要异步发送信息"+clientMessage);
        }
    }

    /**
     * @description: 对要发送的信息生成一个事件，进行超时观察
     * @param message 要发送的信息
     * @return: void
     * @author: 杜科
     * @date: 2020/3/4
     */
    private void doWatch(ClientMessage message){
        if(watcher.get()==null){//这里不直接cas是因为cas是一条cpu指令，能省则省。
            Thread t1=new Watcher();
            t1.setDaemon(true);
            t1.setName("watcher");//事实上可以像waiterQueue一样在静态代码块中初始化，或者直接加synchronized
            if(watcher.compareAndSet(null,t1)) t1.start();
        }
        waiterQueue.add(new TimeOutEvent(message,System.currentTimeMillis(),this));//加入超时观察队列
    }

    //全局超时观察线程
    private class Watcher extends Thread{
        @Override
        public void run() {
            //shutdown后也会继续完成剩下的的工作
            while(!RPCClient.shutdown||waiterQueue.size()>0){//每次循环检查，同样会让出cpu
                try {
                    TimeOutEvent head=waiterQueue.take();//阻塞获取头
                    if(head.getCreateTime()==0){//shutdown标志
                        if(waiterQueue.size()==0) break;//已经没有后续任务了，结束循环
                        else {//还有后续任务
                            waiterQueue.add(head);//将结束标志加回队尾
                            continue;
                        }
                    }
                    if(System.currentTimeMillis()-head.getCreateTime() <RPCClient.timeout)
                        waiterQueue.add(head);//如果没有超时再加回到队尾
                    else{//如果超时了
                        long rpcId=head.getMessage().getRpcId();
                        if(!waiterMap.containsKey(rpcId)) continue;//实际上已经成功返回
                        // 发生超时，调用注册的监听器的handle方法
                       RPCClient.listener.handle(head, head.getClientHandler());
                    }
                } catch (Exception e) {//cathch包含可能在listen.handle抛出的异常
                    // TODO: handle exception
                    e.printStackTrace();
                }
            }
            if(RPCClient.LOG_LEVEL<= LOG.LOG_INFO) log.info("超时观察者退出");
        }
    }

    /**
     * @description: 由unpark后的caller调用，返回对应的调用结果，并删除缓存（即不缓存上一次的结果）
     * caller应该注意 ClassCastException，因为当超时或异常时，将返回字符串提示。
     * @param rpcId 调用id
     * @return: java.lang.Object
     * @author: 杜科
     * @date: 2020/2/28
     */
    public Object getResultSyn(long rpcId){
        long callerId=Thread.currentThread().getId();
        while (!resultMap.containsKey(rpcId)){
            if(RPCClient.LOG_LEVEL<= LOG.LOG_ERROR) log.error("当前resultMap中没有线程——"+callerId+" 第 "+rpcId+" 次调用的结果，可能该线程park失败，或者被意外唤醒，继续park!");
            /**
             * park有可能毫无逻辑地返回
             */
            LockSupport.park();
        }

        Object result=resultMap.get(rpcId);
        resultMap.remove(rpcId);
        /**
         * 理论上，result不会是null的。
         */
        if(result==null){
            if(RPCClient.LOG_LEVEL<= LOG.LOG_ERROR) log.error("当前resultMap中，线程——"+callerId+" 的结果为null，即将返回null ！");
            return null;
        }

        return result;
    }

    public Object getResultAsy(long rpcId){
        /**
         * 当caller调用了resultFuture.get后，调用，caller自己保证还在有效期内，即在发起rpc后5分钟内调用了这个方法。
         * 这样的话，为空则表示还没有结果，否则已有结果。
         */
        return resultMap.get(rpcId);
    }

    //用于在主线程关闭netty线程组时，主线程往超时观察队列中加入一个节点，让超时观察者苏醒一次去检查关闭标志，进而结束
    protected static BlockingQueue<TimeOutEvent> getWaiterQueue(){return waiterQueue;}

    public ChannelHandlerContext getContext() {
        return context;
    }

    public Map<Long, Thread> getWaiterMap() {
        return waiterMap;
    }

    public Map<Long, Object> getResultMap() {
        return resultMap;
    }



}
