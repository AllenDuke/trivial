package com.github.AllenDuke.producerService;


import com.alibaba.fastjson.JSON;
import com.github.AllenDuke.business.InvokeHandler;
import com.github.AllenDuke.business.InvokeTask;
import com.github.AllenDuke.dto.ClientMessage;
import com.github.AllenDuke.dto.ServerMessage;
import com.github.AllenDuke.exception.ConnectionIdleException;
import com.github.AllenDuke.myThreadPoolService.ThreadPoolService;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

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
        ClientMessage clientMessage=null;
        try{
            clientMessage = JSON.parseObject((String) msg, ClientMessage.class);
        }catch (Exception e){
            log.error("解析异常，放弃本次解析任务，即将通知客户端",e);
            ctx.writeAndFlush("服务器解析异常");
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
        Object result=null;
        try {
            result= invokeHandler.handle(clientMessage);
        }catch (Exception e){
            log.error("实现方法调用异常，放弃本次调用服务，即将通知本次调用者",e);
            ServerMessage serverMessage=new ServerMessage(clientMessage.getCallerId()
                    ,clientMessage.getCount()
                    , false,"实现方法调用异常");
            ctx.writeAndFlush(JSON.toJSONString(serverMessage));
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
