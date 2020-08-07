package com.github.AllenDuke.exception;

/**
 * @author 杜科
 * @description 参数缺失异常
 * @contact AllenDuke@163.com
 * @since 2020/3/1
 */
public class ArgNotFoundExecption extends RuntimeException {

    public ArgNotFoundExecption(){super();}

    public ArgNotFoundExecption(String s){super(s);}
}
