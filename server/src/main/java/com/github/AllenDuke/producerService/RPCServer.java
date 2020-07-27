package com.github.AllenDuke.producerService;


import com.github.AllenDuke.exception.ArgNotFoundExecption;
import com.github.AllenDuke.exception.RegistrationFailException;
import com.github.AllenDuke.myThreadPoolService.ThreadPoolService;
import com.github.AllenDuke.util.YmlUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;

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

    //zookeeper地址
    private static String zkHost;

    //zookeeper端口
    private static int zkPort;

    //zookeeper客户端
    private static ZooKeeper zooKeeper;

    //zookeeper连接超时
    private static int sessionTimeOut=1000;

    //往zookeeper中注册的服务，key为服务名，value为Map for{version: 1.0, open: true}
    private static Map<String,Map<String,Object>> services;

    //读写空闲达10s后，服务提供方断开连接
    protected static int allIdleTime=10*1000;//单位毫秒

    //配置jdk线程池，启动服务端
    public static void startServer(ThreadPoolExecutor poolExecutor) throws Exception {
        executor=poolExecutor;
        startServer();
    }

    //配置自实现线程池，启动服务端
    public static void startServer(ThreadPoolService threadPoolService) throws Exception {
        poolService=threadPoolService;
        startServer();
    }

    //配置基础参数
    private static void config(){
        Map<String, Object> map = YmlUtil.getResMap("server");
        if(map==null) throw  new ArgNotFoundExecption("rpc.yml缺少参数server！");
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
        if(map.containsKey("allIdleTime")) allIdleTime=(int) map.get("allIdleTime");
    }

    //配置zookeeper参数
    private static void zkConfig() throws Exception{
        Map<String, Object> map= YmlUtil.getResMap("zookeeper");
        if(map==null) return;//没有配置就直接略过
        if (!map.containsKey("host")) throw new ArgNotFoundExecption("rpc.yml缺少参数host!");
        zkHost= (String) map.get("host");
        if (!map.containsKey("port")) throw new ArgNotFoundExecption("rpc.yml缺少参数port!");
        zkPort = (Integer) map.get("port");
        if(map.containsKey("sessionTimeOut")) sessionTimeOut=(int) map.get("sessionTimeOut");
        if(!map.containsKey("provideServiceNames")) throw new ArgNotFoundExecption("rpc.yaml缺少参数provideServiceNames");
        RPCServer.services = (Map<String, Map<String, Object>>) map.get("provideServiceNames");
        Set<String> keySet = RPCServer.services.keySet();
        String connectString=zkHost+":"+zkPort;
        zooKeeper=new ZooKeeper(connectString,sessionTimeOut,(event)->{
            //多级节点要求父级为persistent
            try {
                if (event.getState()  == Watcher.Event.KeeperState.SyncConnected) {
                    if (zooKeeper.exists("/trivial", null)==null){
                        //先创建父级persistent节点
                        zooKeeper.create("/trivial",null
                                , ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                    }
                    for (String s : keySet) {
                        Double version=1.0;
                        Boolean open=true;
                        Map<String, Object> infoMap = services.get(s);
                        if(infoMap!=null){//todo 增加权重信息
                            if(infoMap.containsKey("version")) version= (Double) infoMap.get("version");
                            if(infoMap.containsKey("open")) open= (Boolean) infoMap.get("open");
                        }
                        if (zooKeeper.exists("/trivial/" + s, null)==null){
                            //先创建父级persistent节点
                            zooKeeper.create("/trivial/"+s,null
                                    , ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                            zooKeeper.create("/trivial/"+s+"/providers",null
                                    , ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                        }
                        //创建当前ephemeral节点
                        zooKeeper.create("/trivial/"+s+"/providers/"+(host+":"+port),
                                (version+","+open).getBytes(CharsetUtil.UTF_8),
                                ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
                    }
                    log.info("成功注册到zookeeper");
                }else log.error("注册失败",new RegistrationFailException("注册失败"));
            }catch (Exception e){
                log.error("节点异常",e);
            }

        });
    }

    //启动netty线程组
    public static void startServer() throws Exception {
        zkConfig();
        config();
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
            //serverBootstrap.childOption(ChannelOption.SO_KEEPALIVE,true);//开启tcp keepAlive
            serverBootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)//水平触发，EpollServerSocketChannel边缘触发
                    .childHandler(//作用于workerGroup
                            new ChannelInitializer<SocketChannel>() {//初始化器也算是一个handler,在pipeline中
                                //初始化socketChannel，完成后从pipeline中移除
                                      @Override
                                      protected void initChannel(SocketChannel ch) throws Exception {
                                          ChannelPipeline pipeline = ch.pipeline();
                                          ByteBuf delimiter = Unpooled.copiedBuffer("}".getBytes());//“}”为分隔符
                                          //解码器循环解码，每解析出一个就往后传播
                                          pipeline.addLast(new DelimiterBasedFrameDecoder(2048,
                                                  false, delimiter));
                                          pipeline.addLast(new StringEncoder());//outbound编码器
                                          pipeline.addLast(new StringDecoder());//inbound解码器
                                          pipeline.addLast(new IdleStateHandler(60*1000,
                                                  60*1000,allIdleTime, TimeUnit.MILLISECONDS));
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
