package com.github.AllenDuke.dto;

/**
 * @author 杜科
 * @description 服务端发送给客户端的消息
 * @contact AllenDuke@163.com
 * @since 2020/2/27
 */
public class ServerMessage {

    private long callerId;//调用者线程id
    private long count;//当前调用者的第n次调用
    private boolean isSucceed=true;//调用成功标志
    private Object reselut;//调用结果，有可能为失败字符串提示

    public ServerMessage(){}

    public ServerMessage(long callerId, long count, boolean isSucceed, Object reselut) {
        this.callerId = callerId;
        this.count = count;
        this.isSucceed = isSucceed;
        this.reselut = reselut;
    }

    public boolean isSucceed() {
        return isSucceed;
    }

    public void setSucceed(boolean succeed) {
        isSucceed = succeed;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }

    public long getCallerId() {
        return callerId;
    }

    public void setCallerId(long callerId) {
        this.callerId = callerId;
    }

    public Object getReselut() {
        return reselut;
    }

    public void setReselut(Object reselut) {
        this.reselut = reselut;
    }

    @Override
    public String toString() {
        return "ServerMessage{" +
                "callerId=" + callerId +
                ", count=" + count +
                ", isSucceed=" + isSucceed +
                ", reselut=" + reselut +
                '}';
    }
}
