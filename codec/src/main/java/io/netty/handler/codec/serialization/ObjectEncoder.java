/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.serialization;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

/**
 * An encoder which serializes a Java object into a {@link ByteBuf}.
 * <p>
 * Please note that the serialized form this encoder produces is not
 * compatible with the standard {@link ObjectInputStream}.  Please use
 * {@link ObjectDecoder} or {@link ObjectDecoderInputStream} to ensure the
 * interoperability with this encoder.
 */
@Sharable
//如果要使用 Java 序列化，对象必须实现 Serializable 接口，因此，它的泛型类型为 Serializable
public class ObjectEncoder extends MessageToByteEncoder<Serializable> {
    private static final byte[] LENGTH_PLACEHOLDER = new byte[4];

    @Override
    protected void encode(ChannelHandlerContext ctx, Serializable msg, ByteBuf out) throws Exception {
        int startIdx = out.writerIndex();
        //首先创建 ByteBufOutputStream 和 ObjectOutputStream，用于将 Object 对象序列化到 ByteBuf 中，值得注意的是在
        // writeObject 之前需要先将长度字段（4 个字节）预留，用于后续长度字段的更新。
        ByteBufOutputStream bout = new ByteBufOutputStream(out);
        //依次写入长度占位符（4 字节）、序列化之后的 Object 对象
        bout.write(LENGTH_PLACEHOLDER);
        ObjectOutputStream oout = new CompactObjectOutputStream(bout);
        //序列化之后的 Object 对象
        oout.writeObject(msg);
        oout.flush();
        oout.close();
        //之后根据 ByteBuf 的 writeIndex 计算序列化之后的码流
        // 长度
        int endIdx = out.writerIndex();
        //最后调用 ByteBuf 的 setInt(int index, int value)更新长度占位符为实际的码流长度。
        out.setInt(startIdx, endIdx - startIdx - 4);

    //    有个细节需要注意，更新码流长度字段使用了 setInt 方法而不是 writeInt，原因就是 setInt 方法只更新内容，并不修改
        // readerIndex 和 writerIndex。
    }
}
