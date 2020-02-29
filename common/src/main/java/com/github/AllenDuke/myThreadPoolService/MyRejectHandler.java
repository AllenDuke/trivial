package com.github.AllenDuke.myThreadPoolService;

/**
 * @author 杜科
 * @description
 * @contact AllenDuke@163.com
 * @since 2020/2/10
 */
public class MyRejectHandler implements RejectHandler {
    @Override
    public void reject(Runnable task) {
        System.out.println("队列已满，已达最大线程数，无空闲线程，抛弃当前任务——"+task.toString());
    }
}
