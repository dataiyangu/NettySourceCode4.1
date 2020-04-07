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
package io.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.util.List;

/**
 * A decoder that splits the received {@link ByteBuf}s by the fixed number
 * of bytes. For example, if you received the following four fragmented packets:
 * <pre>
 * +---+----+------+----+
 * | A | BC | DEFG | HI |
 * +---+----+------+----+
 * </pre>
 * A {@link FixedLengthFrameDecoder}{@code (3)} will decode them into the
 * following three packets with the fixed length:
 * <pre>
 * +-----+-----+-----+
 * | ABC | DEF | GHI |
 * +-----+-----+-----+
 * </pre>
 */

//我
// 们看到 FixedLengthFrameDecoder 类继承了 ByteToMessageDecoder, 重写了 decode()方法，这个类只有一个属性
// 叫 frameLength, 并在构造方法中初始化了该属性。再看 decode()方法, 在 decode()方法中又调用了自身另一个重载
// 的 decode()方法进行解析, 解析出来之后将解析后的数据放在集合 out 中。再看重载的 decode()方法，重载的 decode()
// 方法中首先判断累加器的字节数是否小于固定长度, 如果小于固定长度则返回 null, 代表不是一个完整的数据包, 直接
// 返回 null。如果大于等于固定长度, 则直接从累加器中截取这个长度的数值 in.readRetainedSlice(frameLength) 会返回
// 一个新的截取后的 ByteBuf, 并将原来的累加器读指针后移 frameLength 个字节。如果累计器中还有数据, 则会通过
// ByteToMessageDecoder 中 callDecode()方法里 while 循环的方式, 继续进行解码。这样, 就是实现了固定长度的解码
// 工作。
public class FixedLengthFrameDecoder extends ByteToMessageDecoder {
    //长度大小
    private final int frameLength;

    /**
     * Creates a new instance.
     *
     * @param frameLength the length of the frame
     */
    public FixedLengthFrameDecoder(int frameLength) {
        if (frameLength <= 0) {
            throw new IllegalArgumentException(
                    "frameLength must be a positive integer: " + frameLength);
        }
        //保存当前 frameLength
        this.frameLength = frameLength;
    }

    @Override
    protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        //通过 ByteBuf 去解码.解码到对象之后添加到 out 上
        Object decoded = decode(ctx, in);
        if (decoded != null) {
            //将解析到 byteBuf 添加到对象里面
            out.add(decoded);
        }
    }

    /**
     * Create a frame out of the {@link ByteBuf} and return it.
     *
     * @param   ctx             the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param   in              the {@link ByteBuf} from which to read data
     * @return  frame           the {@link ByteBuf} which represent the frame or {@code null} if no frame could
     *                          be created.
     */
    protected Object decode(
            @SuppressWarnings("UnusedParameters") ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        ////字节是否小于这个固定长度

        //首先判断累加器的字节数是否小于固定长度,
        if (in.readableBytes() < frameLength) {
            //如果小于固定长度则返回 null  代表不是一个完整的数据包,
            return null;
        } else {
            //当前累加器中截取这个长度的数值
            //如果大于等于固定长度, 则直接从累加器中截取这个长度的数值 in.readRetainedSlice(frameLength) 会返回
            // 一个新的截取后的 ByteBuf, 并将原来的累加器读指针后移 frameLength 个字节
            return in.readRetainedSlice(frameLength);
        }
    }
}
//如果累计器中还有数据, 则会通过
// ByteToMessageDecoder 中 callDecode()方法里 while 循环的方式, 继续进行解码