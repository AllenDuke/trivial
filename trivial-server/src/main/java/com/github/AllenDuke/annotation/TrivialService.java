package com.github.AllenDuke.annotation;

import java.lang.annotation.*;

/**
 * @author 杜科
 * @description 作用在类上，代表该类将会注册到zookeeper上，该类下的public方法可以被远程主机调用
 * @contact AllenDuke@163.com
 * @date 2020/8/18
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface TrivialService {

    boolean open() default true; /* 默认服务打开 */

    double version() default 1.0; /* 默认版本为 1.0 */
}
