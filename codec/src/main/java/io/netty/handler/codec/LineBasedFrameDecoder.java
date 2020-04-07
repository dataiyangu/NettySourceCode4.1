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
import io.netty.util.ByteProcessor;

import java.util.List;

/**
 * A decoder that splits the received {@link ByteBuf}s on line endings.
 * <p>
 * Both {@code "\n"} and {@code "\r\n"} are handled.
 * For a more general delimiter-based decoder, see {@link DelimiterBasedFrameDecoder}.
 */
public class LineBasedFrameDecoder extends ByteToMessageDecoder {

    /** Maximum length of a frame we're willing to decode.  */
    //数据包的最大长度, 超过该长度会进行丢弃模式
    private final int maxLength;
    /** Whether or not to throw an exception as soon as we exceed maxLength. */
    //超出最大长度是否要抛出异常
    private final boolean failFast;
    //最终解析的数据包是否带有换行符
    private final boolean stripDelimiter;

    /** True if we're discarding input because we're already over maxLength.  */
    //为 true 说明当前解码过程为丢弃模式
    private boolean discarding;
    //丢弃了多少字节
    private int discardedBytes;

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     */
    public LineBasedFrameDecoder(final int maxLength) {
        this(maxLength, true, false);
    }

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param failFast  If <tt>true</tt>, a {@link TooLongFrameException} is
     *                  thrown as soon as the decoder notices the length of the
     *                  frame will exceed <tt>maxFrameLength</tt> regardless of
     *                  whether the entire frame has been read.
     *                  If <tt>false</tt>, a {@link TooLongFrameException} is
     *                  thrown after the entire frame that exceeds
     *                  <tt>maxFrameLength</tt> has been read.
     */
    public LineBasedFrameDecoder(final int maxLength, final boolean stripDelimiter, final boolean failFast) {
        this.maxLength = maxLength;
        this.failFast = failFast;
        this.stripDelimiter = stripDelimiter;
    }

