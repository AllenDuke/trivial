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
    public ResultFuture invoke(String className, String methodName, Object[] args){
        if(RPCClient.shutdown) throw new ShutDownException("当前RPCClient已经shutdown了");
        ClientMessage clientMessage = new ClientMessage(Thread.currentThread().getId(),
                className, methodName, args);
        return RPCClient.getConnector().invokeAsy(clientMessage);//异步调用
    }
}
