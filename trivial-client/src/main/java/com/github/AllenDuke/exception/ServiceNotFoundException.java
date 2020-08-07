package com.github.AllenDuke.exception;

/**
 * @author 杜科
 * @description
 * @contact AllenDuke@163.com
 * @date 2020/3/12
 */
public class ServiceNotFoundException extends RuntimeException {

    public ServiceNotFoundException(){super();}

    public ServiceNotFoundException(String s){super(s);}
}
