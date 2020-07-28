package com.github.AllenDuke.util;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * @author 杜科
 * @description json解码器，用来对json数据进行解码
 * 当json数据比较复杂时，单纯以‘}’作结尾符号就行不通了
 * @contact AllenDuke@163.com
 * @date 2020/7/28
 */
@Slf4j
public class JsonDecoder extends ByteToMessageDecoder {

    // 消息头：发送端写的是一个int，占用4字节。
    private final static int HEAD_LENGTH = 4;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        //
        if (in.readableBytes() < HEAD_LENGTH) {
            return;
        }

        // 标记一下当前的readIndex的位置
        in.markReaderIndex();

        // 读取数据长度
        int dataLength = in.readInt();

        // 我们读到的消息体长度为0，这是不应该出现的情况，这里出现这情况，关闭连接。
        if (dataLength < 0) {
            log.error("json 解码错误！消息体长度为："+dataLength+" 即将关闭连接！");
            ctx.close();
        }

        /**
         * 读到的消息体长度如果小于我们传送过来的消息长度，则resetReaderIndex.
         * 这个配合markReaderIndex使用的。
         * 把readIndex重置到mark的地方
         */
        if (in.readableBytes() < dataLength) {
            in.resetReaderIndex();
            return;
        }

        // 将缓冲区的数据读到字节数组
        byte[] body = new byte[dataLength];
        in.readBytes(body);
        //将byte数据转化为我们需要的对象。
        String msg = convertToObj(body);
        out.add(msg);
    }

    private String convertToObj(byte[] body) {
        return new String(body, 0, body.length);
    }

}
