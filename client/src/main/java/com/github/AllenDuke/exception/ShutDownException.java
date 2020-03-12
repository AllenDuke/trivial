package com.github.AllenDuke.exception;

/**
 * @author 杜科
 * @description RPCClient结束异常
 * @contact AllenDuke@163.com
 * @date 2020/3/12
 */
public class ShutDownException extends RuntimeException{

    public ShutDownException(){super();}

    public ShutDownException(String s){super(s);}
}
