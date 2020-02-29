package com.github.AllenDuke.server;

/**
 * @author 杜科
 * @description 计算器服务，因为用的fastjson，Number类的转换规则按fastjson
 * @contact AllenDuke@163.com
 * @since 2020/2/11
 */
public interface Calculator {

    Integer add(Integer a,  String b);

    int multipy(Integer a, Integer b);
}
