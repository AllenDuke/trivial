package com.github.AllenDuke.exception;

/**
 * @author 杜科
 * @description 连接空闲异常
 * @contact AllenDuke@163.com
 * @since 2020/3/11
 */
public class ConnectionIdleException extends RuntimeException{
    public ConnectionIdleException(){super();}

    public ConnectionIdleException(String s){super(s);}
}
