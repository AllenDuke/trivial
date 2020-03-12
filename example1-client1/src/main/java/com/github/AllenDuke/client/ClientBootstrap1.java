package com.github.AllenDuke.client;


import com.github.AllenDuke.clientService.RPCClient;
import com.github.AllenDuke.service.Calculator;

/**
 * @author 杜科
 * @description 客户端启动类，从RPCClient处获取代理对象，发起RPC
 * @contact AllenDuke@163.com
 * @since 2020/2/11
 */
public class ClientBootstrap1 {

    public static void main(String[] args) throws Exception {

        RPCClient.init();
        Calculator calculator = (Calculator) RPCClient.getServiceImpl(Calculator.class);
        new Thread(()->{
            calculator.add(1, "2");
        }).start();
        new Thread(()->{
            try {
                calculator.multipy(2, 7);
            }catch (Exception e){
                System.out.println("调用超时");
            }
            try {
                Thread.sleep(11000);
            } catch (InterruptedException e) {

            }
            System.out.println(calculator.add(3, "4"));

        }).start();
        //RPCClient.shutdown();
    }


}
