package com.github.AllenDuke.business;

import com.github.AllenDuke.dto.ClientMessage;
import com.github.AllenDuke.exception.MethodNotFoundException;
import com.github.AllenDuke.producerService.RPCServer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;

/**
 * @author 杜科
 * @description 调用处理器，找到类，找到方法，调用
 * @contact AllenDuke@163.com
 * @since 2020/3/1
 */
public class InvokeHandler {
    
    //实现类所在的包名，可把类都先加载到一个HashMap中
    private static String packageName = RPCServer.packageName+".";

    //key为实现类的全限定名
    private static final Map<String, Class> classMap = new HashMap<>();

    //key为实现方法的全限定名
    private static final Map<String, Method> methodMap = new HashMap<>();

    //key为实现类名，value为实现类的实例
    private static Map<String,Object> serviceObjects=new HashMap<>();

    /**
     * @description: 找到要调用的类，先从缓存中找，找不到再Class.forName()加载，会抛出ClassNotFoundException
     * @param className 类的全限定名
     * @return: java.lang.Class
     * @author: 杜科
     * @date: 2020/3/1
     */
    public Class findClass(String className) throws ClassNotFoundException {
        Class serviceImpl = classMap.get(className);
        /**
         * 并发时可能会进行多次put操作，但问题不大，put的是同一个Class对象。
         */
        if(serviceImpl==null) serviceImpl= Class.forName(className);//找不到将抛异常
        classMap.put(className,serviceImpl);
        return serviceImpl;
    }

    /**
     * @description: 找到要调用的方法，先从缓存中找，会抛出MethodNotFoundException
     * @param serviceImpl 要调用的类
     * @param methodName 要调用的方法的名字
     * @param args 方法的参数，这里用作验证方法
     * @param argsTypeName 参数类型名，这里用作找到方法后以全限定名添加到缓存
     * @return: java.lang.reflect.Method
     * @author: 杜科
     * @date: 2020/3/1
     */
    public Method findMethod(Class serviceImpl,String methodName,Object[] args,String argsTypeName){
        Method method = methodMap.get(serviceImpl.getName()+"."+methodName+argsTypeName);
        if (method == null)
            for (Method method1 : serviceImpl.getMethods()) {
                if (method1.getName().equals(methodName)&&method1.getParameterCount()==args.length) {
                    final Parameter[] parameters = method1.getParameters();
                    boolean match=true;
                    for (int i = 0; i < args.length; i++) {
                        if(args[i].getClass()!=parameters[i].getType()){//发现参数类型不同，不再验证该方法
                            match=false;
                            break;
                        }
                    }
                    if(match) {
                        method=method1;
                        /**
                         * 并发时可能会进行多次put操作，但问题不大，put的是同一个Method对象。
                         */
                        methodMap.put(serviceImpl.getName()+"."+methodName+argsTypeName, method);
                        break;
                    }
                }
            }
        if(method==null) throw new MethodNotFoundException("找不到方法 "+methodName);
        return method;
    }

    /**
     * @description: 对方法进行调用，会要求调用的类有无参构造
     * 会抛出IllegalAccessException InstantiationException InvocationTargetException
     * @param serviceImpl
     * @param method
     * @param args
     * @return: java.lang.Object
     * @author: 杜科
     * @date: 2020/3/1
     */
    public Object invoke(Class serviceImpl,Method method,Object[] args) throws IllegalAccessException,
            InstantiationException, InvocationTargetException {
        Object o = serviceObjects.get(serviceImpl.getName());
        /**
         * double-check使得只创建一个实例，而ConcurrentHashMap做不到
         */
        if(o==null){
            synchronized (serviceObjects){
                if(o==null){
                    o=serviceImpl.newInstance();
                    serviceObjects.put(serviceImpl.getName(),o);
                }
            }
        }
        return method.invoke(o, args);
    }

    /**
     * @description: 对客户端发来的信息，进行方法用，返回结果
     * 会抛出ClassNotFoundException IllegalAccessException
     * InvocationTargetException InstantiationException MethodNotFoundException
     * @param clientMessage 客户端发来的信息
     * @return: java.lang.Object
     * @author: 杜科
     * @date: 2020/3/1
     */
    public Object handle(ClientMessage clientMessage) throws ClassNotFoundException, IllegalAccessException,
            InvocationTargetException, InstantiationException ,MethodNotFoundException{
        String className= packageName+clientMessage.getClassName();
        Object[] args= clientMessage.getArgs();
        StringBuilder argsTypeName=new StringBuilder(",argsType:");//参数类型名
        for (Object arg : args) {
            argsTypeName.append(arg.getClass().getName()+ " ");
        }
        String methodName= clientMessage.getMethodName();
        Class serviceImpl=findClass(className);
        Method method=findMethod(serviceImpl,methodName,args,argsTypeName.toString());
        Object result=invoke(serviceImpl,method,args);
        return result;
    }

}
