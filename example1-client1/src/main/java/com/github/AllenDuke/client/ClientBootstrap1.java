package com.github.AllenDuke.client;


import com.github.AllenDuke.clientService.GenericService;
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
        GenericService genericService = RPCClient.getGenericService();
        Calculator calculator = (Calculator) RPCClient.getServiceImpl(Calculator.class);

        /**
         * 这里在当前线程中连续异步地发送了30个信息，那么对于前29个都将成为历史消息，这是合理的设计。
         * 这里异步发送信息，如果你不依赖此结果，那么前29个虽然成为了历史消息，但是调用进行的操作已经在服务端完成了。
         * 如果你依赖此结果，那么你应该针对每一次的调用进行获取结果，即调用future.get()
         */
        for (int j = 0; j < 10; j++) {
            new Thread(()->{
                long callerId=Thread.currentThread().getId();
                for (int i = 0; i < 100; i++) {
                    calculator.add((int) callerId,i);
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                System.out.println("线程——"+callerId+" finish");
            }).start();
        }

//        RPCClient.shutdown();
    }


}
