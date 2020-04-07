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
 * A decoder that splits the received {@link ByteBuf}s by one or more
 * delimiters.  It is particularly useful for decoding the frames which ends
 * with a delimiter such as {@link Delimiters#nulDelimiter() NUL} or
 * {@linkplain Delimiters#lineDelimiter() newline characters}.
 *
 * <h3>Predefined delimiters</h3>
 * <p>
 * {@link Delimiters} defines frequently used delimiters for convenience' sake.
 *
 * <h3>Specifying more than one delimiter</h3>
 * <p>
 * {@link DelimiterBasedFrameDecoder} allows you to specify more than one
 * delimiter.  If more than one delimiter is found in the buffer, it chooses
 * the delimiter which produces the shortest frame.  For example, if you have
 * the following data in the buffer:
 * <pre>
 * +--------------+
 * | ABC\nDEF\r\n |
 * +--------------+
 * </pre>
 * a {@link DelimiterBasedFrameDecoder}({@link Delimiters#lineDelimiter() Delimiters.lineDelimiter()})
 * will choose {@code '\n'} as the first delimiter and produce two frames:
 * <pre>
 * +-----+-----+
 * | ABC | DEF |
 * +-----+-----+
 * </pre>
 * rather than incorrectly choosing {@code '\r\n'} as the first delimiter:
 * <pre>
 * +----------+
 * | ABC\nDEF |
 * +----------+
 * </pre>
 */
public class DelimiterBasedFrameDecoder extends ByteToMessageDecoder {

    private final ByteBuf[] delimiters;
    private final int maxFrameLength;
    private final boolean stripDelimiter;
    private final boolean failFast;
    private boolean discardingTooLongFrame;
    private int tooLongFrameLength;
    /** Set only when decoding with "\n" and "\r\n" as the delimiter.  */
    private final LineBasedFrameDecoder lineBasedDecoder;

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength  the maximum length of the decoded frame.
     *                        A {@link TooLongFrameException} is thrown if
     *                        the length of the frame exceeds this value.
     * @param delimiter  the delimiter
     */
    //这里参数 maxFrameLength 代表最大长度, delimiters 是个可变参数, 可以说可以支持多个分隔符进行解码。我们进入
    // decode()方法:
    public DelimiterBasedFrameDecoder(int maxFrameLength, ByteBuf delimiter) {
        this(maxFrameLength, true, delimiter);
    }

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength  the maximum length of the decoded frame.
     *                        A {@link TooLongFrameException} is thrown if
     *                        the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param delimiter  the delimiter
     */
    public DelimiterBasedFrameDecoder(
            int maxFrameLength, boolean stripDelimiter, ByteBuf delimiter) {
        this(maxFrameLength, stripDelimiter, true, delimiter);
    }

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength  the maximum length of the decoded frame.
     *                        A {@link TooLongFrameException} is thrown if
     *                        the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param failFast  If <tt>true</tt>, a {@link TooLongFrameException} is
     *                  thrown as soon as the decoder notices the length of the
     *                  frame will exceed <tt>maxFrameLength</tt> regardless of
     *                  whether the entire frame has been read.
     *                  If <tt>false</tt>, a {@link TooLongFrameException} is
     *                  thrown after the entire frame that exceeds
     *                  <tt>maxFrameLength</tt> has been read.
     * @param delimiter  the delimiter
     */
    public DelimiterBasedFrameDecoder(
            int maxFrameLength, boolean stripDelimiter, boolean failFast,
            ByteBuf delimiter) {
        this(maxFrameLength, stripDelimiter, failFast, new ByteBuf[] {
                delimiter.slice(delimiter.readerIndex(), delimiter.readableBytes())});
    }

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength  the maximum length of the decoded frame.
     *                        A {@link TooLongFrameException} is thrown if
     *                        the length of the frame exceeds this value.
     * @param delimiters  the delimiters
     */
    public DelimiterBasedFrameDecoder(int maxFrameLength, ByteBuf... delimiters) {
        this(maxFrameLength, true, delimiters);
    }

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength  the maximum length of the decoded frame.
     *                        A {@link TooLongFrameException} is thrown if
     *                        the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param delimiters  the delimiters
     */
    public DelimiterBasedFrameDecoder(
            int maxFrameLength, boolean stripDelimiter, ByteBuf... delimiters) {
        this(maxFrameLength, stripDelimiter, true, delimiters);
    }

    /**
     * Creates a new instance.
     *
     * @param maxFrameLength  the maximum length of the decoded frame.
     *                        A {@link TooLongFrameException} is thrown if
     *                        the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param failFast  If <tt>true</tt>, a {@link TooLongFrameException} is
     *                  thrown as soon as the decoder notices the length of the
     *                  frame will exceed <tt>maxFrameLength</tt> regardless of
     *                  whether the entire frame has been read.
     *                  If <tt>false</tt>, a {@link TooLongFrameException} is
     *                  thrown after the entire frame that exceeds
     *                  <tt>maxFrameLength</tt> has been read.
     * @param delimiters  the delimiters
     */
    public DelimiterBasedFrameDecoder(
            int maxFrameLength, boolean stripDelimiter, boolean failFast, ByteBuf... delimiters) {
        validateMaxFrameLength(maxFrameLength);
        //代码省略
        if (delimiters == null) {
            throw new NullPointerException("delimiters");
        }
        if (delimiters.length == 0) {
            throw new IllegalArgumentException("empty delimiters");
        }
        //如果是基于行的分隔
        //这
        // 里 isLineBased(delimiters)会判断是否是基于行的分隔, 跟到 isLineBased()方法中：
        if (isLineBased(delimiters) && !isSubclass()) {
            //初始化行处理器
            lineBasedDecoder = new LineBasedFrameDecoder(maxFrameLength, stripDelimiter, failFast);
            this.delimiters = null;
        } else {
            this.delimiters = new ByteBuf[delimiters.length];
            for (int i = 0; i < delimiters.length; i ++) {
                ByteBuf d = delimiters[i];
                validateDelimiter(d);
                this.delimiters[i] = d.slice(d.readerIndex(), d.readableBytes());
            }
            lineBasedDecoder = null;
        }
        this.maxFrameLength = maxFrameLength;
        this.stripDelimiter = stripDelimiter;
        this.failFast = failFast;
    }

    /** Returns true if the delimiters are "\n" and "\r\n".  */

    //首先判断长度等于 2, 直接返回 false。然后拿到第一个分隔符 a 和第二个分隔符 b, 然后判断 a 的第一个分隔符是不是
    // \r, a 的第二个分隔符是不是\n, b 的第一个分隔符是不是\n, 如果都为 true, 则条件成立。我们回到 decode()方法中, 看
    // 第 2 步, 找到最小长度的分隔符。这里最小长度的分隔符, 意思就是从读指针开始, 找到最近的分隔符
    private static boolean isLineBased(final ByteBuf[] delimiters) {
        //分隔符长度不为 2
        if (delimiters.length != 2) {
            return false;
        }
        //拿到第一个分隔符
        ByteBuf a = delimiters[0];
        //拿到第二个分隔符
        ByteBuf b = delimiters[1];
        if (a.capacity() < b.capacity()) {
            a = delimiters[1];
            b = delimiters[0];
        }
        //确保 a 是/r/n 分隔符, 确保 b 是/n 分隔符
        return a.capacity() == 2 && b.capacity() == 1
                && a.getByte(0) == '\r' && a.getByte(1) == '\n'
                && b.getByte(0) == '\n';
    }

    /**
     * Return {@code true} if the current instance is a subclass of DelimiterBasedFrameDecoder
     */
    private boolean isSubclass() {
        return getClass() != DelimiterBasedFrameDecoder.class;
    }

    @Override
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
    //这
    // 里的方法也比较长, 这里也通过拆分进行剖析：1、行处理器；2、找到最小长度分隔符；3、解码。
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
        //行处理器(1)
        //这个成员变量, 会在分隔符
        // 是\n 和\r\n 的时候进行初始化。我们看初始化该属性的构造方法：
        if (lineBasedDecoder != null) {
            return lineBasedDecoder.decode(ctx, buffer);
        }
        // Try all delimiters and choose the delimiter which yields the shortest frame.
        int minFrameLength = Integer.MAX_VALUE;
        ByteBuf minDelim = null;
        //找到最小长度的分隔符(2)
        //这里会遍历所有的分隔符, 然后找到每个分隔符到读指针到数据包长度。然后通过 if 判断, 找到长度最小的数据包的长
        // 度, 然后保存当前数据包的的分隔符, 如下图：
        for (ByteBuf delim: delimiters) {
            //每个分隔符分隔的数据包长度
            int frameLength = indexOf(buffer, delim);
            if (frameLength >= 0 && frameLength < minFrameLength) {
                minFrameLength = frameLength;
                minDelim = delim;
            }
        }
        //解码(3)
        //已经找到分隔符
        //表示已经找到最小长度
        // 分隔符, 我们继续看 if 块中的逻辑：
        if (minDelim != null) {
            int minDelimLength = minDelim.capacity();
            ByteBuf frame;
            //当前分隔符否处于丢弃模式
            //if (discardingTooLongFrame) 表示当前是否处于非丢弃模式
            //如果是丢弃模式, 则进入 if 块
            //因为第一个不是丢弃模式,
            // 所以这里先分析 if 块后面的逻辑
            if (discardingTooLongFrame) {
                // We've just finished discarding a very large frame.
                // Go back to the initial state.
                //首先设置为非丢弃模式
                //设 置 为 false, 标 记 非 丢 弃 模 式 ，
                discardingTooLongFrame = false;
                //丢弃
                //将数据包+分隔符长度的字节数跳过, 也就是进行丢弃,
                buffer.skipBytes(minFrameLength + minDelimLength);

                int tooLongFrameLength = this.tooLongFrameLength;
                this.tooLongFrameLength = 0;
                if (!failFast) {
                    fail(tooLongFrameLength);
                }
                return null;
            }
            //处于非丢弃模式
            //当前找到的数据包, 大于允许的数据包
            //if (minFrameLength > maxFrameLength) 这里是判断当前找到的数据包长度大于最
            // 大 长 度 , 这 里 的 最 大 长 度 使 我 们 创 建 解 码 器 的 时 候 设 置 的 , 如 果 超 过 了 最 大 长 度 , 就 通 过
            // buffer.skipBytes(minFrameLength + minDelimLength) 方式, 跳过数据包+分隔符的长度, 也就是将这部分数据进行
            // 完全丢弃。
            if (minFrameLength > maxFrameLength) {
                // Discard read frame.
                //当前数据包+最小分隔符长度 全部丢弃
                buffer.skipBytes(minFrameLength + minDelimLength);
                //传递异常事件
                fail(minFrameLength);
                return null;
            }
            //如果是正常的长度
            //解析出来的数据包是否忽略分隔符
            //则通过 if (stripDelimiter) 判断解析的出来的数据包是否包含分
            // 隔符, 如果不包含分隔符, 则截取数据包的长度之后, 跳过分隔符。
            if (stripDelimiter) {
                //如果不包含分隔符
                //截取
                frame = buffer.readRetainedSlice(minFrameLength);
                //跳过分隔符
                buffer.skipBytes(minDelimLength);
            } else {
                //截取包含分隔符的长度
                frame = buffer.readRetainedSlice(minFrameLength + minDelimLength);
            }

            return frame;
        } else {
            //如果没有找到分隔符
            //非丢弃模式
            if (!discardingTooLongFrame) {
                //可读字节大于允许的解析出来的长度
                //可读字节数是否大于最大允许的长度,
                if (buffer.readableBytes() > maxFrameLength) {
                    // Discard the content of the buffer until a delimiter is found.
                    //将这个长度记录下
                    tooLongFrameLength = buffer.readableBytes();
                    //跳过这段长度
                    //将累计器中所有的可读字节进行丢弃，
                    buffer.skipBytes(buffer.readableBytes());
                    //标记当前处于丢弃状态
                    //最后将 discardingTooLongFrame 设置
                    // 为 true, 也就是丢弃模式,
                    discardingTooLongFrame = true;
                    if (failFast) {
                        fail(tooLongFrameLength);
                    }
                }
            } else {
                //也就是当前处于丢弃模式, 则
                // 追加 tooLongFrameLength 也就是丢弃的字节数的长度, 并通过 buffer.skipBytes(buffer.readableBytes()) 将所有的字
                // 节继续进行丢弃。以上就是分隔符解码器的相关逻辑
                // Still discarding the buffer since a delimiter is not found.
                tooLongFrameLength += buffer.readableBytes();
                buffer.skipBytes(buffer.readableBytes());
            }
            return null;
        }
    }

    private void fail(long frameLength) {
        if (frameLength > 0) {
            throw new TooLongFrameException(
                            "frame length exceeds " + maxFrameLength +
                            ": " + frameLength + " - discarded");
        } else {
            throw new TooLongFrameException(
                            "frame length exceeds " + maxFrameLength +
                            " - discarding");
        }
    }

    /**
     * Returns the number of bytes between the readerIndex of the haystack and
     * the first needle found in the haystack.  -1 is returned if no needle is
     * found in the haystack.
     */
    private static int indexOf(ByteBuf haystack, ByteBuf needle) {
        for (int i = haystack.readerIndex(); i < haystack.writerIndex(); i ++) {
            int haystackIndex = i;
            int needleIndex;
            for (needleIndex = 0; needleIndex < needle.capacity(); needleIndex ++) {
                if (haystack.getByte(haystackIndex) != needle.getByte(needleIndex)) {
                    break;
                } else {
                    haystackIndex ++;
                    if (haystackIndex == haystack.writerIndex() &&
                        needleIndex != needle.capacity() - 1) {
                        return -1;
                    }
                }
            }

            if (needleIndex == needle.capacity()) {
                // Found the needle from the haystack!
                return i - haystack.readerIndex();
            }
        }
        return -1;
    }

    private static void validateDelimiter(ByteBuf delimiter) {
        if (delimiter == null) {
            throw new NullPointerException("delimiter");
        }
        if (!delimiter.isReadable()) {
            throw new IllegalArgumentException("empty delimiter");
        }
    }

    private static void validateMaxFrameLength(int maxFrameLength) {
        if (maxFrameLength <= 0) {
            throw new IllegalArgumentException(
                    "maxFrameLength must be a positive integer: " +
                    maxFrameLength);
        }
    }
}
