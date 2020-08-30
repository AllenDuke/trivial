package com.github.AllenDuke.clientService;

import com.github.AllenDuke.exception.UnknownException;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author 杜科
 * @description 异步调用的结果
 * @contact AllenDuke@163.com
 * @date 2020/3/27
 */
public class ResultFuture<V> implements Future<V> {

    private long rpcId; /* 绑定的rpc */

    private RPCClientHandler clientHandler; /* 异步结果所处的channel */

    private long createTime; /* 生成ResultFuture时的时间戳 */

    public ResultFuture(long rpcId, RPCClientHandler clientHandler){
        this.rpcId=rpcId;
        this.clientHandler=clientHandler;
        createTime=System.currentTimeMillis();
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
        return System.currentTimeMillis()-createTime>5*60*1000
                || clientHandler.getResultSyn(Thread.currentThread().getId())!=null;
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
        if(System.currentTimeMillis()-createTime>5*60*1000) { /* 超过5分钟，已经被清理 */
            clientHandler.getResultMap().remove(rpcId); /* 这里resultMap是不存在key rpcId的，调用remove是为了清理过期键 */
            return null;
        }

        Object result = clientHandler.getResultAsy(rpcId);

        if(result!=null&&result.getClass() != TimeOutResult.class) return (V) result; /* 如果已经完成，那么就返回 */
        if (result!=null&&result.getClass() == TimeOutResult.class) return null; /* 调用超时 */

        /* 还没有结果，继续等待，不会lost-wake-up */
        synchronized (this){
            this.wait(); /* 这里释放了锁，等待netty io线程收到结果后调用resultFuture.notifyAll()后，返回，然后去获取结果。 */
        }

        result = clientHandler.getResultAsy(rpcId);

        /**
         * 理论上 这时的result不可能为空
         */
        if(result==null)
            throw new UnknownException("出现严重的未知错误，线程——"+Thread.currentThread().getId()+" 第 "+rpcId
                    +" 次调用，unpark后获取到空的结果，有可能该调用结果被意外删除！！！");

        if(result.getClass() != TimeOutResult.class) return (V) result; /* 如果已经完成，那么就返回 */

        return null; /* 调用超时 */
    }

    /**
     * @description: 这里由调用get的线程自旋等待结果，因为通常自旋时间不会很长，而且最终超时也会结束自旋
     * @param timeout 等待时长
     * @param unit 时间单位
     * @return: V
     * @author: 杜科
     * @date: 2020/3/28
     */
    @Override
    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        if(System.currentTimeMillis()-createTime>5*60*1000) { /* 超过5分钟，已经被清理 */
            clientHandler.getResultMap().remove(rpcId); /* 这里resultMap是不存在key rpcId的，调用remove是为了清理过期键 */
            return null;
        }
        long begin=System.currentTimeMillis();
        timeout=unit.toMillis(timeout);
        long callerId=Thread.currentThread().getId();
        Object result;
        /* 自旋获取，最久要到最终调用超时 */
        while((result=clientHandler.getResultAsy(callerId))==null&&System.currentTimeMillis()-begin<timeout);
        if(result!=null&&result.getClass() != TimeOutResult.class) return (V) result; /* 如果已经完成，那么就返回 */
        return null; /* 调用超时 */
    }
}
