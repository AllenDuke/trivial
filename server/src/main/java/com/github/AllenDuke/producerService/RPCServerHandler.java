package com.github.AllenDuke.producerService;


import com.alibaba.fastjson.JSON;
import com.github.AllenDuke.dto.ClientMessage;
import com.github.AllenDuke.dto.ServerMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;

/**
 * @author 杜科
 * @description rpc服务提供者的业务处理器
 * 由netty线程负责接收来自客户端的信息，解析信息，调用相关方法，写回结果，后续版本会使用业务线程池来更新
 * 这里有多出try catch是为了防止netty线程在处理时异常退出。
 * @contact AllenDuke@163.com
 * @since 2020/2/11
 */
@Slf4j
public class RPCServerHandler extends ChannelInboundHandlerAdapter {

    //实现类所在的包名，可把类都先加载到一个HashMap中
    private static String packageName = RPCServer.packageName+".";

    //key为实现类的全限定名
    private static final Map<String, Class> classMap = new HashMap<>();

    //key为实现方法的全限定名
    private static final Map<String, Method> methodMap = new HashMap<>();

    /**
     * @description: 由netty线程负责接收来自客户端的信息，解析信息，调用相关方法，写回结果
     * @param ctx 当前channelHandler所在的环境（重量级对象）
     * @param msg netty线程读取到的信息
     * @return: void
     * @author: 杜科
     * @date: 2020/2/28
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        log.info("服务器收到信息：" + msg + "，准备解码，调用服务");
        ClientMessage clientMessage=null;
        try{
            clientMessage = JSON.parseObject((String) msg, ClientMessage.class);
        }catch (Exception e){
            log.error("解析异常，放弃本次解析任务，即将通知客户端",e);
            ctx.writeAndFlush("服务器解析异常");
            return;
        }
        String className= clientMessage.getClassName();
        String methodName= clientMessage.getMethodName();
        Object[] args= clientMessage.getArgs();
        StringBuilder argsTypeName=new StringBuilder(",argsType:");//参数类型名
        for (Object arg : args) {
            argsTypeName.append(arg.getClass().getName()+ " ");
        }
        log.info("服务器尝试调用——"+packageName + className + "." + methodName+argsTypeName);
        //寻找类
        Class serviceImpl = classMap.get(packageName + className);
        try {//这里forName去寻找类，也可以一开始就把包下的类都加载进来
            if(serviceImpl==null) serviceImpl= Class.forName(packageName + className);
        }catch (Exception e){
            log.error("服务器找不到服务实现类，放弃本次调用服务，即将通知本次调用者",e);
            ServerMessage serverMessage=new ServerMessage(clientMessage.getCallerId()
                    ,clientMessage.getCount(),false,"服务器找不到服务实现类，请检查类名！");
            ctx.writeAndFlush(JSON.toJSONString(serverMessage));
            return;
        }
        //类中对应的方法（方法中某些方法可能不向外提供）
        Method method = methodMap.get(packageName + className + "." + methodName+argsTypeName);
        if (method == null)
            for (Method method1 : serviceImpl.getMethods()) {
                if (method1.getName().equals(methodName)&&method1.getParameterCount()==args.length) {
                    StringBuilder argsType=new StringBuilder(",argsType：");//参数类型名,准备验证参数类型
                    final Parameter[] parameters = method1.getParameters();
                    boolean match=true;
                    for (int i = 0; i < args.length; i++) {
                        if(args[i].getClass()!=parameters[i].getType()){//发现参数类型不同，不再验证该方法
                            match=false;
                            break;
                        }else argsType.append(parameters[i].getType().getName()+" ");
                    }
                    if(match) {
                        method=method1;
                        methodMap.put(packageName + className + "."
                                + methodName+argsType.toString(), method1);//找到后加入hashMap
                        break;
                    }

                }

            }
        if(method==null){
            log.error("服务器找不到服务实现类的实现方法，放弃本次调用服务，即将通知本次调用者");
            ServerMessage serverMessage=new ServerMessage(clientMessage.getCallerId()
                    ,clientMessage.getCount()
                    , false,"服务器找不到服务实现类的实现方法，请检查方法名和参数！");
            ctx.writeAndFlush(JSON.toJSONString(serverMessage));
            return;
        }

        //方法调用
        Object result = null;//返回结果
        try{
            result = method.invoke(serviceImpl.newInstance(), args );//serviceImpl无参构造要public
        }catch (Exception  e){
            log.error("服务器的实现方法调用异常，放弃本次调用服务，即将通知本次调用者",e);
            ServerMessage serverMessage=new ServerMessage(clientMessage.getCallerId()
                    ,clientMessage.getCount()
                    , false,"服务器的实现方法调用异常");
            ctx.writeAndFlush(JSON.toJSONString(serverMessage));
            return;
        }

        ServerMessage serverMessage=new ServerMessage(clientMessage.getCallerId()
                ,clientMessage.getCount(),true,result);
        log.info("服务器的实现方法调用成功，即将返回信息："+serverMessage);
        ctx.writeAndFlush(JSON.toJSONString(serverMessage));//转换为json文本
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        ctx.close();
    }
}
