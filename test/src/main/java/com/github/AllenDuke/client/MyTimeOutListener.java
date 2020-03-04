package com.github.AllenDuke.client;

import com.github.AllenDuke.event.TimeOutEvent;
import com.github.AllenDuke.exception.InvokeTimeOutException;
import com.github.AllenDuke.listener.TimeOutListener;

/**
 * @author 杜科
 * @description
 * @contact AllenDuke@163.com
 * @since 2020/3/4
 */
public class MyTimeOutListener implements TimeOutListener {
    @Override
    public void handle(TimeOutEvent event) {
        throw new InvokeTimeOutException();//经常将会在超时扫描线程内抛出
    }
}
