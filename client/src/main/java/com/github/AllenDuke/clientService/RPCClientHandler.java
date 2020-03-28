package com.github.AllenDuke.clientService;

import com.alibaba.fastjson.JSON;
import com.github.AllenDuke.dto.ClientMessage;
import com.github.AllenDuke.dto.ServerMessage;
import com.github.AllenDuke.event.TimeOutEvent;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
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
public class RPCClientHandler extends ChannelInboundHandlerAdapter {

    //对应的ChannelHandlerContext
    private volatile ChannelHandlerContext context;//一个线程连上或断连后，其他线程要感知到

    //以下static数据为各pipeline共有
    //调用者线程在各自的条件上等待，并发性能差，要先抢夺锁
//    private static final Map<Long, Condition> waitingThreadMap=new HashMap();

    //callerId与caller，park后加入，unpark后删除
    private static final Map<Long, Thread> waiterMap=new HashMap<>();//todo 换ConcurrentHashMap来保证可见性

    //各caller的当前调用结果（这里一直会缓存上一次的结果）
    private static final Map<Long,Object> resultMap=new HashMap<>();

    //各caller的当前调用用次数，park后加入，unpark后删除
    private static final Map<Long,Long> countMap=new HashMap<>();

    //超时观察队列
    private static BlockingQueue<TimeOutEvent> waiterQueue;
    static {if(RPCClient.timeout!=-1)waiterQueue=new LinkedBlockingQueue<>();}

    //超时观察者，原子变量避免并发创建
    private static final AtomicReference<Thread> watcher=new AtomicReference<>();

    //与服务器的连接创建后，就会被调用, 这个方法是第一个被调用
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.info(ctx.channel()+" 已连接");
        this.context=ctx;
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
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        log.info("收到信息："+msg+"，准备解码，返回结果");
        ServerMessage serverMessage=null;
        try{
            serverMessage=JSON.parseObject((String) msg, ServerMessage.class);
        }catch (Exception e){
            log.error("解析异常，收到错误的信息，即将抛弃，callers继续等待",e);
            return;
        }
        long callerId=serverMessage.getCallerId();
        long count=serverMessage.getCount();
        if(countMap.containsKey(callerId)&&countMap.get(callerId).equals(count)){//是本次调用结果
            if(serverMessage.isSucceed()) log.info("收到发送给线程——"+callerId+" 的成功信息，即将返回结果");
            else log.error("线程——"+callerId+" 第 "+count+" 次调用失败，"
                    +serverMessage.getReselut()+" 即将返回错误提示");
            resultMap.put(callerId,serverMessage.getReselut());
            Thread caller = waiterMap.get(callerId);
            LockSupport.unpark(caller);//这里如果是异步调用，那么caller先获得一次许可，避免lost-wake-up
            waiterMap.remove(callerId);
            countMap.remove(callerId);
        }else {//如果断开了连接是收不到历史信息的
            log.info("收到发送给线程——"+callerId+" 的历史信息，即将即将抛弃，继续等待");//上次调用超时
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
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
        log.error(ctx.channel()+" 连接已断开，即将加入空闲连接池");
        Connector.getConnectedChannelHandlerMap().remove(ctx.channel().remoteAddress().toString().substring(1));
        context=null;//提醒其他线程不要使用马上要断开连接
        Map<String, RPCClientHandler> connectedServiceHandlerMap = Connector.getConnectedServiceHandlerMap();
        Set<String> keySet = connectedServiceHandlerMap.keySet();
        for (String s : keySet) {//移除所有有关此连接的clientHandler
            if(connectedServiceHandlerMap.get(s)==this) connectedServiceHandlerMap.remove(s);
        }

        Connector.getIdlePipelineQueue().add(this.getContext().pipeline());
    }



    /**
     * @description: caller调用后，记录相关信息（waiterMap,countMap等），在此阻塞(超时等待结果)，
     * 由netty线程发送信息。
     * @param clientMessage 要发送的消息
     * @return: void
     * @author: 杜科
     * @date: 2020/2/27
     */
    public void sendMsg(ClientMessage clientMessage) throws InterruptedException {
        send(clientMessage,false);
        LockSupport.park();
    }

    /**
     * @description: 异步发送消息，不阻塞caller
     * @param clientMessage 要发送的消息
     * @return: void
     * @author: 杜科
     * @date: 2020/3/27
     */
    public void sendMsgAsy(ClientMessage clientMessage){
        send(clientMessage,true);
    }

    /**
     * @description: 发送信息，主要用于设置一些参数。
     * 同步或是异步都把caller加入到waiterMap当中，让netty收到信息后唤醒caller（或者给caller一个消费许可）
     * @param clientMessage 要发送的信息
     * @param isAsy 异步标志 true为异步，false为同步
     * @return: void
     * @author: 杜科
     * @date: 2020/3/27
     */
    private void send(ClientMessage clientMessage,boolean isAsy){
        long callerId=clientMessage.getCallerId();
        if(resultMap.containsKey(callerId)) LockSupport.park();//如果上一次调用的结果没有去获取，那么就先消费上一次的许可
        /**
         * 同步或者异步调用，caller都加入waiterMap当中，
         * 若为异步调用，可利用此来避免lost-wake-up
         */
        waiterMap.put(callerId,Thread.currentThread());
        countMap.put(callerId,clientMessage.getCount());
        while(context==null);//这里直接自旋等待，因为马上就连接上了
        if(RPCClient.timeout!=-1) doWatch(clientMessage);//进行超时观察
        context.writeAndFlush(JSON.toJSONString(clientMessage));//加到任务队列，netty线程发送json文本
        if(!isAsy) log.info("线程——"+callerId+"，要同步发送信息"+clientMessage);
        else log.info("线程——"+callerId+"，要异步发送信息"+clientMessage);
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
        waiterQueue.add(new TimeOutEvent(message,System.currentTimeMillis()));//加入超时观察队列
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
                        long callerId=head.getMessage().getCallerId();
                        long count=head.getMessage().getCount();
                        if(countMap.get(callerId)==null
                                || countMap.get(callerId)!=count) continue;//实际上已经成功返回
                        // 发生超时，调用注册的监听器的handle方法
                       RPCClient.listener.handle(head, (RPCClientHandler) context.handler());
                    }
                } catch (Exception e) {//cathch包含可能在listen.handle抛出的异常
                    // TODO: handle exception
                    e.printStackTrace();
                }
            }
            log.info("超时观察者退出");
        }
    }

    /**
     * @description: 由unpark后的caller调用，返回对应的调用结果，并删除缓存（即不缓存上一次的结果）
     * caller应该注意 ClassCastException，因为当超时或异常时，将返回字符串提示。
     * @param callerId 线程id
     * @return: java.lang.Object
     * @author: 杜科
     * @date: 2020/2/28
     */
    public Object getResult(long callerId){
        Object result=resultMap.get(callerId);
        if(result!=null) resultMap.remove(callerId);
        return result;
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

    public Map<Long, Long> getCountMap() {
        return countMap;
    }

}
