package com.github.AllenDuke.producerService;


import com.github.AllenDuke.annotation.TrivialScan;
import com.github.AllenDuke.annotation.TrivialService;
import com.github.AllenDuke.constant.LOG;
import com.github.AllenDuke.dto.ClientMessage;
import com.github.AllenDuke.exception.ArgNotFoundExecption;
import com.github.AllenDuke.exception.RegistrationFailException;
import com.github.AllenDuke.myThreadPoolService.ThreadPoolService;
import com.github.AllenDuke.util.TrivialClassLoader;
import com.github.AllenDuke.util.TrivialDecoder;
import com.github.AllenDuke.util.TrivialEncoder;
import com.github.AllenDuke.util.YmlUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author 杜科
 * @description rpc服务提供者，要求源码中resource文件夹中有rpc.yml
 * @contact AllenDuke@163.com
 * @since 2020/2/11
 */
@Slf4j
public class RPCServer {

    /* 服务实现类所在的包名，将来接收到消费者的请求时，只会扫描该包下的类 todo 优化 */
    public static String packageName;

    /* 主机地址 */
    private static String host;

    /* 端口 */
    private static int port;

    /* netty boss数量 */
    private static int bossSize = 1;

    /* netty worker数量 */
    private static int workerSize = 0;//为0将使用默认值：cpu核数*2

    /* 业务线程池模型 */
    protected static int businessPoolModel = 0;//0为不开启，1为使用jdk线程池，2为使用自实现线程池

    /* jdk线程池 */
    protected static ThreadPoolExecutor executor;

    /* 自实现线程池 */
    protected static ThreadPoolService poolService;

    /* zookeeper地址 */
    private static String zkHost;

    /* zookeeper端口 */
    private static int zkPort;

    /* zookeeper操作客户端 */
    private static ZooKeeper zooKeeper;

    /* zookeeper连接超时时限 */
    private static int sessionTimeOut = 1000;

    /* 往zookeeper中注册的服务，key为服务名，value为Map for{version: 1.0, open: true} */
    private static Map<String, Map<String, Object>> services;

    /* 默认读写空闲达10s后，服务提供方断开连接 */
    protected static int allIdleTime = 60 * 1000; /* 单位毫秒 */

    /* 是否启用Spring，取决于用户要暴露的服务是否存在于Spring环境中 */
    public static int enableSpring;

    /* 默认日志的等级为debug */
    public static int LOG_LEVEL= LOG.LOG_DEBUG; /* todo 添加volatile修饰 */

    /* 扫描项目中的类 仅作扫描使用，不进行加载 */
    public static TrivialClassLoader classLoader;

    /* 配置jdk线程池，启动服务端 */
    public static void startServer(Class mainClass, ThreadPoolExecutor poolExecutor) throws Exception {
        executor = poolExecutor;
        startServer(mainClass);
    }

    /* 配置自实现线程池，启动服务端 */
    public static void startServer(Class mainClass, ThreadPoolService threadPoolService) throws Exception {
        poolService = threadPoolService;
        startServer(mainClass);
    }

    /* 主要是处理主类上的@TrivialScan注解 */
    private static void handleMainClass(Class mainClass) throws ClassNotFoundException {
        TrivialScan trivialScan = (TrivialScan) mainClass.getAnnotation(TrivialScan.class);
        if (trivialScan == null) return;

        /* @TrivialScan中path将作为暴露服务的包名，将用于拼接客户端发送的简单类名成全限定名 */
        packageName = trivialScan.path();
        services = new HashMap<>();

        /* 构造绝对路径 */
        String curPath = mainClass.getResource("").getPath();
        curPath = curPath.substring(1, curPath.indexOf("classes") + 7);
        StringBuilder sb = new StringBuilder(curPath);
        for (int i = 0; i < curPath.length(); i++)
            if (curPath.charAt(i) == '/') sb.setCharAt(i, '\\');
        curPath = sb.toString();

        /* 扫描项目中的类 仅作扫描使用*/
        classLoader = new TrivialClassLoader(curPath);
        Set<String> classNameSet = classLoader.getClassByteMap().keySet();

        /**
         * 仍然用应用程序类加载器加载packageName下的类，以免在反射调用时，一部分被自定义类加载器加载，另一部分被应用程序类加载器加载
         * 发现了@TrviailService就加到service中，待会儿会把services注册到zookeeper上
         */
        for (String s : classNameSet) { /* s 为类的全限定名 */
            classLoader.unloadClass(s); /* 自定义类加载可以清除这个类了，因为我们只需要从它那里获取类的全限定名 */
            Class c = Class.forName(s); /* 用应用程序类加载器加载 */
            TrivialService trivialService = (TrivialService) c.getAnnotation(TrivialService.class);
            if (trivialService == null) continue;
            Map<String, Object> map = new HashMap<>();
            map.put("open", trivialService.open());
            map.put("version", trivialService.version());

            /* 暴露服务实现的接口 */
            for (Class cInterface : c.getInterfaces()) {
                services.put(cInterface.getSimpleName(), map); /* 不用全限定名 */
            }
        }
    }

