package com.github.AllenDuke.producerService;


import com.alibaba.fastjson.JSON;
import com.github.AllenDuke.business.InvokeErrorNode;
import com.github.AllenDuke.business.InvokeHandler;
import com.github.AllenDuke.business.InvokeTask;
import com.github.AllenDuke.business.LRU;
import com.github.AllenDuke.dto.ClientMessage;
import com.github.AllenDuke.dto.ServerMessage;
import com.github.AllenDuke.exception.ConnectionIdleException;
import com.github.AllenDuke.myThreadPoolService.ThreadPoolService;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketAddress;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * @author 杜科
 * @description rpc服务提供者的业务处理器
 * 由netty线程负责接收来自客户端的信息，（由业务线程池）解析信息，调用相关方法，写回结果。
 * 这里有多出try catch是为了防止netty线程在处理时异常退出。
 * @contact AllenDuke@163.com
 * @since 2020/2/11
 */
@Slf4j
public class RPCServerHandler extends SimpleChannelInboundHandler {

    //调用处理器，用来找到方法进行调用
    private static final InvokeHandler invokeHandler=new InvokeHandler();

    //jdk业务线程池
    private static final ThreadPoolExecutor executor=RPCServer.executor;

    //自实现线程池
    private static final ThreadPoolService poolService=RPCServer.poolService;

    //缓存一些错误调用信息，用来进行一些简单的防护
    private static volatile LRU<String,InvokeErrorNode> lru;

    /**
     * @description: 检查每一个连上来的客户端在lru中的情况，如果一天内调用错误次数已达到100次，将立马断开连接
     * @param ctx
     * @return: void
     * @author: 杜科
     * @date: 2020/4/4
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        SocketAddress remoteAddress = ctx.channel().remoteAddress();
        String addr=remoteAddress.toString();
        log.info(addr+" 正在尝试连接...");
        if(lru!=null){
            addr=addr.substring(1,addr.indexOf(":"));
            InvokeErrorNode errorNode = lru.get(addr);
            if(errorNode==null||System.currentTimeMillis()-errorNode.getLastTime()>1000*60*60*24
                    ||errorNode.getErrCount()<100) {
                System.out.println(remoteAddress.toString() + " 成功连接");
                super.channelActive(ctx);
                return;
            }
            log.error("该远程客户端一天内调用错误次数达到100次，认为已受到了该客户端的攻击，" +
                    "24小时内不允许再次连接，将告知客户端并断开连接");
            ServerMessage serverMessage=new ServerMessage(0,0,false,
                    "请24小时之后再试！");
            ctx.writeAndFlush(JSON.toJSONString(serverMessage));
            ctx.close();
            return;
        }
        log.info(remoteAddress.toString() + " 成功连接");
        super.channelActive(ctx);
    }

    /**
     * @description: 由netty线程负责接收来自客户端的信息，解析信息，调用相关方法，写回结果
     * 如果有线程池，将会把信息封装成任务提交到线程池
     * @param ctx 当前channelHandler所在的环境（重量级对象）
     * @param msg netty线程读取到的信息
     * @return: void
     * @author: 杜科
     * @date: 2020/2/28
     */
    @Override
    public void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        log.info("收到信息：" + msg + "，准备解码，调用服务");
        ClientMessage clientMessage;

        try{
            clientMessage = JSON.parseObject((String) msg, ClientMessage.class);
        }catch (Exception e){
            log.error("解析异常，放弃本次解析任务，即将通知客户端",e);
            ServerMessage serverMessage=new ServerMessage(0
                    ,0, false,"服务器解析异常");
            ctx.writeAndFlush(JSON.toJSONString(serverMessage));
            recordInvokeException(ctx,e);
            return;
        }

        /**
         * 例如传进来的是serviceA，那么我们的实现应该serviceAImpl
         */
        clientMessage.setClassName(clientMessage.getClassName()+"Impl");//加上后缀

