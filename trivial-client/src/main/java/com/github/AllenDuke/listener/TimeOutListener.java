package com.github.AllenDuke.listener;

import com.github.AllenDuke.clientService.RPCClientHandler;
import com.github.AllenDuke.event.TimeOutEvent;

/**
 * @author 杜科
 * @description 超时监听器
 * @contact AllenDuke@163.com
 * @since 2020/3/4
 */
public interface TimeOutListener {

    /**
     * @description: 对某一pipeline中发生的超时事件进行处理
     * @param event 超时事件
     * @param clientHandler 处理该事件的RPCClientHandler（从中可以获取到对应的channel）
     * @return: void
     * @author: 杜科
     * @date: 2020/3/11
     */
    void handle(TimeOutEvent event, RPCClientHandler clientHandler);
}
