package com.github.AllenDuke.client;

import com.github.AllenDuke.clientService.RPCClientHandler;
import com.github.AllenDuke.event.TimeOutEvent;
import com.github.AllenDuke.exception.InvokeTimeOutException;
import com.github.AllenDuke.listener.TimeOutListener;

/**
 * @author 杜科
 * @description 超时监听器
 * @contact AllenDuke@163.com
 * @since 2020/3/4
 */
public class MyTimeOutListener implements TimeOutListener {
    @Override
    public void handle(TimeOutEvent event, RPCClientHandler clientHandler) {
        throw new InvokeTimeOutException();//异常将会在超时扫描线程内抛出
    }
}
