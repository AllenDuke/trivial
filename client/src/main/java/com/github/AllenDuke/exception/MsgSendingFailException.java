package com.github.AllenDuke.exception;

/**
 * @author 杜科
 * @description
 * @contact AllenDuke@163.com
 * @date 2020/4/4
 */
public class MsgSendingFailException extends RuntimeException {

    public MsgSendingFailException(){super();}

    public MsgSendingFailException(String s){super(s);}
}
