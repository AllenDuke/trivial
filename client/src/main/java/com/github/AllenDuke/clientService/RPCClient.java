package com.github.AllenDuke.clientService;


import com.github.AllenDuke.dto.ClientMessage;
import com.github.AllenDuke.event.TimeOutEvent;
import com.github.AllenDuke.exception.ArgNotFoundExecption;
import com.github.AllenDuke.listener.DefaultTimeOutListener;
import com.github.AllenDuke.listener.TimeOutListener;
import com.github.AllenDuke.util.YmlUtil;
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

import java.lang.reflect.Proxy;
import java.util.Map;

/**
 * @author 杜科
 * @description rpc消费者，要求源码中resource文件夹中有myrpc.yml
 * 由于netty线程收到数据后只需要进行简单的验证就可以去唤醒caller，因此不需要使用业务线程池
 * 而客户端的超时机制，另有详细说明
 * @contact AllenDuke@163.com
 * @since 2020/2/11
 */
@Slf4j
public class RPCClient {

    //初始化标志
    private static boolean isInit = false;

    //关闭标志，用于
    protected static boolean shutdown=false;

    //服务提供方主机地址
    private static String serverHost;

    //服务提供方端口号
    private static int serverPort;

    //是否允许超时，不允许将一直阻塞
    protected static long timeout = -1;

    //超时重试次数
    public static int retryNum=0;

    //netty线程数
    private static int workerSize= 0;//为0将使用默认值：cpu核数*2

    //netty线程组
    private static NioEventLoopGroup group;

    //业务处理器
    public static RPCClientHandler clientHandler;

    //超时监听者
    protected static TimeOutListener listener;

    /**
     * @description: 注册超时监听器，当发生超时时，将调用监听器里的相关方法
     * @param timeOutListener 超时监听器
     * @return: void
     * @author: 杜科
     * @date: 2020/3/4
     */
    public synchronized static void init(TimeOutListener timeOutListener) {
        if(isInit) return;
        listener= timeOutListener;
        init();
    }

    /**
     * @param
     * @description: 用户在使用前要先初始化，否则将抛异常。
     * 初始化时，将解析rpc.yml，设置相应的参数，至少包含serverHost,serverPort
     * synchronized防止并发初始化，这里不用volatile和dubble-check是因为用户应该尽量保持只有一个线程在初始化，
     * 这样为了不增加编码复杂度，使用synchronized的花费也不高，较为直观。
     * @return: void
     * @author: 杜科
     * @date: 2020/2/28
     */
    public synchronized static void init() {
        if (isInit) return;
        isInit = true;
        Map<String, Object> map = YmlUtil.getResMap("client");
        if (!map.containsKey("serverHost")) throw new ArgNotFoundExecption("rpc.yml缺少参数serverHost!");
        serverHost = (String) map.get("serverHost");
        if (!map.containsKey("serverPort")) throw new ArgNotFoundExecption("rpc.yml缺少参数serverPort!");
        serverPort = (Integer) map.get("serverPort");
        if (map.containsKey("timeout")) timeout = new Long((int) map.get("timeout"));
        if(map.containsKey("retryNum")) retryNum=(int) map.get("retryNum");
        if(map.containsKey("workerSize")) workerSize= (int) map.get("workerSize");
        clientHandler = new RPCClientHandler();
        if(timeout!=-1&&listener==null) listener=new DefaultTimeOutListener();//设置默认监听器(注意初始化顺序)
        group = new NioEventLoopGroup(workerSize);
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
            bootstrap.connect(serverHost, serverPort).sync();
        } catch (Exception e) {
            e.printStackTrace();
            group.shutdownGracefully();
        }
    }

    /**
     * @param serivceClass 服务要实现的接口
     * @description: 返回一个代理对象（jdk动态代理）
     * 返回前，检查是否初始化
     * 其中invokeHandler的invoke方法内，为以下逻辑（以HelloService接口，sayHello()方法为例）
     * 线程调用 HelloService service=RPCClient.getServiceImpl(HelloService.class);
     * service.sayHello(),此时进入invokeHandler的invoke方法，将向服务方寻求HelloServiceImpl类的sayHello()方法的结果
     * 即生成一条信息交由netty线程发送，阻塞或超时地等待结果
     * 其中结果有可能是错误信息或者超时提示字符串，用户应注意抛出 ClassCastException
     * @return: java.lang.Object
     * @author: 杜科
     * @date: 2020/2/12
     */
    public static Object getServiceImpl(final Class<?> serivceClass) {
        if (!isInit) throw new RuntimeException("还没有init");
        return Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{serivceClass}, (proxy, method, args) -> {
                    //lamda表达式，匿名内部类实现InvokeInhandler接口，重写invoke方法

                    String className = serivceClass.getName();
                    className = className.substring(className.lastIndexOf(".") + 1) + "Impl";//去掉包名
                    ClientMessage clientMessage = new ClientMessage(Thread.currentThread().getId(),
                            className, method.getName(), args);
                    clientHandler.sendMsg(clientMessage);//caller park
                    Object result = clientHandler.getResult(Thread.currentThread().getId());//unpark后获取结果
//                    if(result.getClass()==method.getReturnType())
                    return result;
                });
    }

    public static void shutdown(){
        group.shutdownGracefully();
        shutdown=true;//按netty线程组的关闭策略先让其完成相关工作，再去检查超时观察者
        if(timeout!=-1){//结束超时观察者
            clientHandler.getWaiterQueue()//传入当前时间是为了在最后一次检查中不误报超时信息
                    .add(new TimeOutEvent(new ClientMessage(),System.currentTimeMillis()));
        }
    }
}
