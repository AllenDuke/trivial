package com.github.AllenDuke.annotation;

import java.lang.annotation.*;

/**
 * @author 杜科
 * @description
 * @contact AllenDuke@163.com
 * @date 2020/8/18
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface TrivialScan {

    String path() default ""; /* 将会扫描path下的类，将有@TrivialService的类注册到zookeeper */
}
