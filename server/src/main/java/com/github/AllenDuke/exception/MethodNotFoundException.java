package com.github.AllenDuke.exception;

/**
 * @author 杜科
 * @description 方法缺失异常
 * @contact AllenDuke@163.com
 * @since 2020/3/1
 */
public class MethodNotFoundException extends RuntimeException{

    public MethodNotFoundException(){}

    public MethodNotFoundException(String s){super(s);}
}
