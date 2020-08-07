package com.github.AllenDuke.business;

/**
 * @author 杜科
 * @description 每次调用异常时，记录远程地址，当远程客户端一定时间内连续发生调用异常达一定次数时，那么认为收到了攻击，
 * 将拒绝本次调用，同时断开连接，且一定时间内不允许再次连接，防止恶意攻击。
 * @contact AllenDuke@163.com
 * @date 2020/4/4
 */
public class InvokeErrorNode {

    //时间戳
    private long lastTime;

    private int errCount;

    public InvokeErrorNode(long lastTime, int errCount) {
        this.lastTime = lastTime;
        this.errCount = errCount;
    }

    public long getLastTime() {
        return lastTime;
    }

    public void setLastTime(long lastTime) {
        this.lastTime = lastTime;
    }

    public int getErrCount() {
        return errCount;
    }

    public void setErrCount(int errCount) {
        this.errCount = errCount;
    }
}