        /**
         * 如果有设定业务线程池，那么封装成任务，提交到线程池
         */
        if(RPCServer.businessPoolModel>0){
            try{
                if(RPCServer.businessPoolModel==1)//jdk线程池
                    executor.execute(new InvokeTask(clientMessage,invokeHandler,ctx));
                if(RPCServer.businessPoolModel==2)//自实现线程池
                    poolService.execute(new InvokeTask(clientMessage,invokeHandler,ctx));
            }catch (Exception e){
                log.error("线程池拒绝任务，即将通知客户端",e);
                ServerMessage serverMessage=new ServerMessage(clientMessage.getCallerId()
                        ,clientMessage.getCount(), false,"服务器繁忙！");
                ctx.writeAndFlush(JSON.toJSONString(serverMessage));
                recordInvokeException(ctx,e);//记录异常调用
            }finally {
                return;
            }
        }

        Object result;//调用结果

        try {
            result= invokeHandler.handle(clientMessage);//由netty io线程直接调用
        }catch (Exception e){
            log.error("实现方法调用异常，放弃本次调用服务，即将通知本次调用者",e);
            ServerMessage serverMessage=new ServerMessage(clientMessage.getCallerId()
                    ,clientMessage.getCount(), false,"实现方法调用异常");
            ctx.writeAndFlush(JSON.toJSONString(serverMessage));
            recordInvokeException(ctx,e);//记录异常调用
            return;
        }

        ServerMessage serverMessage=new ServerMessage(clientMessage.getCallerId()
                ,clientMessage.getCount(),true,result);
        log.info("实现方法调用成功，即将返回信息："+serverMessage);
        ctx.writeAndFlush(JSON.toJSONString(serverMessage));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("捕获到异常，即将关闭连接！",cause);
        super.exceptionCaught(ctx,cause);
    }

    /**
     * @description: 这里只是负责记录远程客户端调用异常的次数，并作出处理，如达阈值后将断开连接。
     * @param ctx 调用所处的连接
     * @param cause 具体的调用异常
     * @return: void
     * @author: 杜科
     * @date: 2020/4/5
     */
    public void recordInvokeException(ChannelHandlerContext ctx, Throwable cause){
        if(lru==null){//volatile double-check防止指令重排序拿到半初始化对象
            synchronized (invokeHandler){
                if(lru==null) lru=new LRU<>();
            }
        }
        SocketAddress remoteAddress = ctx.channel().remoteAddress();
        if(remoteAddress==null) {
            log.info("连接已被远程客户端断开！");
            return;
        }
        String addr=remoteAddress.toString();
        addr=addr.substring(1,addr.indexOf(":"));
        InvokeErrorNode errorNode;
        synchronized (lru){
            /**
             * 使用synchronized暴力地解决并发问题，但正常情况下这种并发是不高的，而异常情况发生的概率也不大
             * todo 下次优化将使用自实现的ConcurrentLinkedHashMap来做LRU
             */
            errorNode = lru.get(addr);
            if(errorNode==null||System.currentTimeMillis()-errorNode.getLastTime()>1000*60*60*24) {
                errorNode=new InvokeErrorNode(System.currentTimeMillis(),1);
                lru.put(addr,errorNode);
            } else{
                errorNode.setErrCount(errorNode.getErrCount()+1);
                lru.put(addr,errorNode);
            }
        }
        /**
         * 当同一批同时达到时，将触发多次，但并无实际影响，close方法可以调用多次而只生效一次
         */
        if(errorNode.getErrCount()>=100) {
            log.error("该远程客户端一天内调用错误次数达到100次，认为已受到了该客户端的攻击，" +
                    "24小时内不允许再次连接，将告知客户端并断开连接",cause);
            ctx.close();
        }
        return;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if(evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            String eventType = null;
            switch (event.state()) {
                case READER_IDLE:
                    eventType = "读空闲";
                    break;
                case WRITER_IDLE:
                    eventType = "写空闲";
                    break;
                case ALL_IDLE:
                    eventType = "读写空闲";
                    break;
            }
            log.error("channel: "+ctx.channel()+eventType, new ConnectionIdleException("发生连接空闲，即将断开"));
            ctx.channel().close();
        }
    }
}
