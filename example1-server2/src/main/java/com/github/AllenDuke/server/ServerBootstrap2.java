package com.github.AllenDuke.server;


import com.github.AllenDuke.annotation.TrivialScan;
import com.github.AllenDuke.producerService.RPCServer;

/**
 * @author 杜科
 * @description 服务端启动类，启动rpc服务提供者
 * @contact AllenDuke@163.com
 * @since 2020/2/11
 */
@TrivialScan(path = "com.github.AllenDuke.serviceImpl")
public class ServerBootstrap2 {

    public static void main(String[] args) throws Exception {

//        RPCServer.startServer(new ThreadPoolService(1,2,3000,10));
        RPCServer.startServer(ServerBootstrap2.class);
    }
}