    /* 配置基础参数 */
    private static void config() {
        Map<String, Object> map = YmlUtil.getResMap("server");
        if (map == null) throw new ArgNotFoundExecption("rpc.yml缺少参数server！");
        if (!map.containsKey("host")) throw new ArgNotFoundExecption("rpc.yml缺少参数host!");
        host = (String) map.get("host");
        if (!map.containsKey("port")) throw new ArgNotFoundExecption("rpc.yml缺少参数port!");
        port = (Integer) map.get("port");
        if (!map.containsKey("packageName") && packageName == null)
            throw new ArgNotFoundExecption("rpc.yml缺少参数packageName!");
        if (map.containsKey("packageName"))
            packageName = (String) map.get("packageName"); /* rpc.yml的优先级较高 */
        if (map.containsKey("bossSize")) bossSize = (int) map.get("bossSize");
        if (map.containsKey("workerSize")) workerSize = (int) map.get("workerSize");
        if (map.containsKey("businessPoolModel")) businessPoolModel = (int) map.get("businessPoolModel");
        if (businessPoolModel == 1 && executor == null) throw new ArgNotFoundExecption("缺少jdk线程池");
        if (businessPoolModel == 2 && poolService == null) throw new ArgNotFoundExecption("缺少自实现线程池");
        if (map.containsKey("allIdleTime")) allIdleTime = (int) map.get("allIdleTime");
        if (map.containsKey("enableSpring")) enableSpring = (int) map.get("enableSpring");
        if (enableSpring == 1) initSpring();
        if(map.containsKey("logLevel")) {
            String s=(String) map.get("logLevel");
            switch (s){
                case "debug":
                    LOG_LEVEL=LOG.LOG_DEBUG;
                    break;
                case "info":
                    LOG_LEVEL=LOG.LOG_INFO;
                    break;
                case "warning":
                    LOG_LEVEL=LOG.LOG_WARNING;
                    break;
                case "error":
                    LOG_LEVEL=LOG.LOG_ERROR;
                    break;
                default:
                    throw new ArgNotFoundExecption("logLevel的值只能是{debug, info, warning, error}其中之一");
            }
        }
    }

    /* 初始化spring环境 */
    private static void initSpring() {

    }

