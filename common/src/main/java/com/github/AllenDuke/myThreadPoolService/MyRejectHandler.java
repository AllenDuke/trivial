package com.github.AllenDuke.myThreadPoolService;

import lombok.extern.slf4j.Slf4j;

/**
 * @author 杜科
 * @description 线程池拒绝策略，抛弃任务，不抛异常
 * @contact AllenDuke@163.com
 * @since 2020/2/10
 */
@Slf4j
public class MyRejectHandler implements RejectHandler {
    @Override
    public void reject(Runnable task) {
        log.error("队列已满，已达最大线程数，无空闲线程，抛弃当前任务——"+task.toString());
    }
}
