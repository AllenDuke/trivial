package com.github.AllenDuke.listener;

import com.alibaba.fastjson.JSON;
import com.github.AllenDuke.clientService.RPCClient;
import com.github.AllenDuke.clientService.RPCClientHandler;
import com.github.AllenDuke.dto.ClientMessage;
import com.github.AllenDuke.event.TimeOutEvent;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/**
 * @author 杜科
 * @description 超时监听器，发生超时事件后被超时线程调用handle方法
 * @contact AllenDuke@163.com
 * @since 2020/3/4
 */
@Slf4j
public class DefaultTimeOutListener implements TimeOutListener {

    /**
     * @description: 对超时事件进行处理，
     * 如果可以重试，那么就重试，
     * 如果不可以就unpark相应的caller，将返回超时提示的字符串（caller要注意ClassCastException）
     * @param event 超时事件
     * @param clientHandler 该超时事件发生所在pipeline中的RPCClientHandler
     * @return: void
     * @author: 杜科
     * @date: 2020/3/4
     */
    @Override
    public void handle(TimeOutEvent event, RPCClientHandler clientHandler) {
        long callerId=event.getMessage().getCallerId();
        long count=event.getMessage().getCount();
        int retryNum=event.getRetryNum();
        ClientMessage clientMessage=event.getMessage();
        ChannelHandlerContext context=clientHandler.getContext();
        if(retryNum>0){
            retryNum--;
            event.setRetryNum(retryNum);
            log.error("线程——"+callerId+" 第 "+count +" 次调用超时，即将进行第 "
                    +(RPCClient.retryNum-retryNum)+" 次重试");
            context.writeAndFlush(JSON.toJSONString(clientMessage));//重发信息
            return;
        }
        Map<Long,Object> resultMap=clientHandler.getResultMap();
        Map<Long,Thread> waiterMap=clientHandler.getWaiterMap();
        Map<Long,Long> countMap=clientHandler.getCountMap();
        resultMap.put(callerId,"调用超时");
        log.error("线程—— "+callerId+" 第 "+count
                +"次调用超时，已重试 "+RPCClient.retryNum+" 次，即将返回超时提示");
        LockSupport.unpark(waiterMap.get(callerId));
        waiterMap.remove(callerId);
        countMap.remove(callerId);
        return;
    }
}
