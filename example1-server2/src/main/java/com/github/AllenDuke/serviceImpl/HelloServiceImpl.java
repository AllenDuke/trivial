package com.github.AllenDuke.serviceImpl;

import com.github.AllenDuke.service.HelloService;

/**
 * @author 杜科
 * @description sayHello服务实现
 * @contact AllenDuke@163.com
 * @since 2020/2/11
 */
public class HelloServiceImpl implements HelloService {

    public String hello(String a,String b,Integer num) {
        return "你好，"+a+" and "+b+" "+num;
    }

    //线程安全的单例懒加载模式
    public static HelloServiceImpl getInstance(){
        return HelloServiceImplInner.getHelloService();
    }
    static class HelloServiceImplInner{
        private static HelloServiceImpl helloService=new HelloServiceImpl();
        public static HelloServiceImpl getHelloService(){ return helloService;}
    }
}
