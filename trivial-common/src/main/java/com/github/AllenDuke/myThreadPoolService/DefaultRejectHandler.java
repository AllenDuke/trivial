package com.github.AllenDuke.myThreadPoolService;

import com.github.AllenDuke.exception.RejectedExecutionException;

/**
 * @author 杜科
 * @description 默认的拒绝策略，异常在调用者线程抛出
 * @contact AllenDuke@163.com
 * @date 2020/3/13
 */
public class DefaultRejectHandler implements RejectHandler {
    @Override
    public void reject(Runnable task) {
        throw new RejectedExecutionException("线程池负荷已满！");
    }
}
