package com.github.AllenDuke.client;


import com.github.AllenDuke.clientService.RPCClient;
import com.github.AllenDuke.server.Calculator;

/**
 * @author 杜科
 * @description 客户端启动类，从RPCClient处获取代理对象，发起RPC
 * @contact AllenDuke@163.com
 * @since 2020/2/11
 */
public class ClientBootstrap2 {


    public static void main(String[] args) throws Exception {

        RPCClient.init();
        Calculator calculator = (Calculator) RPCClient.getServiceImpl(Calculator.class);
        calculator.multipy(5,9);
        //RPCClient.shutdown();

    }


}
