package com.github.AllenDuke.business;

import com.alibaba.fastjson.JSON;
import com.github.AllenDuke.dto.ClientMessage;
import com.github.AllenDuke.exception.MethodNotFoundException;
import com.github.AllenDuke.producerService.RPCServer;
import com.github.AllenDuke.spring.TrivialSpringUtil;

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
    private static String packageName = RPCServer.packageName + ".";

    //key为实现类的全限定名
    private static final Map<String, Class> classMap = new HashMap<>();

    //key为实现方法的全限定名
    private static final Map<String, Method> methodMap = new HashMap<>();

    //key为实现类名，value为实现类的实例
    private static Map<String, Object> serviceObjects = new HashMap<>();

    /**
     * @param className 类的全限定名
     * @description: 找到要调用的类，先从缓存中找，找不到再Class.forName()加载，会抛出ClassNotFoundException
     * @return: java.lang.Class
     * @author: 杜科
     * @date: 2020/3/1
     */
    public Class findClass(String className) throws ClassNotFoundException {
        Class serviceImpl = classMap.get(className);
        /**
         * 并发时可能会进行多次put操作，但问题不大，put的是同一个Class对象。
         */
        if (serviceImpl == null) serviceImpl = Class.forName(className);//找不到将抛异常
        classMap.put(className, serviceImpl);
        return serviceImpl;
    }

    /**
     * @param serviceImpl  要调用的类
     * @param methodName   要调用的方法的名字
     * @param args         方法的参数，这里用作验证方法
     * @param argTypes 参数类型名，这里用作找到方法后以全限定名添加到缓存
     * @description: 找到要调用的方法，先从缓存中找，会抛出MethodNotFoundException
     * @return: java.lang.reflect.Method
     * @author: 杜科
     * @date: 2020/3/1
     */
    public Method findMethod(Class serviceImpl, String methodName, Object[] args, String argTypes) {
        Method method = methodMap.get(serviceImpl.getName() + "." + methodName + argTypes);//先在缓存中寻找
        if (method == null)
            for (Method method1 : serviceImpl.getMethods()) {
                if (method1.getName().equals(methodName) && method1.getParameterCount() == args.length) {
                    final Parameter[] parameters = method1.getParameters();
                    /**
                     * 比对的是全限定名
                     */
                    StringBuilder curArgTypes = new StringBuilder(",argTypes:");
                    for (Parameter parameter : parameters) {
                        curArgTypes.append(parameter.getType().getName() + " ");
                    }
                    if (!curArgTypes.toString().equals(argTypes)) continue;

                    method = method1;
                    /**
                     * 并发时可能会进行多次put操作，但问题不大，put的是同一个Method对象。
                     */
                    methodMap.put(serviceImpl.getName() + "." + methodName + argTypes, method);
                    break;

                }
            }
        if (method == null) throw new MethodNotFoundException("找不到方法 " + methodName);
        return method;
    }

    /**
     * @param serviceImpl
     * @param method
     * @param args        每一个arg是json格式的
     * @description: 对方法进行调用，会要求调用的类有无参构造
     * 会抛出IllegalAccessException InstantiationException InvocationTargetException
     * @return: java.lang.Object
     * @author: 杜科
     * @date: 2020/3/1
     */
    public Object invoke(Class serviceImpl, Method method, Object[] args) throws IllegalAccessException,
            InstantiationException, InvocationTargetException {
        Object o = serviceObjects.get(serviceImpl.getName());
        /**
         * double-check使得只创建一个实例，而ConcurrentHashMap做不到
         */
        if (o == null) {
            /**
             * 先尝试从spring容器中获取，因为如果当前环境是spring，直接调用newInstance生成实例的话，
             * 很可能会造成service中的mapper属性丢失，进而发生空指针异常。
             */
            if (RPCServer.enableSpring == 1) o = TrivialSpringUtil.getBean(serviceImpl);
            synchronized (serviceObjects) {
                if (o == null) {
                    o = serviceImpl.newInstance();
                    serviceObjects.put(serviceImpl.getName(), o);
                }
            }
        }
        return method.invoke(o, args);
    }

    /**
     * @description: 根据原参数类型，将json格式化的参数恢复原状
     * @param args json格式化的参数s
     * @param argTypes 原参数类型
     * @return: java.lang.Object[] 原参数
     * @author: 杜科
     * @date: 2020/7/28
     */
    private Object[] resumeArgs(Object[] args,String argTypes) throws ClassNotFoundException {
        argTypes=argTypes.substring(argTypes.indexOf(":")+1);
        String[] types = argTypes.split(" ");
        for(int i=0;i<args.length;i++){
            Class<?> clazz = Class.forName(types[i]);
            args[i]=JSON.parseObject((String) args[i], clazz);
        }
        return args;
    }

    /**
     * @param clientMessage 客户端发来的信息
     * @description: 对客户端发来的信息，进行方法用，返回结果
     * 会抛出ClassNotFoundException IllegalAccessException
     * InvocationTargetException InstantiationException MethodNotFoundException
     * @return: java.lang.Object
     * @author: 杜科
     * @date: 2020/3/1
     */
    public Object handle(ClientMessage clientMessage) throws ClassNotFoundException, IllegalAccessException,
            InvocationTargetException, InstantiationException, MethodNotFoundException {
        String className = packageName + clientMessage.getClassName();
        Object[] args = clientMessage.getArgs();
        String methodName = clientMessage.getMethodName();
        Class serviceImpl = findClass(className);
        Method method = findMethod(serviceImpl, methodName, args, clientMessage.getArgTypes());
//        args = resumeArgs(args,clientMessage.getArgTypes());
        Object result = invoke(serviceImpl, method, args);
        return result;
    }

}
