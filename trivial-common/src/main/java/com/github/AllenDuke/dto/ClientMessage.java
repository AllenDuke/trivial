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
    private static AtomicLong sum = new AtomicLong(0);

    //第count次调用
    private long rpcId;

    //要调用的类名
    private String className;

    //要调用的方法名
    private String methodName;

    /**
     * 参数原始类型，因为arg将会被json格式化成为JSONObject
     * 这里用参数的全限定名（如：java.lang.String），以空格为分隔符
     */
//    private String argTypes;

    //方法的参数，每一个参数可能有复杂的形式
    private Object[] args;

    public ClientMessage() {
    }

    public ClientMessage(String className, String methodName, Object[] args, String argTypes) {
        this.rpcId = sum.getAndIncrement();//sum++
        this.className = className;
        this.methodName = methodName;
        this.args = args;
//        this.argTypes = argTypes;
    }

    public long getRpcId() {
        return rpcId;
    }

    public void setRpcId(long rpcId) {
        this.rpcId = rpcId;
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

//    public String getArgTypes() {
//        return argTypes;
//    }
//
//    public void setArgTypes(String argTypes) {
//        this.argTypes = argTypes;
//    }

    @Override
    public String toString() {
        return "ClientMessage{" +
                "rpcId=" + rpcId +
                ", className='" + className + '\'' +
                ", methodName='" + methodName + '\'' +
                ", args=" + Arrays.toString(args) +
//                ", argTypes='" + argTypes + '\'' +
                '}';
    }
}
