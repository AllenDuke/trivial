package com.github.AllenDuke.producerService;


import com.alibaba.fastjson.JSON;
import com.github.AllenDuke.business.InvokeErrorNode;
import com.github.AllenDuke.business.InvokeHandler;
import com.github.AllenDuke.business.InvokeTask;
import com.github.AllenDuke.dto.ClientMessage;
import com.github.AllenDuke.dto.ServerMessage;
import com.github.AllenDuke.exception.ConnectionIdleException;
import com.github.AllenDuke.myThreadPoolService.ThreadPoolService;
import com.github.AllenDuke.business.LRU;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketAddress;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * @author 杜科
 * @description rpc服务提供者的业务处理器
 * 由netty线程负责接收来自客户端的信息，解析信息，调用相关方法，写回结果，后续版本会使用业务线程池来更新
 * 这里有多出try catch是为了防止netty线程在处理时异常退出。
 * @contact AllenDuke@163.com
 * @since 2020/2/11
 */
@Slf4j
public class RPCServerHandler extends ChannelInboundHandlerAdapter {

    private static final InvokeHandler invokeHandler=new InvokeHandler();

    private static final ThreadPoolExecutor executor=RPCServer.executor;

    private static final ThreadPoolService poolService=RPCServer.poolService;

    // todo 修改以主机地址为单位
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
        if(lru!=null){
            SocketAddress remoteAddress = ctx.channel().remoteAddress();
            String addr=remoteAddress.toString();
            addr=addr.substring(1,addr.indexOf(":"));
            InvokeErrorNode errorNode = lru.get(addr);
            if(errorNode==null||System.currentTimeMillis()-errorNode.getLastTime()>1000*60*60*24
                    ||errorNode.getErrCount()<100) return;
            log.error("该远程客户端一天内调用错误次数达到100次，认为已受到了该客户端的攻击，" +
                    "24小时内不允许再次连接，将告知客户端并断开连接");
            ServerMessage serverMessage=new ServerMessage(0,0,false,
                    "请24小时之后再试！");
            ctx.writeAndFlush(JSON.toJSONString(serverMessage));
            ctx.close();
        }
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
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        log.info("收到信息：" + msg + "，准备解码，调用服务");
        ClientMessage clientMessage;
        try{
            clientMessage = JSON.parseObject((String) msg, ClientMessage.class);
        }catch (Exception e){
            log.error("解析异常，放弃本次解析任务，即将通知客户端",e);
            ctx.writeAndFlush("服务器解析异常");
            handleInvokeException(ctx,e);
            return;
        }
        clientMessage.setClassName(clientMessage.getClassName()+"Impl");//加上后缀
        if(RPCServer.businessPoolModel==1) {
            executor.execute(new InvokeTask(clientMessage,invokeHandler,ctx));
            return;
        }
        if(RPCServer.businessPoolModel==2){
            poolService.execute(new InvokeTask(clientMessage,invokeHandler,ctx));
            return;
        }
        Object result;
        try {
            result= invokeHandler.handle(clientMessage);
        }catch (Exception e){
            log.error("实现方法调用异常，放弃本次调用服务，即将通知本次调用者",e);
            ServerMessage serverMessage=new ServerMessage(clientMessage.getCallerId()
                    ,clientMessage.getCount()
                    , false,"实现方法调用异常");
            ctx.writeAndFlush(JSON.toJSONString(serverMessage));
            handleInvokeException(ctx,e);
            return;
        }
        ServerMessage serverMessage=new ServerMessage(clientMessage.getCallerId()
                ,clientMessage.getCount(),true,result);
        log.info("实现方法调用成功，即将返回信息："+serverMessage);
        ctx.writeAndFlush(JSON.toJSONString(serverMessage));//转换为json文本
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        ctx.close();
    }

    public void handleInvokeException(ChannelHandlerContext ctx, Throwable cause){
        if(lru==null){//volatile double-check防止指令重排序拿到半初始化对象
            synchronized (invokeHandler){
                if(lru==null) lru=new LRU<>();
            }
        }
        SocketAddress remoteAddress = ctx.channel().remoteAddress();
        String addr=remoteAddress.toString();
        addr=addr.substring(1,addr.indexOf(":"));
        InvokeErrorNode errorNode = lru.get(addr);
        /**
         * 使用synchronized暴力地解决并发问题，todo 下次优化将使用自实现的ConcurrentLinkedHashMap来做LRU
         */
        if(errorNode==null||System.currentTimeMillis()-errorNode.getLastTime()>1000*60*60*24) {
            errorNode=new InvokeErrorNode(System.currentTimeMillis(),1);
            lru.put(addr,errorNode);
        } else{
            errorNode.setErrCount(errorNode.getErrCount()+1);
            lru.put(addr,errorNode);
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
            log.error("channel: "+ctx.channel()+eventType,
                    new ConnectionIdleException("发生连接空闲，即将断开"));
            ctx.channel().close();
        }
    }
}
