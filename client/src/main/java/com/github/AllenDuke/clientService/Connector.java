package com.github.AllenDuke.clientService;

import com.github.AllenDuke.dto.ClientMessage;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * @author 杜科
 * @description 连接器，负责通过netty连接RPCServer
 * @contact AllenDuke@163.com
 * @since 2020/3/10
 */
@Slf4j
public class Connector {

    //已与某个RPCServer连接的RPCClientHandler，key为服务名称，如Calculator，但是有可能出现下一次要调用HelloService的时候
    //从此map中找不到RPCClient，而事实上Calculaor所在的RPCServer也提供了HelloService的服务，但无法感知到，从而又再次与此
    //服务端再构建了一条连接，这个问题将在下一次更新中解决。
    //注意为什么不用ConcurrentHashMap，在createAndConnectChannel有解释
    private static final Map<String,RPCClientHandler> connectedHandlerMap=new HashMap<>();

    //RPCClientHandler空闲队列(空闲后与原连接断开，进入空闲队列) 并发容器防止并发构建连接
    private static final ConcurrentLinkedQueue<RPCClientHandler> idleHandlerQueue=new ConcurrentLinkedQueue();

    //记录NioEventLoopGroup，用于关闭
    private static final Set<NioEventLoopGroup> groupSet=new HashSet<>();

    //注册中心
    private static final Registry registry =RPCClient.registry;

    /**
     * @description: 新建一条NioSocketChannel，连上指定的服务端
     * 这里避免调用同一个服务的两个线程同时创建两条连接，所以直接使用sychronized，看起来不友好，
     * 但实际上新建的次数是不多的，因为可以重用空闲的连接。
     * 这里不把sychronized加到方法头上，是为了尽量减少要同步的区域，尽量让同步在轻量级锁（自旋）上完成，避免升级
     * @param serverAddr 服务端地址，如 127.0.0.1:7000
     * @param serviceName 该服务端提供的服务的名称，如 Calculator
     * @return: void
     * @author: 杜科
     * @date: 2020/3/10
     */
    private void createAndConnectChannel(String serverAddr,String serviceName){
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        RPCClientHandler clientHandler=new RPCClientHandler();
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(
                            new ChannelInitializer<SocketChannel>() {
                                @Override
                                protected void initChannel(SocketChannel ch) throws Exception {
                                    ChannelPipeline pipeline = ch.pipeline();
                                    ByteBuf delimiter = Unpooled.copiedBuffer("}".getBytes());//“}”为分隔符
                                    pipeline.addLast(new DelimiterBasedFrameDecoder(2048,
                                            false, delimiter));//不去除分割符
                                    pipeline.addLast(new StringEncoder());//outbound编码器
                                    pipeline.addLast(new StringDecoder());//inbound解码器
                                    pipeline.addLast(clientHandler);//业务处理器
                                }
                            }
                    );
            int splitIndex=serverAddr.indexOf(":");
            String serverHost=serverAddr.substring(0,splitIndex);
            int serverPort=Integer.valueOf(serverAddr.substring(splitIndex+1,serverAddr.length()));
            synchronized (this){//connector只有一个
                // synchronized保证可见性，在其内写入的数据对其他线程可见(写回主存，其他线程再从主存中读)，所以可用HashMap
                if(connectedHandlerMap.containsKey(serviceName)) return;//已由别的线程先创建
                bootstrap.connect(serverHost,serverPort).sync();
                connectedHandlerMap.put(serviceName,clientHandler);//已建立连接
                groupSet.add(group);
            }
        } catch (Exception e) {
            e.printStackTrace();
            group.shutdownGracefully();
        }
    }

    /**
     * @description: 将空闲的SocketChannel重新连上指定服务端
     * @param pipeline channel中的pipeline
     * @param serverAddr 服务端地址
     * @return: void
     * @author: 杜科
     * @date: 2020/3/10
     */
    public void connectChannel(ChannelPipeline pipeline,String serverAddr){
        int splitIndex=serverAddr.indexOf(":");
        String serverHost=serverAddr.substring(0,splitIndex);
        int serverPort=Integer.valueOf(serverAddr.substring(splitIndex+1,serverAddr.length()));
        pipeline.connect(new InetSocketAddress(serverHost,serverPort));
    }

    /**
     * @description: 根据服务名称尝试从已有的连接中拿到一个RPCClientHandler，
     * 如果没有重用空闲的连接，如果也没有空闲的连接则构建新的连接
     * @param serviceName 服务名称
     * @return: com.github.AllenDuke.clientService.RPCClientHandler
     * @author: 杜科
     * @date: 2020/3/10
     */
    private RPCClientHandler findClientHandler(String serviceName){
        RPCClientHandler clientHandler = connectedHandlerMap.get(serviceName);
        if(clientHandler!=null) return clientHandler;
        String serverAddr= registry.findServer(serviceName);
        clientHandler=idleHandlerQueue.poll();//防止并发构建连接
        if(clientHandler!=null) {
            connectChannel(clientHandler.getContext().pipeline(),serverAddr);
            connectedHandlerMap.put(serviceName,clientHandler);//已建立连接
            return clientHandler;
        }
        createAndConnectChannel(serverAddr,serviceName);
        clientHandler= connectedHandlerMap.get(serviceName);
        return clientHandler;
    }

    /**
     * @description: 根据服务名称找到一个RPCClientHandler将消息发送出去，这里会并发调用
     * 线程之间有可能调用同一个服务，而不同的服务有可能在同一个生产者中有提供
     * @param clientMessage 要发送的消息
     * @return: Object 调用结果
     * @author: 杜科
     * @date: 2020/3/10
     */
    public Object invoke(ClientMessage clientMessage) throws InterruptedException {
        RPCClientHandler clientHandler=findClientHandler(clientMessage.getClassName());
        clientHandler.sendMsg(clientMessage);//caller park
        return clientHandler.getResult(Thread.currentThread().getId());//unpark后获取结果
    }

    public static Map<String, RPCClientHandler> getConnectedHandlerMap() {
        return connectedHandlerMap;
    }

    public static ConcurrentLinkedQueue getIdleHandlerQueue() {
        return idleHandlerQueue;
    }

    //关闭所有netty线程
    public void shutDown(){
        for (NioEventLoopGroup group : groupSet) {
            group.shutdownGracefully();
        }
    }

}
