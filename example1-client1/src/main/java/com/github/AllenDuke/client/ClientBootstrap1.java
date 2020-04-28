package com.github.AllenDuke.client;


import com.github.AllenDuke.clientService.GenericService;
import com.github.AllenDuke.clientService.RPCClient;
import com.github.AllenDuke.clientService.ResultFuture;
import com.github.AllenDuke.service.Calculator;

import java.util.concurrent.TimeUnit;

/**
 * @author 杜科
 * @description 客户端启动类，从RPCClient处获取代理对象，发起RPC
 * @contact AllenDuke@163.com
 * @since 2020/2/11
 */
public class ClientBootstrap1 {

    public static void main(String[] args) throws Exception {

        RPCClient.init();
        GenericService genericService = RPCClient.getGenericService();
        Calculator calculator = (Calculator) RPCClient.getServiceImpl(Calculator.class);
        new Thread(() -> {
            for (int i = 0; i < 30; i++) {
                ResultFuture<Integer> future = genericService.invokeAsy("Calculator", "add",
                        new Object[]{1, "2", 3});
                Thread.yield();
            }
        }).start();
        Thread.sleep(11000);
        ResultFuture<Integer> future = genericService.invokeAsy("Calculator", "add",
                new Object[]{2, "3", 4});

        new Thread(() -> {
            try {
                calculator.multipy(2, 7);
            } catch (Exception e) {
                System.out.println("调用超时");
            }
            try {
                Thread.sleep(11000);
            } catch (InterruptedException e) {

            }

            ResultFuture<Integer> future1 = genericService.invokeAsy("Calculator", "add",
                    new Object[]{3, "4"});
            try {
                System.out.println(future.get(10, TimeUnit.MILLISECONDS) == null);
            } catch (Exception e) {
                e.printStackTrace();
            }

        }).start();
//        RPCClient.shutdown();
    }


}
