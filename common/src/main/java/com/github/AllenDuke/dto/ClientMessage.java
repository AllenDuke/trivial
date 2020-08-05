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

    //记录构造次数，原子变量防止并发构造，作为id用来唯一标记一次rpc
    private static AtomicLong sum=new AtomicLong(0);

    //调用者线程id
    private Long callerId;

    //要调用的类名
    private String className;

    //要调用的方法名
    private String methodName;

    //方法的参数
    private Object[] args;

    //参数原始类型，因为arg将会被json格式化
    private String argTypes;

    //第count次调用
    private Long count;

    public ClientMessage(){}

    public ClientMessage(long callerId, String className, String methodName, Object[] args,String argTypes) {
        this.callerId = callerId;
        this.className = className;
        this.methodName = methodName;
        this.args = args;
        this.argTypes=argTypes;
        this.count=sum.getAndIncrement();//sum++
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

    public String getArgTypes() {
        return argTypes;
    }

    public void setArgTypes(String argTypes) {
        this.argTypes = argTypes;
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
