package com.github.AllenDuke.dto;

/**
 * @author 杜科
 * @description 服务端发送给客户端的消息
 * @contact AllenDuke@163.com
 * @since 2020/2/27
 */
public class ServerMessage {

    //调用者线程id
    private Short callerId;

    //第n次调用
    private Long count;

    //调用成功标志
    private Boolean isSucceed=true;

    //调用结果，有可能为失败字符串提示
    private Object result;

    public ServerMessage(){}

    public ServerMessage(short callerId, long count, boolean isSucceed, Object reselut) {
        this.callerId = callerId;
        this.count = count;
        this.isSucceed = isSucceed;
        this.result = reselut;
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

    public short getCallerId() {
        return callerId;
    }

    public void setCallerId(short callerId) {
        this.callerId = callerId;
    }

    public Object getReselut() {
        return result;
    }

    public void setReselut(Object result) {
        this.result = result;
    }

    @Override
    public String toString() {
        return "ServerMessage{" +
                "callerId=" + callerId +
                ", count=" + count +
                ", isSucceed=" + isSucceed +
                ", reselut=" + result +
                '}';
    }
}
