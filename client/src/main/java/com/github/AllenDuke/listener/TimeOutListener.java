package com.github.AllenDuke.listener;

import com.github.AllenDuke.event.TimeOutEvent;

/**
 * @author 杜科
 * @description 超时监听器
 * @contact AllenDuke@163.com
 * @since 2020/3/4
 */
public interface TimeOutListener {

    void handle(TimeOutEvent event);
}
