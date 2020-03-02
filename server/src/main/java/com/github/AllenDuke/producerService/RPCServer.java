package com.github.AllenDuke.producerService;


import com.github.AllenDuke.exception.ArgNotFoundExecption;
import com.github.AllenDuke.myThreadPoolService.ThreadPoolService;
import com.github.AllenDuke.util.YmlUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * @author 杜科
 * @description rpc服务提供者，要求源码中resource文件夹中有myrpc.yml
 * @contact AllenDuke@163.com
 * @since 2020/2/11
 */
@Slf4j
public class RPCServer {

    //服务实现类所在的包名
    public static String packageName;

    //主机地址
    private static String host;

    //端口
    private static int port;

    //boss数量
    private static int bossSize=1;

    //worker数量
    private static int workerSize= 0;//为0将使用默认值：cpu核数*2

    //业务线程池模型
    protected static int businessPoolModel=0;//0为不开启，1为使用jdk线程池，2为使用自实现线程池

    //jdk线程池
    protected static ThreadPoolExecutor executor;

    //自实现线程池
    protected static ThreadPoolService poolService;

    public static void startServer(ThreadPoolExecutor poolExecutor){
        executor=poolExecutor;
        startServer();
    }

    public static void startServer(ThreadPoolService threadPoolService){
        poolService=threadPoolService;
        startServer();
    }

    //启动netty线程组
    public static void startServer() {
        Map<String, Object> map = YmlUtil.getResMap("server");
        if (!map.containsKey("host")) throw new ArgNotFoundExecption("rpc.yml缺少参数host!");
        host = (String) map.get("host");
        if (!map.containsKey("port")) throw new ArgNotFoundExecption("rpc.yml缺少参数port!");
        port = (Integer) map.get("port");
        if (!map.containsKey("packageName")) throw new ArgNotFoundExecption("rpc.yml缺少参数packageName!");
        packageName = (String) map.get("packageName");
        if(map.containsKey("bossSize")) bossSize= (int) map.get("bossSize");
        if(map.containsKey("workerSize")) workerSize= (int) map.get("workerSize");
        if(map.containsKey("businessPoolModel")) businessPoolModel= (int) map.get("businessPoolModel");
        if(businessPoolModel==1&&executor==null) throw new ArgNotFoundExecption("缺少jdk线程池");
        if(businessPoolModel==2&&poolService==null) throw new ArgNotFoundExecption("缺少自实现线程池");
        new Thread(() -> {//转移阻塞点，使主线程得以返回
            startServer0();
        }).start();
    }

    //指定boss worker数量启动netty线程组
    private static void startServer0() {
        EventLoopGroup bossGroup = new NioEventLoopGroup(bossSize);
        EventLoopGroup workerGroup = new NioEventLoopGroup(workerSize);
        try {
            ServerBootstrap serverBootstrap = new ServerBootstrap();
            serverBootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(//作用于workerGroup
                            new ChannelInitializer<SocketChannel>() {//初始化器也算是一个handler,在pipeline中
                                //初始化socketChannel（每一个都包含一个pipeline）
                                      @Override
                                      protected void initChannel(SocketChannel ch) throws Exception {
                                          ChannelPipeline pipeline = ch.pipeline();
                                          ByteBuf delimiter = Unpooled.copiedBuffer("}".getBytes());//“}”为分隔符
                                          pipeline.addLast(new DelimiterBasedFrameDecoder(2048,
                                                  false, delimiter));
                                          pipeline.addLast(new StringDecoder());//inbound编码器
                                          pipeline.addLast(new StringEncoder());//outbound解码器
                                          pipeline.addLast(new RPCServerHandler());//业务处理器
                                      }
                                  }
                    );
            ChannelFuture channelFuture = serverBootstrap.bind(host, port).sync();
            log.info("server is ready! ");
            channelFuture.channel().closeFuture().sync();//同步方法，直到有结果才往下执行
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
