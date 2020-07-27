package com.github.AllenDuke.clientService;

import com.github.AllenDuke.exception.ServiceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

import java.util.*;

/**
 * @author 杜科
 * @description 注册中心，主要用于消费者从注册中心中拿取服务提供者信息
 * @contact AllenDuke@163.com
 * @since 2020/3/10
 */
@Slf4j
public class Registry {

    private ZooKeeper zooKeeper=RPCClient.zooKeeper;

    //本地存根，key为服务名，value的提供该服务的主机列表
    //todo 是否用ConcurrentHashMap换取可见性，findServer方法有解释
    private final Map<String,List<String>> addrMem=new HashMap<>();



    /**
     * @description:  根据要消费的服务的名称随机返回一个服务提供者的地址，如果该服务已经降级，那么返回null。
     * 注册中心设置一些降级一些不降级是希望客户端随机拿取到降级或者不降级，而不是选取不降级那些
     * @param serviceName 要消费的服务的名称
     * @return: java.lang.String 如 127.0.0.1:7000
     * @author: 杜科
     * @date: 2020/3/10
     */
    public String findServer(String serviceName){
        List<String> children=addrMem.get(serviceName);
        if(children==null){//没有本地存根
            try {
                children= zooKeeper.getChildren("/trivial/" + serviceName + "/providers", null);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return selectServer(children,serviceName);
    }

    /**
     * @description: 根据要消费的服务的名称和要过滤掉的主机地址随机返回一个服务提供者的地址，用于超时请求重路由
     * 这里会发生并发的操作，但是addrMem暂时用的是HashMap，因为过滤的删除操作是在局部变量中完成的，完成后在整体地put回去，
     * 不会有线程不安全的问题，但是有可见性的问题（这个问题是不大的，衡量是否为了解决可见性而增加并发控制带来的额外开销？）
     * @param serviceName 要消费的服务的名称
     * @param blackSet 黑名单，要过滤掉的名单
     * @return: java.lang.String
     * @author: 杜科
     * @date: 2020/3/11
     */
    public String findServer(String serviceName, Set<String> blackSet) {
        if(blackSet==null||blackSet.size()==0) return findServer(serviceName);
        List<String> children=addrMem.get(serviceName);
        if(children!=null){//如果有本地存根，总是先过滤，防止黑名单变动
            for (int i = 0; i < children.size();) {
                if(blackSet.contains(children.get(i))) children.remove(i);
                else i++;
            }
        }
        if(children==null){//过滤后没有存活的
            try {
                children= zooKeeper.getChildren("/trivial/" + serviceName + "/providers", null);
            } catch (Exception e) {
                e.printStackTrace();
            }
            for (int i = 0; i < children.size();) {
                if(blackSet.contains(children.get(i))) children.remove(i);
                else i++;
            }
        }
        return selectServer(children,serviceName);
    }

    /**
     * @description: 从主机列表中随机挑选出一个，然后然后验证节点信息。
     * //todo 增加多种负载均衡算法
     * @param children 主机列表
     * @param serviceName 服务名称
     * @return: java.lang.String
     * @author: 杜科
     * @date: 2020/4/28
     */
    private String selectServer(List<String> children,String serviceName){
        if(children==null||children.size()==0) {
            log.error("找不到服务",new ServiceNotFoundException("找不到服务："+serviceName));
            return null;
        }
        addrMem.put(serviceName,children);//更新本地存根（过滤黑名单）
        int rand= new Random().nextInt(children.size());//随机返回children.size为上界的非负数
        String addr=children.get(rand);
        return verifyNodeInfo(addr,serviceName);
    }

    /**
     * @description: 检查该服务的版本信息和降级信息，如果已经降级，那么返回null
     * //todo 把版本，降级信息也缓存起来，利用watcher机制来更新
     * @param serviceName 服务名称
     * @param addr 服务方主机地址
     * @return: java.lang.String
     * @author: 杜科
     * @date: 2020/4/28
     */
    private String verifyNodeInfo(String addr,String serviceName){
        byte[] data=null;
        if(data==null){
            Stat stat=new Stat();
            try {
                data = zooKeeper.getData("/trivial/" + serviceName + "/providers/" + addr, null, stat);
            } catch (Exception e) {
                e.printStackTrace();
                //因为有可能zookeeper已经宕机，所以直接返回addr
                return addr;
            }
        }
        String s = new String(data);
        String versionS=s.substring(0,s.indexOf(","));
        s=s.substring(s.indexOf(",")+1);
        String open=s;
        if(open.equals("true")) return addr;
        else return null;
    }
}
