package com.github.AllenDuke.annotation;

import java.lang.annotation.*;

/**
 * @author 杜科
 * @description 作用于类上的注解，应该把它贴在主类上，类似springboot的@SpringbootApplication注解那样使用
 * @contact AllenDuke@163.com
 * @date 2020/8/18
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface TrivialScan {

    String path() default ""; /* 将会扫描path下的类，将有@TrivialService的类注册到zookeeper */
}
