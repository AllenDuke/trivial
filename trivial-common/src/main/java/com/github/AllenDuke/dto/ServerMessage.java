package com.github.AllenDuke.dto;

/**
 * @author 杜科
 * @description 服务端发送给客户端的消息
 * @contact AllenDuke@163.com
 * @since 2020/2/27
 */
public class ServerMessage {

    //第n次调用
    private long rpcId;

    //调用成功标志
    private boolean isSucceed=true;

    //调用结果，有可能为失败字符串提示
    private Object result;

    public ServerMessage(){}

    public ServerMessage(long rpcId, boolean isSucceed, Object reselut) {
        this.rpcId=rpcId;
        this.isSucceed = isSucceed;
        this.result = reselut;
    }

    public long getRpcId() {
        return rpcId;
    }

    public void setRpcId(long rpcId) {
        this.rpcId = rpcId;
    }

    public boolean isSucceed() {
        return isSucceed;
    }

    public void setSucceed(boolean succeed) {
        isSucceed = succeed;
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
                "rpcId=" + rpcId +
                ", isSucceed=" + isSucceed +
                ", result=" + result +
                '}';
    }
}