    @Override
    //这里的 decode()方法调用重载的 decode()方法, 并将解码后的内容放到 out 集合中。我们跟到重载的 decode()方法中：
    protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Object decoded = decode(ctx, in);
        if (decoded != null) {
            out.add(decoded);
        }
    }

    /**
     * Create a frame out of the {@link ByteBuf} and return it.
     *
     * @param   ctx             the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param   buffer          the {@link ByteBuf} from which to read data
     * @return  frame           the {@link ByteBuf} which represent the frame or {@code null} if no frame could
     *                          be created.
     */
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
        //找这行的结尾
        final int eol = findEndOfLine(buffer);
        //if (!discarding) 判断是否为非丢弃模式  默认是就是非丢弃模式  所以进入 if
        if (!discarding) {
            // if (eol >= 0) 如果找到了换行符, 我们看非丢弃模式下找到换行符的相关逻辑：
            if (eol >= 0) {
                final ByteBuf frame;
                //计算从换行符到可读字节之间的长度
                ////首先获得换行符到可读字节之间的长度
                final int length = eol - buffer.readerIndex();
                //拿到分隔符长度, 如果是\r\n 结尾, 分隔符长度为 2
                //然后拿到换行符的长度, 如果是\n 结尾, 那么长度为1, 如果是\r 结尾, 长度为2。
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;
                //如果长度大于最大长度
                // if (length > maxLength) 带表如果长度超过最大长度, 则直接通过 readerIndex(eol + delimLength) 这种方式, 将
                // 读指针指向换行符之后的字节, 说明换行符之前的字节需要完全丢弃。
                if (length > maxLength) {
                    //指向换行符之后的可读字节(这段数据完全丢弃)
                    buffer.readerIndex(eol + delimLength);
                    //传播异常事件
                    //丢弃之后通过 fail 方法传播异常, 并返回 null。
                    fail(ctx, length);
                    return null;
                }
                //如果这次解析的数据是有效的
                //分隔符是否算在完整数据包里
                //true 为丢弃分隔符

                //走到下一步, 说明解析出来的数据长度没有超过最大长度,
                // 说明是有效数据包。

                //表示是否要将分隔符放在完整数据包里面, 如果是 true, 则说明要丢弃分隔符,
                // 然后截取有效长度, 并跳过分隔符长度，将包含分隔符进行截取
                if (stripDelimiter) {
                    //截取有效长度
                    frame = buffer.readRetainedSlice(length);
                    //跳过分隔符的字节
                    buffer.skipBytes(delimLength);
                } else {
                    //包含分隔符
                    frame = buffer.readRetainedSlice(length + delimLength);
                }

                return frame;
            } else {
                //如果没找到分隔符(非丢弃模式)
                //可读字节长度
                //通过 final int length = buffer.readableBytes() 获取所有的可读字节数。
                final int length = buffer.readableBytes();
                //如果朝超过能解析的最大长度
                if (length > maxLength) {
                    //将当前长度标记为可丢弃的
                    //然后判断可读字节数是否超过了最大值,
                    // 如果超过最大值, 则属性 discardedBytes 标记为这个长度, 代表这段内容要进行丢弃
                    discardedBytes = length;
                    //直接将读指针移动到写指针
                    //buffer.readerIndex(buffer.writerIndex()) 这里直接将读指针移动到写指针
                    buffer.readerIndex(buffer.writerIndex());
                    //标记为丢弃模式
                    //并且将 discarding 设置为 true就是丢弃模
                    // 式。
                    discarding = true;
                    //超过最大长度抛出异常
                    if (failFast) {
                        fail(ctx, "over " + discardedBytes);
                    }
                }
                //没有超过, 则直接返回
                //如果可读字节没有超过最大长度, 则返回 null, 表示什么都没解析出来, 等着下次解析。
                //因为没有换行符，不进行解析
                return null;
            }
        } else {
            //丢弃模式
            if (eol >= 0) {
                //如果找到换行符, 则需要将换行符之前的数据全部丢弃掉。
                //找到分隔符
                //当前丢弃的字节(前面已经丢弃的+现在丢弃的位置-写指针)
                //这里获得丢弃的字节总数,也就是之前丢弃的字节数+
                // 现在需要丢弃的字节数
                final int length = discardedBytes + eol - buffer.readerIndex();
                //当前换行符长度为多少
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;
                //读指针直接移到换行符+换行符的长度
                //将读指针移动到换行符之后的位置
                buffer.readerIndex(eol + delimLength);
                //当前丢弃的字节为 0
                discardedBytes = 0;
                //设置为未丢弃模式
                //将 discarding 设置为 false,
                discarding = false;
                //丢弃完字节之后触发异常
                if (!failFast) {
                    fail(ctx, length);
                }
            } else {

                //这里做的事情非常简单, 就是累计丢弃的字节数, 并将读指针移动到写指针, 也就是将数据全部丢弃。最后在丢弃模式
                // 下, decode()方法返回 null, 代表本次没有解析出任何数据。以上就是行解码器的相关逻辑。

                //累计已丢弃的字节个数+当前可读的长度
                discardedBytes += buffer.readableBytes();
                //移动
                buffer.readerIndex(buffer.writerIndex());
            }
            return null;
        }
    }

    private void fail(final ChannelHandlerContext ctx, int length) {
        fail(ctx, String.valueOf(length));
    }

    private void fail(final ChannelHandlerContext ctx, String length) {
        ctx.fireExceptionCaught(
                new TooLongFrameException(
                        "frame length (" + length + ") exceeds the allowed maximum (" + maxLength + ')'));
    }

    /**
     * Returns the index in the buffer of the end of line found.
     * Returns -1 if no end of line was found in the buffer.
     */
    //从
    // 上面代码看到，通过一个 forEachByte()方法找\n 这个字节, 如果找到了, 并且前面是\r, 则返回\r 的索引, 否则返回
    // \n 的索引。回到重载的 decode()方法
    private static int findEndOfLine(final ByteBuf buffer) {
        //找到/n 这个字节
        int i = buffer.forEachByte(ByteProcessor.FIND_LF);
        //如果找到了, 并且前面的字符是-r, 则指向/r 字节
        if (i > 0 && buffer.getByte(i - 1) == '\r') {
            i--;
        }
        return i;
    }
}
