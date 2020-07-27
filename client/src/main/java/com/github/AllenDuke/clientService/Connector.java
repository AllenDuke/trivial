package com.github.AllenDuke.clientService;

import com.github.AllenDuke.dto.ClientMessage;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * @author 杜科
 * @description 连接器，负责通过netty连接RPCServer进行通信
 * @contact AllenDuke@163.com
 * @since 2020/3/10
 */
@Slf4j
public class Connector {

    //两个共享的HashMap不加volatile，暂未发现问题

    /**
     * 已与某个RPCServer连接的RPCClientHandler，key为服务名称，如Calculator，但是有可能出现下一次要调用HelloService的时候
     * 从此map中找不到RPCClientHandler,而事实上Calculaor所在的RPCServer也提供了HelloService的服务,但无法感知到,从而又再次与此
     * 服务端再构建了一条连接，TODO 这个问题将在下一次更新中解决（在通信的时候服务端返回它能提供的所有服务），或者不解决。
     * 注意为什么不用ConcurrentHashMap，在createAndConnectChannel有解释
     */
    private static final Map<String, RPCClientHandler> connectedServiceHandlerMap = new HashMap<>();

    /**
     * 这是对connectedServiceHandlerMap的补充，key为远程主机地址，防止因不同的服务而对同一个远程主机建立多个连接。
     * 但仍然是没能去发现远程主机能提供的所有服务的，即当注册中心返回一样的主机地址时照样会新建连接的,尽管已建立的连接中有提供
     * 当从调用HelloService.sayHello时，从connectedServiceHandlerMap中找不到，
     * 然后从Registry中find，当返回的远程主机地址为Calculator服务的地址一样时，那么可以从connectedChannelHandlerMap中找到
     */
    private static final Map<String,RPCClientHandler> connectedChannelHandlerMap=new HashMap<>();

    //RPCClientHandler空闲队列(空闲后与原连接断开，进入空闲队列) 并发容器防止并发构建连接
    private static final ConcurrentLinkedQueue<ChannelPipeline> idlePipelineQueue = new ConcurrentLinkedQueue();

    //注册中心
    private static final Registry registry = RPCClient.registry;

    //记录某种服务调用超时的主机名单，用于下次调用时过滤
    private static final Map<String, Set<String>> timeOutMap = new HashMap<>();

