package com.github.AllenDuke.util;

import com.alibaba.fastjson.JSON;
import com.github.AllenDuke.dto.ClientMessage;
import com.github.AllenDuke.dto.ServerMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * @author 杜科
 * @description 对ClientMessage和ServerMessage进行序列化
 * @contact AllenDuke@163.com
 * @date 2020/7/28
 */
public class TrivialEncoder extends MessageToByteEncoder<Object> {

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) throws Exception {
        if (msg instanceof ClientMessage) {
            ClientMessage message = (ClientMessage) msg;
            long rpcId = message.getRpcId();
            byte[] className = message.getClassName().getBytes();
            byte[] methodName = message.getMethodName().getBytes();
//            byte[] argTypes = message.getArgTypes().getBytes();
            byte[] args = JSON.toJSONString(message.getArgs()).getBytes();

//            out.writeInt(8+2+className.length+2+methodName.length+2+argTypes.length+args.length);
            out.writeInt(8+2+className.length+2+methodName.length+args.length);

            out.writeLong(rpcId);
            /**
             * 如果以分割符为边界，那么在java中一个char 同样占2字节
             */
            out.writeShort(className.length);//最大65535
            out.writeBytes(className);

            out.writeShort(methodName.length);
            out.writeBytes(methodName);

//            out.writeShort(argTypes.length);
//            out.writeBytes(argTypes);

            //最后一个的大小，服务端可以从上述推导得出
//            out.writeInt(args.length);
            out.writeBytes(args);
            return;
        }

        if (msg instanceof ServerMessage) {
            ServerMessage message = (ServerMessage) msg;
            long rpcId = message.getRpcId();
            boolean succeed = message.isSucceed();
            byte[] result = JSON.toJSONString(message.getReselut()).getBytes();

            out.writeInt(8+1+result.length);

            out.writeLong(rpcId);

            out.writeBoolean(succeed);

            //最后一个的大小，服务端可以从上述推导得出
//            out.writeInt(result.length);
            out.writeBytes(result);
            return;
        }
    }
}
