package com.github.AllenDuke.listener;

import com.github.AllenDuke.clientService.Connector;
import com.github.AllenDuke.clientService.RPCClient;
import com.github.AllenDuke.clientService.RPCClientHandler;
import com.github.AllenDuke.clientService.TimeOutResult;
import com.github.AllenDuke.dto.ClientMessage;
import com.github.AllenDuke.event.TimeOutEvent;
import com.github.AllenDuke.exception.InvokeTimeOutException;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

/**
 * @author 杜科
 * @description 默认的超时监听器，发生超时事件后被超时线程调用handle方法
 * @contact AllenDuke@163.com
 * @since 2020/3/4
 */
@Slf4j
public class DefaultTimeOutListener implements TimeOutListener {

    //调用超时的标志性结果
    private static TimeOutResult timeOutResult=new TimeOutResult("调用超时");

    /**
     * @description: 对超时事件进行处理，
     * 如果可以重试，那么就向重试，
     * 如果不可以就做下记录，unpark相应的caller，将返回超时提示的字符串（caller要注意ClassCastException）
     * @param event 超时事件
     * @param clientHandler 该超时事件发生所在pipeline中的RPCClientHandler
     * @return: void
     * @author: 杜科
     * @date: 2020/3/4
     */
    @Override
    public void handle(TimeOutEvent event, RPCClientHandler clientHandler) {
        long rpcId=event.getMessage().getRpcId();
        Map<Long,Thread> waiterMap=clientHandler.getWaiterMap();
        long callerId=waiterMap.get(rpcId).getId();
        int retryNum=event.getRetryNum();
        ClientMessage clientMessage=event.getMessage();
        ChannelHandlerContext context=clientHandler.getContext();
        if(context!=null&&retryNum>0){
            retryNum--;
            event.setRetryNum(retryNum);
            log.error("线程——"+callerId+" 第 "+rpcId +" 次调用超时，即将进行第 "
                    +(RPCClient.retryNum-retryNum)+" 次重试");
            context.writeAndFlush(clientMessage);//向原机重发信息
            return;
        }
        //--------重试仍失败
        /**
         * 记录到Connector的TimeOutMap，并从connectedMap中移除，不应该断开连接的，原因如下：
         * 1.有可能别的线程正在使用该channel
         * 2.该调用超时，只能说明该生产者的该服务可能挂掉了，但是其他服务可能是正常，不能一概而论。
         * 所以消费者下次调用的使用该服务的时候，过滤掉该主机即可。
         * 当该生产者真的所有服务都挂掉时，那么自然所有消费者调用该服务时都会过滤掉该主机，
         * 已存在的连接将会没人用而空闲，最终会被生产者断开连接，而消费者可以将断开的连接重用。
         */
        Map<String, Set<String>> timeOutMap = Connector.getTimeOutMap();
        Set<String> blackList = timeOutMap.get(clientMessage.getClassName());
        if (blackList==null) {
            blackList=new HashSet<>();
            timeOutMap.put(clientMessage.getClassName(),blackList);
        }
        if(context!=null){
            String remoteAddress = context.channel().remoteAddress().toString();
            blackList.add(remoteAddress.substring(1));//去掉'/'
            log.error("生产者："+remoteAddress+" 进入服务："+clientMessage.getClassName()+" 的黑名单");
            Connector.getConnectedServiceHandlerMap().
                    remove(clientMessage.getClassName());//有可能有人同时在拿去这个clientHandler，不过问题不大，可以接收
            log.error("服务："+clientMessage.getClassName()+" 超时假断连，不让新的线程使用该生产者提供的该服务");
        }
        if(context==null) log.error("连接已经被远程服务端断开！");
        Map<Long,Object> resultMap=clientHandler.getResultMap();
        resultMap.put(rpcId,timeOutResult);
        log.error("线程——"+callerId+" 第 "+rpcId
                +"次调用超时，已重试 "+RPCClient.retryNum
                +" 次，即将返回超时提示",new InvokeTimeOutException("调用超时"));
        LockSupport.unpark(waiterMap.get(rpcId));
        waiterMap.remove(rpcId);
        return;
    }
}