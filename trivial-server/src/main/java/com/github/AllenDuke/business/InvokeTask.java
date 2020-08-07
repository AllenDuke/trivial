package com.github.AllenDuke.business;

import com.alibaba.fastjson.JSON;
import com.github.AllenDuke.dto.ClientMessage;
import com.github.AllenDuke.dto.ServerMessage;
import com.github.AllenDuke.exception.MethodNotFoundException;
import com.github.AllenDuke.producerService.RPCServerHandler;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;

/**
 * @author 杜科
 * @description 调用任务，加入线程池执行，任务结束后会向channel写入信息
 * @contact AllenDuke@163.com
 * @since 2020/3/1
 */
@Slf4j
public class InvokeTask implements Runnable {

    //客户端发来的信息
    private ClientMessage clientMessage;

    //调用处理器
    private InvokeHandler invokehandler;

    //当前channel的上下文，用于调用writeAndFlush
    private ChannelHandlerContext ctx;

    public InvokeTask(ClientMessage clientMessage, InvokeHandler invokehandler, ChannelHandlerContext ctx){
        this.clientMessage=clientMessage;
        this.invokehandler=invokehandler;
        this.ctx=ctx;
    }

    @Override
    public void run() {
        Object result=null;
        try {
            result=invokehandler.handle(clientMessage);
        } catch (ClassNotFoundException e) {
            log.error("找不到要调用的类，放弃本次调用，即将通知调用者",e);
            ServerMessage serverMessage=new ServerMessage(clientMessage.getRpcId()
                    , false,"找不到要调用的类，请检查类名");
            ctx.writeAndFlush(JSON.toJSONString(serverMessage));
            RPCServerHandler handler = (RPCServerHandler) ctx.handler();
            handler.recordInvokeException(ctx,e);
            return;
        } catch(MethodNotFoundException e){
            log.error("找不到要调用的方法，放弃本次调用，即将通知调用者",e);
            ServerMessage serverMessage=new ServerMessage(clientMessage.getRpcId()
                    , false,"找不到要调用的方法，请检查方法名和参数");
            ctx.writeAndFlush(JSON.toJSONString(serverMessage));
            RPCServerHandler handler = (RPCServerHandler) ctx.handler();
            handler.recordInvokeException(ctx,e);
            return;
        } catch (Exception e){
            log.error("方法调用异常，放弃本次调用，即将通知调用者",e);
            ServerMessage serverMessage=new ServerMessage(clientMessage.getRpcId()
                    , false,"服务器的实现方法调用异常");
            ctx.writeAndFlush(JSON.toJSONString(serverMessage));
            RPCServerHandler handler = (RPCServerHandler) ctx.handler();
            handler.recordInvokeException(ctx,e);
            return;
        }
        ServerMessage serverMessage=new ServerMessage(clientMessage.getRpcId(),true,result);
        log.info("实现方法调用成功，即将返回信息："+serverMessage);
        ctx.writeAndFlush(JSON.toJSONString(serverMessage));//转换为json文本，由netty线程发送
    }
}
