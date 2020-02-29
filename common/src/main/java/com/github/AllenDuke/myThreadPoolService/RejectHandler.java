package com.github.AllenDuke.myThreadPoolService;

/**
 * @author 杜科
 * @description 拒绝策略
 * @contact AllenDuke@163.com
 * @since 2020/2/10
 */
public interface RejectHandler {

    void reject(Runnable task);
}
