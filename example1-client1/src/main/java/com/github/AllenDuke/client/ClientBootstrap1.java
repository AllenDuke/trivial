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
        //todo 当创建大量线程（如30个线程）同时发送消息时，发送完信息就主动强制断开了连接（服务端无法感知）？
        for (int i = 0; i < 5; i++) {
            new Thread(() -> {
                ResultFuture<Integer> future = genericService.invokeAsy("Calculator", "add",
                        new Object[]{1, "2", 3});
            }).start();
        }

        Thread.sleep(11000);
        ResultFuture<Integer> future = genericService.invokeAsy("Calculator", "add",
                new Object[]{1, "2", 3});

        //TODO 两线程并发时依然有错误，如消息丢失、漏发，有可能是别的原因，不是同步出现问题，因为在解决另一bug后
        //TODO 此种问题即似乎漏掉了略过2*7，暂未再现，难道是重排序？
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

        });
//        RPCClient.shutdown();
    }


}
