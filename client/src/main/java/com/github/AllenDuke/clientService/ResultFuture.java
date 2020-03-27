package com.github.AllenDuke.clientService;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

/**
 * @author 杜科
 * @description
 * @contact AllenDuke@163.com
 * @date 2020/3/27
 */
public class ResultFuture<V> implements Future<V> {

    private RPCClientHandler clientHandler;

    public ResultFuture(RPCClientHandler clientHandler){
        this.clientHandler=clientHandler;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return false;
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public boolean isDone() {
        return clientHandler.getResult(Thread.currentThread().getId())!=null;
    }

    /**
     * @description: 阻塞获取结果
     * @param
     * @return: V 正确结果，或者null(调用超时)
     * @author: 杜科
     * @date: 2020/3/27
     */
    @Override
    public V get() throws InterruptedException, ExecutionException {
        long callerId=Thread.currentThread().getId();
        Object result = clientHandler.getResult(callerId);
        if(result!=null&&!result.equals("调用超时")) return (V) result;//如果已经完成，那么就返回
        if (result!=null&&result.equals("调用超时")) return null;//调用超时
        LockSupport.park();//不会lost-wake-up
        result = clientHandler.getResult(callerId);
        if(!result.equals("调用超时")) return (V) result;//如果已经完成，那么就返回
        return null;//调用超时
    }

    @Override
    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        long begin=System.currentTimeMillis();
        timeout=unit.toMillis(timeout);
        long callerId=Thread.currentThread().getId();
        Object result;
        //自旋获取，最久要到最终调用超时
        while((result=clientHandler.getResult(callerId))==null||System.currentTimeMillis()-begin<timeout);
        if(!result.equals("调用超时")) return (V) result;//如果已经完成，那么就返回
        return null;//调用超时
    }
}
