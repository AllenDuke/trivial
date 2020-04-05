package com.github.AllenDuke.server;


import com.github.AllenDuke.producerService.RPCServer;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author 杜科
 * @description 服务端启动类，启动rpc服务提供者
 * @contact AllenDuke@163.com
 * @since 2020/2/11
 */
public class ServerBootstrap1 {

    public static void main(String[] args) throws Exception {

        RPCServer.startServer(new ThreadPoolExecutor(2,4,3000,
                TimeUnit.MILLISECONDS,new LinkedBlockingQueue<Runnable>(150)));
    }
}
