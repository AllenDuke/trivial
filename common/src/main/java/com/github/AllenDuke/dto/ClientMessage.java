package com.github.AllenDuke.dto;


import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author 杜科
 * @description 客户端发送给服务端的消息
 * @contact AllenDuke@163.com
 * @since 2020/2/11
 */
public class ClientMessage {

    //记录构造次数，原子变量防止并发构造
    private static AtomicLong sum=new AtomicLong(0);

    private Long callerId;//调用者线程id
    private String className;//要调用的类名
    private String methodName;//要调用的方法名
    private Object[] args;//方法的参数
    private Long count;//当前调用者的第count次调用

    //要有无参构造共fastjson反序列化调用，
    // 否则服务方将调用下面有参构造使得count++（不会再调用setter，使得count一致，消息过期）
    public ClientMessage(){}

    public ClientMessage(long callerId, String className, String methodName, Object[] args) {
        this.callerId = callerId;
        this.className = className;
        this.methodName = methodName;
        this.args = args;
        this.count=sum.getAndIncrement();
    }

    public long getCallerId() {
        return callerId;
    }

    public void setCallerId(long callerId) {
        this.callerId = callerId;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public Object[] getArgs() {
        return args;
    }

    public void setArgs(Object[] args) {
        this.args = args;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }

    @Override
    public String toString() {
        return "ClientMessage{" +
                "callerId=" + callerId +
                ", className='" + className + '\'' +
                ", methodName='" + methodName + '\'' +
                ", args=" + Arrays.toString(args) +
                ", count=" + count +
                '}';
    }
}
