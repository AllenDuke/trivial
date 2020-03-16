package com.github.AllenDuke.clientService;

import com.github.AllenDuke.exception.ServiceNotFoundException;
import org.apache.zookeeper.ZooKeeper;

import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * @author 杜科
 * @description 注册中心，主要用于消费者从注册中心中拿取服务提供者信息
 * @contact AllenDuke@163.com
 * @since 2020/3/10
 */
public class Registry {

    private ZooKeeper zooKeeper=RPCClient.zooKeeper;

    /**
     * @description:  根据要消费的服务的名称随机返回一个服务提供者的地址
     * @param serviceName 要消费的服务的名称
     * @return: java.lang.String 如 127.0.0.1:7000
     * @author: 杜科
     * @date: 2020/3/10
     */
    public String findServer(String serviceName ){
        List<String> children=null;
        try {
            children= zooKeeper.getChildren("/trivial/" + serviceName + "/providors", null);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if(children==null||children.size()==0) throw new ServiceNotFoundException("找不到服务："+serviceName);
        int rand= new Random().nextInt(children.size());//随机返回children.size为上界的非负数
        return children.get(rand);
    }

    /**
     * @description: 根据要消费的服务的名称和要过滤掉的主机地址随机返回一个服务提供者的地址，用于超时请求重路由
     * @param serviceName 要消费的服务的名称
     * @param blackSet 黑名单，要过滤掉的名单
     * @return: java.lang.String
     * @author: 杜科
     * @date: 2020/3/11
     */
    public String findServer(String serviceName, Set<String> blackSet){
        if(blackSet==null||blackSet.size()==0) return findServer(serviceName);
        List<String> children=null;
        try {
            children= zooKeeper.getChildren("/trivial/" + serviceName + "/providors", null);
        } catch (Exception e) {
            e.printStackTrace();
        }
        for (int i = 0; i < children.size();) {
            if(blackSet.contains(children.get(i))) children.remove(i);
            else i++;
        }
        int rand= new Random().nextInt(children.size());//随机返回children.size为上界的非负数
        return children.get(rand);
    }
}
