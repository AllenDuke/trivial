package com.github.AllenDuke.util;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * @author 杜科
 * @description Json编码器
 * 为原来的json数据添加一个消息头，int类型，表示json数据的大小
 * @contact AllenDuke@163.com
 * @date 2020/7/28
 */
public class JsonEncoder extends MessageToByteEncoder<Object> {

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) throws Exception {
        if(!(msg instanceof String)) return;//这里只针对json字符串

        byte[] body = ((String) msg).getBytes();
        int dataLength = body.length;
        out.writeInt(dataLength);
        //todo 对json再进行编码，例如对每一个byte ^dataLength
        out.writeBytes(body);
    }
}
