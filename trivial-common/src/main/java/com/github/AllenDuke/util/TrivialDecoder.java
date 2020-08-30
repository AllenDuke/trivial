package com.github.AllenDuke.util;

import com.alibaba.fastjson.JSONObject;
import com.github.AllenDuke.dto.ClientMessage;
import com.github.AllenDuke.dto.ServerMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * @author 杜科
 * @description 对ClientMessage和ServerMessage进行反序列化
 * @contact AllenDuke@163.com
 * @date 2020/7/28
 */
@Slf4j
public class TrivialDecoder extends ByteToMessageDecoder {

    /* 消息头：发送端写的是一个int，占用4字节。表示接下数据的长度 */
    private final static int HEAD_LENGTH = 4;

    /* 需要解码的信息的类型，因为从网络接收到的是字节序列，而这序列中没有表明类型，所以只能提前固定 */
    private Class clazz;

    public TrivialDecoder(Class clazz) {
        this.clazz = clazz;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {

        /* 4字节的头部也没有，那么直接返回 */
        if (in.readableBytes() < HEAD_LENGTH) return;

        /* 标记一下当前的readIndex的位置 */
        in.markReaderIndex();

        /* 读取数据长度 */
        int dataLength = in.readInt();

        /* 我们读到的消息体长度为0，这是不应该出现的情况，这里出现这情况，关闭连接。 */
        if (dataLength < 0) {
            log.error("解码错误！消息体长度为："+dataLength+" 即将关闭连接！");
            ctx.close();
        }

        /* 如果数据长度不足 */
        if (in.readableBytes() < dataLength) {
            in.resetReaderIndex();
            return;
        }

        /* 将byte数据转化为我们需要的对象。 */
        if (clazz == ClientMessage.class) {
            try {
                ClientMessage clientMessage = convertToClientMessage(in,dataLength);
                out.add(clientMessage);
            } catch (Exception e) {
                log.error("解析异常，放弃本次解析任务，即将通知客户端", e);
                ServerMessage serverMessage = new ServerMessage(0, false, "服务器解析异常");
                ctx.writeAndFlush(serverMessage);
//                recordInvokeException(ctx,e);
            }finally {
                return;
            }

        }

        if (clazz == ServerMessage.class) {
            ServerMessage serverMessage = convertToServerMessage(in,dataLength);
            out.add(serverMessage);
            return;
        }

    }

    /* 读出一条ServerMessage */
    private ServerMessage convertToServerMessage(ByteBuf in,int dataLength) {
        long rpcId = in.readLong();
        boolean isSucceed=true;
        if(rpcId<0){//最高位为1，即是负数
            isSucceed=false;
            rpcId-=0x8000000000000000L;
        }

        int resultLen=dataLength-8;
        byte[] tmp = new byte[resultLen];
        in.readBytes(tmp);

        /**
         * 这里先把它转换为String，因为在真正将它转换时，会知道它的类型。
         * 当然这里也可以要求服务端把参数类型也发过来，然后直接在这里转换，但是没必要，我们要尽量减少网络的传输。
         * 而ClientMessage中的参数类型是必要的，是方法签名的一部分
         */
        ServerMessage message = new ServerMessage(rpcId, isSucceed, new String(tmp));
//        System.out.println("解码了一条ServerMessage：" + message);
        return message;

    }

    /* 从中读出一条ClientMessage */
    private ClientMessage convertToClientMessage(ByteBuf in,int dataLenth) throws ClassNotFoundException {
        byte[] tmp = null;

        long rpcId = in.readLong();

        short classNameLen = in.readShort();
        tmp=new byte[classNameLen];
        in.readBytes(tmp);
        String className = new String(tmp);

        short methodNameLen = in.readShort();
        tmp=new byte[methodNameLen];
        in.readBytes(tmp);
        String methodName =  new String(tmp);

//        short argTypesLen = in.readShort();
//        tmp=new byte[argTypesLen];
//        in.readBytes(tmp);
//        String argTypes = new String(tmp);

//        int argsLen = dataLenth-8-2-classNameLen-2-methodNameLen-2-argTypesLen;
        int argsLen = dataLenth-8-2-classNameLen-2-methodNameLen;
        tmp=new byte[argsLen];
        in.readBytes(tmp);
        Object[] args =JSONObject.parseObject(new String(tmp), Object[].class);

//        ClientMessage message = new ClientMessage(className, methodName, args, argTypes);
        ClientMessage message = new ClientMessage(className, methodName, args, null);
        message.setRpcId(rpcId);
//        System.out.println("解码了一条ClientMessage：" + message);
        return message;

    }

}
