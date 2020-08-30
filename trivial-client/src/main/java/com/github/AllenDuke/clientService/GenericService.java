package com.github.AllenDuke.clientService;

import com.github.AllenDuke.dto.ClientMessage;
import com.github.AllenDuke.exception.ShutDownException;

/**
 * @author 杜科
 * @description 通用服务，用于异步调用
 * @contact AllenDuke@163.com
 * @date 2020/3/27
 */
public class GenericService {

    /**
     * @description: 这里通过设定一些参数来确定要发送的信息，发起异步调用，立即得到一个异步结果
     * @param className 要调用的类名
     * @param methodName 要调用的方法名
     * @param args 参数列表
     * @return: com.github.AllenDuke.clientService.ResultFuture
     * @author: 杜科
     * @date: 2020/3/28
     */
    public ResultFuture invokeAsy(String className, String methodName, Object[] args){
        if(RPCClient.shutdown) throw new ShutDownException("当前RPCClient已经shutdown了");
        StringBuilder argTypes=new StringBuilder();
        for (Object arg : args) {
            argTypes.append(arg.getClass().getName()+ " ");
        }
        ClientMessage clientMessage = new ClientMessage(className, methodName, args,argTypes.toString());
        return RPCClient.getConnector().invokeAsy(clientMessage); /* 异步调用 */
    }
}
