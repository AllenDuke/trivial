package com.github.AllenDuke.clientService;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

/**
 * @author 杜科
 * @description 异步调用的结果
 * @contact AllenDuke@163.com
 * @date 2020/3/27
 */
public class ResultFuture<V> implements Future<V> {

    //异步结果所处的channel
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
     * @description: 阻塞获取结果，不会lost-wake-up（LockSupport.unPark可先许可）
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

    /**
     * @description: 这里自旋等待结果，因为通常自旋时间不会很长，而且最终超时也会结束自旋
     * @param timeout 等待时长
     * @param unit 时间单位
     * @return: V
     * @author: 杜科
     * @date: 2020/3/28
     */
    @Override
    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        long begin=System.currentTimeMillis();
        timeout=unit.toMillis(timeout);
        long callerId=Thread.currentThread().getId();
        Object result;
        //自旋获取，最久要到最终调用超时
        while((result=clientHandler.getResult(callerId))==null&&System.currentTimeMillis()-begin<timeout);
        if(result!=null&&!result.equals("调用超时")) return (V) result;//如果已经完成，那么就返回
        return null;//调用超时
    }
}