    /* 配置zookeeper参数 */
    private static void zkConfig() throws Exception {
        Map<String, Object> map = YmlUtil.getResMap("zookeeper");
        if (map == null) return; /* 没有配置就直接略过 */
        if (!map.containsKey("host")) throw new ArgNotFoundExecption("rpc.yml缺少参数host!");
        zkHost = (String) map.get("host");
        if (!map.containsKey("port")) throw new ArgNotFoundExecption("rpc.yml缺少参数port!");
        zkPort = (Integer) map.get("port");
        if (map.containsKey("sessionTimeOut")) sessionTimeOut = (int) map.get("sessionTimeOut");
        if (!map.containsKey("provideServiceNames") && services == null)
            throw new ArgNotFoundExecption("rpc.yaml缺少参数provideServiceNames");
        if (map.containsKey("provideServiceNames"))
            services = (Map<String, Map<String, Object>>) map.get("provideServiceNames"); /* rpc.yml的优先级较高 */
        if (services == null) return;
        Set<String> keySet = services.keySet();
        /**
         * 将服务信息挂载到zookeeper上，
         * 形如：/trivial/serviceA/providers/127.0.0.1:7000, {version:1.0, open:true}
         */
        String connectString = zkHost + ":" + zkPort;
        zooKeeper = new ZooKeeper(connectString, sessionTimeOut, (event) -> {
            /* 多级节点要求父级为persistent */
            try {
                if (event.getState() == Watcher.Event.KeeperState.SyncConnected) {
                    if (zooKeeper.exists("/trivial", null) == null) {
                        /* 先创建父级persistent节点 */
                        zooKeeper.create("/trivial", null
                                , ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                    }
                    for (String s : keySet) {
                        Double version = 1.0; /* 服务的版本 */
                        Boolean open = true; /* 是否开启服务 */
                        Map<String, Object> infoMap = services.get(s);
                        if (infoMap != null) { //todo 增加权重信息
                            if (infoMap.containsKey("version")) version = (Double) infoMap.get("version");
                            if (infoMap.containsKey("open")) open = (Boolean) infoMap.get("open");
                        }
                        if (zooKeeper.exists("/trivial/" + s, null) == null) {
                            /* 先创建父级persistent节点 */
                            zooKeeper.create("/trivial/" + s, null
                                    , ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                            zooKeeper.create("/trivial/" + s + "/providers", null
                                    , ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                        }
                        /* 创建当前ephemeral节点 */
                        zooKeeper.create("/trivial/" + s + "/providers/" + (host + ":" + port),
                                (version + "," + open).getBytes(CharsetUtil.UTF_8),
                                ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
                    }
                    if(RPCServer.LOG_LEVEL<= LOG.LOG_INFO) log.info("成功注册到zookeeper");
                } else log.error("注册失败", new RegistrationFailException("注册失败"));
            } catch (Exception e) {
                if(RPCServer.LOG_LEVEL<= LOG.LOG_ERROR) log.error("节点异常", e);
            }

        });
    }

    /* 启动netty线程组 */
    public static void startServer(Class mainClass) throws Exception {
        handleMainClass(mainClass);
        zkConfig();
        config();
        new Thread(() -> { /* 转移阻塞点，使主线程得以返回 */
            startServer0();
        }).start();
    }

    /* 指定boss worker数量启动netty线程组 */
    private static void startServer0() {
        EventLoopGroup bossGroup = new NioEventLoopGroup(bossSize);
        EventLoopGroup workerGroup = new NioEventLoopGroup(workerSize);
        try {
            ServerBootstrap serverBootstrap = new ServerBootstrap();
            /**
             * 开启tcp keepAlive,，当开启后，会有tcp层面上的心跳机制，我们应该关闭而去做我们自己的更为定制化的心跳探测
             */
            //serverBootstrap.childOption(ChannelOption.SO_KEEPALIVE,true);
            serverBootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class) /* channel类型水平触发，EpollServerSocketChannel边缘触发 */
                    .childHandler( /* 作用于workerGroup */
                            new ChannelInitializer<SocketChannel>() {
                                /**
                                 * 初始化器也算是一个handler,在pipeline中初始化socketChannel，完成后从pipeline中移除
                                 */
                                @Override
                                protected void initChannel(SocketChannel ch) throws Exception {
                                    ChannelPipeline pipeline = ch.pipeline();
                                    pipeline.addLast(new TrivialEncoder());
                                    pipeline.addLast(new TrivialDecoder(ClientMessage.class));

                                    /* 默认1分钟内无读无写则空闲，最终取决于用户配置的参数allIdleTime */
                                    pipeline.addLast(new IdleStateHandler(60 * 1000,
                                            60 * 1000, allIdleTime, TimeUnit.MILLISECONDS));
                                    pipeline.addLast(new RPCServerHandler());//业务处理器
                                }
                            }
                    );
            ChannelFuture channelFuture = serverBootstrap.bind(host, port).sync();
            if(RPCServer.LOG_LEVEL<= LOG.LOG_INFO) log.info("server is ready! ");
            channelFuture.channel().closeFuture().sync(); /* 同步方法，直到有结果才往下执行 */
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
