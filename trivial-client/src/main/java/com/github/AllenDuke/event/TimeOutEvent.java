package com.github.AllenDuke.event;

import com.github.AllenDuke.clientService.RPCClient;
import com.github.AllenDuke.clientService.RPCClientHandler;
import com.github.AllenDuke.dto.ClientMessage;

/**
 * @author 杜科
 * @description 超时事件，记录每个caller发起第count次调用的时间
 * @contact AllenDuke@163.com
 * @since 2020/3/4
 */
public class TimeOutEvent {

    ClientMessage message;
    long createTime;
    int retryNum;
    RPCClientHandler clientHandler;//所处的连接

    public TimeOutEvent(ClientMessage message, long createTime, RPCClientHandler clientHandler) {
        this.message=message;
        this.createTime = createTime;
        this.clientHandler=clientHandler;
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

    public RPCClientHandler getClientHandler() {
        return clientHandler;
    }

    @Override
    public int hashCode() {
        return (int) message.getRpcId();
    }

    @Override
    public boolean equals(Object obj) {
        TimeOutEvent t=(TimeOutEvent)obj;
        return this.message.getRpcId()==t.message.getRpcId();
    }
}
