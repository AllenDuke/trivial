package com.github.AllenDuke.clientService;

import com.alibaba.fastjson.JSON;
import com.github.AllenDuke.dto.ClientMessage;
import com.github.AllenDuke.dto.ServerMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * @author 杜科
 * @description rpc消费者的业务处理器
 * 所有caller的消息经此发出，因此caller在此park。
 * 当业务处理器收到服务端的结果时，会根据结果中的线程id unpark相应的caller。
 *
 * 这里多处用到HashMap而不是ConcurrentHashMap是因为，虽然存在并发行为，但彼此只操作自己的数据并没有影响他人。
 * 要发送的信息会封装成任务，加入到eventLoop的TaskQueue
 * 这里的超时机制采用的是：超时向原目的主机重发原信息（后续会将超时请求重新路由到别的主机，另有详细信息）
 * @contact AllenDuke@163.com
 * @since 2020/2/11
 */
@Slf4j
public class RPCClientHandler extends ChannelInboundHandlerAdapter {

    private ChannelHandlerContext context;

    //调用者线程在各自的条件上等待，并发性能差，要先抢夺锁
//    private final Map<Long, Condition> waitingThreadMap=new HashMap();

    //callerId与caller，park后加入，unpark后删除
    private final Map<Long, Thread> waiterMap=new HashMap<>();

    //各caller的当前调用结果（这里一直会缓存上一次的结果）
    private final Map<Long,Object> resultMap=new HashMap<>();

    //各caller的当前条用次数，park后加入，unpark后删除
    private final Map<Long,Long> countMap=new HashMap<>();

    //记录每个caller发起第count次调用的时间，用作超时
    protected static class CountDownNode{
        ClientMessage message;//应尽量缩减message的信息，避免不必要的传输(后面将加入超时时长，供服务提供方使用)
        long createTime;
        int retryNum;

        public CountDownNode(ClientMessage message, long createTime) {
            this.message=message;
            this.createTime = createTime;
            this.retryNum=RPCClient.retryNum;
        }

        @Override
        public int hashCode() {
            return (int) message.getCallerId()+(int) message.getCount();
        }

        @Override
        public boolean equals(Object obj) {
            CountDownNode t=(CountDownNode)obj;
            return this.message.getCallerId()==t.message.getCallerId()
                    &&this.message.getCount()==t.message.getCount();
        }
    }

    //超时观察队列
    private BlockingQueue<CountDownNode> waiterQueue;
    {if(RPCClient.timeout!=-1)waiterQueue=new LinkedBlockingQueue<>();}

    //超时观察者，原子变量避免并发创建
    private final AtomicReference<Thread> watcher=new AtomicReference<>();

    //与服务器的连接创建后，就会被调用, 这个方法是第一个被调用
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
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
        log.info("客户端收到信息："+msg+"，准备解码，返回结果");
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
            LockSupport.unpark(waiterMap.get(callerId));
            waiterMap.remove(callerId);
            countMap.remove(callerId);
        }else {
            log.info("收到发送给线程——"+callerId+" 的历史信息，即将即将抛弃，继续等待");//上次调用超时
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        ctx.close();
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
        long callerId=clientMessage.getCallerId();
        waiterMap.put(callerId,Thread.currentThread());//caller加入map当中
        countMap.put(callerId,clientMessage.getCount());
        if(RPCClient.timeout!=-1){
            doWatch(clientMessage);
        }
        context.writeAndFlush(JSON.toJSONString(clientMessage));//加到任务队列，netty线程发送json文本
        log.info("线程——"+callerId+"，要发送信息"+clientMessage);
        LockSupport.park();
    }

    /**
     * @description: 对caller的第count次调用进行超时观察，每次阻塞地拉取队头，
     * 1.若超时：
     * （1）.检查是否已经成功返回，
     * （2）.检查是否可以重试，若可以则重发信息（仍然是原来的信息），
     * （3）.重试仍超时，unpark相应的caller，将返回超时提示的字符串（caller要注意ClassCastException）
     * 2.若没有超时，重新加到队尾
     * @param message
     * @return: void
     * @author: 杜科
     * @date: 2020/2/28
     */
    private void doWatch(ClientMessage message){
        if(watcher.get()==null){//这里不直接cas是因为cas是一条cpu指令，能省则省。
            Thread t1=new Watcher();
            t1.setName("watcher");//事实上可以像waiterQueue一样在成员代码块中初始化
            if(watcher.compareAndSet(null,t1)) t1.start();
        }
        waiterQueue.add(
                new CountDownNode(message,System.currentTimeMillis()));//加入超时观察队列
    }

    private class Watcher extends Thread{
        @Override
        public void run() {
            while(!RPCClient.shutdown){//每次循环检查是否已经关闭
                try {
                    CountDownNode head=waiterQueue.take();//阻塞获取头
                    if(System.currentTimeMillis()-head.createTime <RPCClient.timeout)
                        waiterQueue.add(head);//如果没有超时再加回到队尾
                    else{//如果超时了
                        long callerId=head.message.getCallerId();
                        long count=head.message.getCount();
                        if(countMap.get(callerId)==null
                                || countMap.get(callerId)!=count) continue;//实际上已经成功返回
                        if(head.retryNum>0){
                            head.retryNum--;
                            log.error("线程——"+callerId+" 第 "+count +" 次调用超时，即将进行第 "
                                    +(RPCClient.retryNum-head.retryNum)+" 次重试");
                            context.writeAndFlush(head.message);//重发信息
                            continue;
                        }
                        resultMap.put(callerId,"调用超时");
                        log.error("线程—— "+callerId+" 第 "+count
                                +"次调用超时，已重试 "+RPCClient.retryNum+" 次，即将返回超时提示");
                        LockSupport.unpark(waiterMap.get(callerId));
                        waiterMap.remove(callerId);
                        countMap.remove(callerId);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            log.info("超时观察者退出");
        }
    }

    /**
     * @description: 由unpark后的caller调用，返回对应的调用结果
     * caller应该注意 ClassCastException，因为当超时或异常时，将返回字符串提示。
     * @param callerId 线程id
     * @return: java.lang.Object
     * @author: 杜科
     * @date: 2020/2/28
     */
    public Object getResult(long callerId){
        return resultMap.get(callerId);
    }

    //用于在主线程关闭netty线程组时，主线程往超时观察队列中加入一个节点，让超时观察者苏醒一次去检查关闭标志，进而结束
    protected BlockingQueue<CountDownNode> getWaiterQueue(){return this.waiterQueue;}

}
