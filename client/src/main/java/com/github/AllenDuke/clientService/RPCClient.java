package com.github.AllenDuke.clientService;


import com.github.AllenDuke.dto.ClientMessage;
import com.github.AllenDuke.event.TimeOutEvent;
import com.github.AllenDuke.exception.ArgNotFoundExecption;
import com.github.AllenDuke.exception.InitException;
import com.github.AllenDuke.exception.ShutDownException;
import com.github.AllenDuke.exception.SubscribeFailException;
import com.github.AllenDuke.listener.DefaultTimeOutListener;
import com.github.AllenDuke.listener.TimeOutListener;
import com.github.AllenDuke.util.YmlUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/**
 * @author 杜科
 * @description rpc消费者，要求源码中resource文件夹中有rpc.yml
 * 由于netty线程收到数据后只需要进行简单的验证就可以去唤醒caller，因此不需要使用业务线程池
 * 而客户端的超时机制，另有详细说明
 * @contact AllenDuke@163.com
 * @since 2020/2/11
 */
@Slf4j
public class RPCClient {

    //初始化标志
    private static boolean isInit = false;

    //关闭标志，用于关闭netty线程组
    protected static boolean shutdown = false;

    //服务提供方主机地址，用于直连
    private static String serverHost;

    //服务提供方端口号
    private static int serverPort;

    //是否允许超时，不允许将一直阻塞
    protected static long timeout = -1;

    //超时重试次数
    public static int retryNum = 0;

    //netty线程数
    private static int workerSize = 0;//为0将使用默认值：cpu核数*2

    //业务处理器
    public static RPCClientHandler clientHandler;

    //超时监听器，超时后会收到通知
    protected static TimeOutListener listener;

    //zookeeper地址
    private static String zkHost;

    //zookeeper端口
    private static int zkPort;

    //zookeeper客户端
    public static ZooKeeper zooKeeper;

    //zookeeper连接超时
    private static int sessionTimeOut = 1000;

    //往zookeeper中订阅的服务
    private static Map<String, String> serviceNames;

    //注册中心，用于寻找服务
    protected static Registry registry;

    //连接器，用于与服务端通信
    protected static Connector connector;

    /**
     * @param timeOutListener 超时监听器
     * @description: 注册超时监听器，当发生超时时，将调用监听器里的相关方法
     * @return: void
     * @author: 杜科
     * @date: 2020/3/4
     */
    public synchronized static void init(TimeOutListener timeOutListener) throws Exception {
        if (isInit) return;
        listener = timeOutListener;
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
    public synchronized static void init() throws Exception {
        if (isInit) return;
        isInit = true;
        zkconfig();
        config();
        if (timeout != -1 && listener == null) listener = new DefaultTimeOutListener();//设置默认监听器(注意初始化顺序)
    }

    //配置基础参数
    private static void config() {
        Map<String, Object> map = YmlUtil.getResMap("client");
        if (map == null) throw new ArgNotFoundExecption("rpc.yml缺少参数client");
        if (!map.containsKey("serverHost")) throw new ArgNotFoundExecption("rpc.yml缺少参数serverHost!");
        serverHost = (String) map.get("serverHost");
        if (!map.containsKey("serverPort")) throw new ArgNotFoundExecption("rpc.yml缺少参数serverPort!");
        serverPort = (Integer) map.get("serverPort");
        if (map.containsKey("timeout")) timeout = new Long((int) map.get("timeout"));
        if (map.containsKey("retryNum")) retryNum = (int) map.get("retryNum");
        if (map.containsKey("workerSize")) workerSize = (int) map.get("workerSize");
        connector = new Connector();
    }

    //配置zookeeper参数
    private static void zkconfig() throws Exception {
        Map<String, Object> map = YmlUtil.getResMap("zookeeper");
        if (map == null) return;//没有配置就直接略过
        if (!map.containsKey("host")) throw new ArgNotFoundExecption("rpc.yml缺少参数host!");
        zkHost = (String) map.get("host");
        if (!map.containsKey("port")) throw new ArgNotFoundExecption("rpc.yml缺少参数port!");
        zkPort = (Integer) map.get("port");
        if (map.containsKey("sessionTimeOut")) sessionTimeOut = (int) map.get("sessionTimeOut");
        if (map.containsKey("serviceNames")) serviceNames = (Map<String, String>) map.get("serviceNames");
        String connectString = zkHost + ":" + zkPort;
        Thread main=Thread.currentThread();
        zooKeeper = new ZooKeeper(connectString, sessionTimeOut, (event) -> {
            //多级节点要求父级为persistent
            try {
                if (event.getState() == Watcher.Event.KeeperState.SyncConnected) {
                    if (serviceNames != null && serviceNames.keySet() != null)//这是不必要的参数
                        for (String s : serviceNames.keySet()) {
                            if (zooKeeper.exists("/trivial/" + s, null) == null) {
                                //先创建父级persistent节点
                                zooKeeper.create("/trivial/" + s, null
                                        , ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                                zooKeeper.create("/trivial/" + s + "/consumers", null
                                        , ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                            }
                        }
                    log.info("成功从zookeeper订阅服务");
                } else log.error("订阅失败", new SubscribeFailException("订阅失败"));
            } catch (Exception e) {
                log.error("节点创建异常", e);
            }finally {
                LockSupport.unpark(main);//唤醒主线程
            }
        });
        registry = new Registry();
        LockSupport.park();//主线程等待zookeeper连接成功
    }

    /**
     * @param serivceClass 服务要实现的接口
     * @description: 返回一个代理对象（jdk动态代理）,这里没有做缓存
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
        if (!isInit) throw new InitException("还没有init");
        if(shutdown) throw new ShutDownException("当前RPCClient已经shutdown了");
        return Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{serivceClass}, (proxy, method, args) -> {
                    //lamda表达式，匿名内部类实现InvokeInhandler接口，重写invoke方法

                    if(shutdown) throw new ShutDownException("当前RPCClient已经shutdown了");
                    String className = serivceClass.getName();
                    className = className.substring(className.lastIndexOf(".") + 1);//去掉包名
                    ClientMessage clientMessage = new ClientMessage(Thread.currentThread().getId(),
                            className, method.getName(), args);
                    Object result = connector.invoke(clientMessage);
//                    if(result.getClass()==method.getReturnType())
                    return result;
                });
    }

    public static void shutdown() {
        connector.shutDown();
        shutdown = true;//按netty线程组的关闭策略先让其完成相关工作，再去检查超时观察者
        if (timeout != -1) {//结束超时观察者
            RPCClientHandler.getWaiterQueue()//传入结束标志
                    .add(new TimeOutEvent(null, 0));
        }
    }
}