    //复用bootstrap，之前一个连接对应一个bootstrap和 一个nioeventloopgroup和一个nioeventloop，
    //现在复用了线程，发挥了nio的优势，但并没有复用空闲的tcp的连接
    private static Bootstrap bootstrap;
    private static NioEventLoopGroup group;
    {
        try {
            group = new NioEventLoopGroup();
            bootstrap = new Bootstrap();
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
                                    pipeline.addLast(new RPCClientHandler());//业务处理器
                                }
                            }
                    );
        } catch (Exception e) {
            e.printStackTrace();
            group.shutdownGracefully();
        }
    }

    /**
     * @param serverAddr  服务端地址，如 127.0.0.1:7000
     * @param serviceName 该服务端提供的服务的名称，如 Calculator
     * @description: 新建一条NioSocketChannel，连上指定的服务端，
     * 在连接前先检查connectedServiceHandlerMap中是否已经有了该服务的连接，
     * 如果有，直接返回，如果没有再检查connectedChannelHandlerMap中是否已经有了该远程主机的连接，
     * 如果有，把clientHandler加到connectedServiceHandlerMap后直接返回，如果也没有则与进行连接，然后更新两个map
     * 这里避免调用同一个服务的两个线程同时创建两条连接，所以直接使用sychronized，看起来不友好，
     * 但实际上新建的次数是不多的，因为可以从缓存中拿取或是重用空闲的连接。
     * @return: void
     * @author: 杜科
     * @date: 2020/3/10
     */
    private void createAndConnectChannel(String serverAddr, String serviceName) {
        try {
            int splitIndex = serverAddr.indexOf(":");
            String serverHost = serverAddr.substring(0, splitIndex);//去掉'/'
            int serverPort = Integer.valueOf(serverAddr.substring(splitIndex + 1, serverAddr.length()));
            /**
             * 细节优化
             * 这里不把sychronized加到方法头上，是为了尽量减少要同步的区域，尽量让同步在轻量级锁（自旋）上完成，避免升级
             *
             * synchronized保证可见性，进入前清空缓存，再从主存中读，在其内写入的数据对其他线程可见(写回主存，
             * 其他线程再从主存中读)，所以可用HashMap
             */
            synchronized (this) {//connector只有一个
                if (connectedServiceHandlerMap.containsKey(serviceName)) {
                    log.info(serviceName + ",该服务的连接已由别的线程先创建，这里直接返回");
                    return;
                }
                if(connectedChannelHandlerMap.containsKey(serverAddr)){//对外表现为新建连接，其实复用了别的服务创建的连接
                    log.info(serverAddr+",该远程主机的连接已由别的线程先创建，" +
                            "这里把clientHandler加到connectedServiceHandlerMap后直接返回");
                    connectedServiceHandlerMap.put(serviceName,connectedChannelHandlerMap.get(serverAddr));
                    return;
                }
                ChannelHandler handler =
                        bootstrap.connect(serverHost, serverPort).sync().channel().pipeline().lastContext().handler();
                connectedServiceHandlerMap.put(serviceName, (RPCClientHandler) handler);//已建立该服务连接
                connectedChannelHandlerMap.put(serverAddr, (RPCClientHandler) handler);//已建立该channel连接
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * @param pipeline   channel中的pipeline
     * @param serverAddr 服务端地址
     * @description: 将空闲的SocketChannel重新连上指定服务端，暂时还搞不清楚断连后的channel处于什么样的一个状态
     * 还不知道如何重用
     * @return: void
     * @author: 杜科
     * @date: 2020/3/10
     */
    public void connectChannel(ChannelPipeline pipeline, String serverAddr) {
        int splitIndex = serverAddr.indexOf(":");
        String serverHost = serverAddr.substring(0, splitIndex);
        int serverPort = Integer.valueOf(serverAddr.substring(splitIndex + 1, serverAddr.length()));
        //断连后的pipeline会移除添加进去的HandlerContext
        ByteBuf delimiter = Unpooled.copiedBuffer("}".getBytes());//“}”为分隔符
        pipeline.addLast(new DelimiterBasedFrameDecoder(2048,
                false, delimiter));//不去除分割符
        pipeline.addLast(new StringEncoder());//outbound编码器
        pipeline.addLast(new StringDecoder());//inbound解码器
        pipeline.addLast(new RPCClientHandler());//业务处理器
        InetSocketAddress address = new InetSocketAddress(serverHost, serverPort);
        log.info("即将利用旧的连接：" + pipeline.channel() + "，新的服务地址：" + address);
        pipeline.connect(address).addListener((future) -> {
            System.out.println(future.get());
        });
    }

    /**
     * @param serviceName 服务名称
     * @description: 根据服务名称尝试从已有的连接中拿到一个可用的RPCClientHandler，
     * 如果没有则重用空闲的连接，如果也没有空闲的连接则构建新的连接，
     * 若构建连接的时候发现服务已经降级，那么直接返回null，不发起调用。
     * @return: com.github.AllenDuke.clientService.RPCClientHandler
     * @author: 杜科
     * @date: 2020/3/10
     */
    private RPCClientHandler findClientHandler(String serviceName) {
        RPCClientHandler clientHandler = connectedServiceHandlerMap.get(serviceName);
        if (clientHandler != null && clientHandler.getContext() != null) {//如果不是马上要断开的连接
            //检查该生产者是否进入了该服务的黑名单，以免当前clientHandler在间隙之中进入了黑名单
            Set<String> blackList = timeOutMap.get(serviceName);
            if (blackList == null || blackList.size() == 0) return clientHandler;
            String remoteAddress = clientHandler.getContext().channel().remoteAddress().toString();
            remoteAddress = remoteAddress.substring(1);//去掉'/'
            if (!blackList.contains(remoteAddress)) return clientHandler;
            log.error("生产者：" + remoteAddress + "，已进入服务：" + serviceName + " 的黑名单");
        }
        String serverAddr = registry.findServer(serviceName, timeOutMap.get(serviceName));
        if(serverAddr==null){
            log.info("本次挑选的主机的服务："+serviceName+"，已经降级！尝试直连...");
            serverAddr=RPCClient.serverHost+":"+RPCClient.serverPort;
        }
        //ChannelPipeline pipeline = idlePipelineQueue.poll();//防止并发构建连接
        ChannelPipeline pipeline = null;//暂不知道如何重用
        if (pipeline != null) {
            connectChannel(pipeline, serverAddr);
            while (!pipeline.channel().isActive()) ;//这里直接自旋等待，因为马上就连上了
            log.info("chanel：" + pipeline.channel() + "，成功连接上：" + pipeline.channel().remoteAddress());
            clientHandler = (RPCClientHandler) pipeline.lastContext().handler();
            connectedServiceHandlerMap.put(serviceName, clientHandler);//已建立连接
            return clientHandler;
        }
        createAndConnectChannel(serverAddr, serviceName);
        clientHandler = connectedServiceHandlerMap.get(serviceName);
        return clientHandler;
    }

    /**
     * @param clientMessage 要发送的消息
     * @description: 根据服务名称找到一个RPCClientHandler将消息发送出去，这里会并发调用
     * 线程之间有可能调用同一个服务，而不同的服务有可能在同一个生产者中有提供
     * @return: Object 调用结果
     * @author: 杜科
     * @date: 2020/3/10
     */
    public Object invoke(ClientMessage clientMessage) throws InterruptedException {
        RPCClientHandler clientHandler = findClientHandler(clientMessage.getClassName());
        if(clientHandler==null){
            log.error("本次挑选的主机的服务："+clientMessage.getClassName()+"，已经降级，将不发起调用，直接返回null！");
            return null;
        }
        clientHandler.sendMsg(clientMessage);//caller park
        return clientHandler.getResult(Thread.currentThread().getId());//unpark后获取结果
    }

    /**
     * @description: 异步调用，这里直接返回一个ResultFuture
     * @param clientMessage 要发送的信息
     * @return: com.github.AllenDuke.clientService.ResultFuture 异步结果
     * @author: 杜科
     * @date: 2020/3/28
     */
    public ResultFuture invokeAsy(ClientMessage clientMessage){
        RPCClientHandler clientHandler = findClientHandler(clientMessage.getClassName());
        if(clientHandler==null){
            log.error("本次挑选的主机的服务："+clientMessage.getClassName()+"，已经降级，将不发起调用，直接返回null！");
            return null;
        }
        clientHandler.sendMsgAsy(clientMessage);
        return new ResultFuture(clientHandler);
    }

    public static Map<String, RPCClientHandler> getConnectedServiceHandlerMap() {
        return connectedServiceHandlerMap;
    }

    public static Map<String, RPCClientHandler> getConnectedChannelHandlerMap() {
        return connectedChannelHandlerMap;
    }

    public static ConcurrentLinkedQueue<ChannelPipeline> getIdlePipelineQueue() {
        return idlePipelineQueue;
    }

    public static Map<String, Set<String>> getTimeOutMap() {
        return timeOutMap;
    }

    //关闭所有netty线程
    public void shutDown() {
        group.shutdownGracefully();
    }

}
