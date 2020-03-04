package com.github.AllenDuke.event;

import com.github.AllenDuke.clientService.RPCClient;
import com.github.AllenDuke.dto.ClientMessage;

/**
 * @author 杜科
 * @description 超时事件，记录每个caller发起第count次调用的时间
 * @contact AllenDuke@163.com
 * @since 2020/3/4
 */
public class TimeOutEvent {

    ClientMessage message;//应尽量缩减message的信息，避免不必要的传输
    long createTime;
    int retryNum;

    public TimeOutEvent(ClientMessage message, long createTime) {
        this.message=message;
        this.createTime = createTime;
        this.retryNum= RPCClient.retryNum;
    }

    public ClientMessage getMessage() {
        return message;
    }

    public void setMessage(ClientMessage message) {
        this.message = message;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public int getRetryNum() {
        return retryNum;
    }

    public void setRetryNum(int retryNum) {
        this.retryNum = retryNum;
    }

    @Override
    public int hashCode() {
        return (int) message.getCallerId()+(int) message.getCount();
    }

    @Override
    public boolean equals(Object obj) {
        TimeOutEvent t=(TimeOutEvent)obj;
        return this.message.getCallerId()==t.message.getCallerId()
                &&this.message.getCount()==t.message.getCount();
    }
}
