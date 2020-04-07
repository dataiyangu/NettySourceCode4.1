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
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.util.internal.StringUtil;

import java.util.List;

/**
 * {@link ChannelInboundHandlerAdapter} which decodes bytes in a stream-like fashion from one {@link ByteBuf} to an
 * other Message type.
 *
 * For example here is an implementation which reads all readable bytes from
 * the input {@link ByteBuf} and create a new {@link ByteBuf}.
 *
 * <pre>
 *     public class SquareDecoder extends {@link ByteToMessageDecoder} {
 *         {@code @Override}
 *         public void decode({@link ChannelHandlerContext} ctx, {@link ByteBuf} in, List&lt;Object&gt; out)
 *                 throws {@link Exception} {
 *             out.add(in.readBytes(in.readableBytes()));
 *         }
 *     }
 * </pre>
 *
 * <h3>Frame detection</h3>
 * <p>
 * Generally frame detection should be handled earlier in the pipeline by adding a
 * {@link DelimiterBasedFrameDecoder}, {@link FixedLengthFrameDecoder}, {@link LengthFieldBasedFrameDecoder},
 * or {@link LineBasedFrameDecoder}.
 * <p>
 * If a custom frame decoder is required, then one needs to be careful when implementing
 * one with {@link ByteToMessageDecoder}. Ensure there are enough bytes in the buffer for a
 * complete frame by checking {@link ByteBuf#readableBytes()}. If there are not enough bytes
 * for a complete frame, return without modifying the reader index to allow more bytes to arrive.
 * <p>
 * To check for complete frames without modifying the reader index, use methods like {@link ByteBuf#getInt(int)}.
 * One <strong>MUST</strong> use the reader index when using methods like {@link ByteBuf#getInt(int)}.
 * For example calling <tt>in.getInt(0)</tt> is assuming the frame starts at the beginning of the buffer, which
 * is not always the case. Use <tt>in.getInt(in.readerIndex())</tt> instead.
 * <h3>Pitfalls</h3>
 * <p>
 * Be aware that sub-classes of {@link ByteToMessageDecoder} <strong>MUST NOT</strong>
 * annotated with {@link @Sharable}.
 * <p>
 * Some methods such as {@link ByteBuf#readBytes(int)} will cause a memory leak if the returned buffer
 * is not released or added to the <tt>out</tt> {@link List}. Use derived buffers like {@link ByteBuf#readSlice(int)}
 * to avoid leaking memory.
 */
public abstract class ByteToMessageDecoder extends ChannelInboundHandlerAdapter {

    /**
     * Cumulate {@link ByteBuf}s by merge them into one {@link ByteBuf}'s, using memory copies.
     */

    //这里创建了 Cumulator 类型的静态对象, 并重写了 cumulate()方法, 这个 cumulate()方法, 就是用于将 ByteBuf 进行拼
    // 接的方法。在方法中, 首先判断 cumulation 的写指针+in 的可读字节数是否超过了 cumulation 的最大长度, 如果超过
    // 了, 将对 cumulation 进行扩容, 如果没超过, 则将其赋值到局部变量 buffer 中。然后，将 in 的数据写到 buffer 中, 将
    // in 进行释放, 返回写入数据后的 ByteBuf。回到 channelRead()方法：
    public static final Cumulator MERGE_CUMULATOR = new Cumulator() {
        @Override
        // 这个 cumulate()方法, 就是用于将 ByteBuf 进行拼接的方法
        public ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
            ByteBuf buffer;
            ////不能到过最大内存
            //在方法中, 首先判断 cumulation 的写指针+in 的可读字节数是否超过了 cumulation 的最大长度,  如果没超过, 则将其赋值到局部变量 buffer 中。
            if (cumulation.writerIndex() > cumulation.maxCapacity() - in.readableBytes()
                    || cumulation.refCnt() > 1) {
                // Expand cumulation (by replace it) when either there is not more room in the buffer
                // or if the refCnt is greater then 1 which may happen when the user use slice().retain() or
                // duplicate().retain().
                //
                // See:
                // - https://github.com/netty/netty/issues/2327
                // - https://github.com/netty/netty/issues/1764
                //如果超过了, 将对 cumulation 进行扩容,
                buffer = expandCumulation(alloc, cumulation, in.readableBytes());
            } else {
                ///将当前数据 buffer
                // 如果没超过, 则将其赋值到局部变量 buffer 中
                buffer = cumulation;
            }
            buffer.writeBytes(in);
            in.release();
            return buffer;
        }
    };

    /**
     * Cumulate {@link ByteBuf}s by add them to a {@link CompositeByteBuf} and so do no memory copy whenever possible.
     * Be aware that {@link CompositeByteBuf} use a more complex indexing implementation so depending on your use-case
     * and the decoder implementation this may be slower then just use the {@link #MERGE_CUMULATOR}.
     */
    public static final Cumulator COMPOSITE_CUMULATOR = new Cumulator() {
        @Override
        public ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in) {
            ByteBuf buffer;
            if (cumulation.refCnt() > 1) {
                // Expand cumulation (by replace it) when the refCnt is greater then 1 which may happen when the user
                // use slice().retain() or duplicate().retain().
                //
                // See:
                // - https://github.com/netty/netty/issues/2327
                // - https://github.com/netty/netty/issues/1764
                buffer = expandCumulation(alloc, cumulation, in.readableBytes());
                buffer.writeBytes(in);
                in.release();
            } else {
                CompositeByteBuf composite;
                if (cumulation instanceof CompositeByteBuf) {
                    composite = (CompositeByteBuf) cumulation;
                } else {
                    composite = alloc.compositeBuffer(Integer.MAX_VALUE);
                    composite.addComponent(true, cumulation);
                }
                composite.addComponent(true, in);
                buffer = composite;
            }
            return buffer;
        }
    };

    ByteBuf cumulation;
    private Cumulator cumulator = MERGE_CUMULATOR;
    private boolean singleDecode;
    private boolean decodeWasNull;
    private boolean first;
    private int discardAfterReads = 16;
    private int numReads;

    protected ByteToMessageDecoder() {
        CodecUtil.ensureNotSharable(this);
    }

    /**
     * If set then only one message is decoded on each {@link #channelRead(ChannelHandlerContext, Object)}
     * call. This may be useful if you need to do some protocol upgrade and want to make sure nothing is mixed up.
     *
     * Default is {@code false} as this has performance impacts.
     */
    public void setSingleDecode(boolean singleDecode) {
        this.singleDecode = singleDecode;
    }

    /**
     * If {@code true} then only one message is decoded on each
     * {@link #channelRead(ChannelHandlerContext, Object)} call.
     *
     * Default is {@code false} as this has performance impacts.
     */
    public boolean isSingleDecode() {
        return singleDecode;
    }

    /**
     * Set the {@link Cumulator} to use for cumulate the received {@link ByteBuf}s.
     */
    public void setCumulator(Cumulator cumulator) {
        if (cumulator == null) {
            throw new NullPointerException("cumulator");
        }
        this.cumulator = cumulator;
    }

    /**
     * Set the number of reads after which {@link ByteBuf#discardSomeReadBytes()} are called and so free up memory.
     * The default is {@code 16}.
     */
    public void setDiscardAfterReads(int discardAfterReads) {
        if (discardAfterReads <= 0) {
            throw new IllegalArgumentException("discardAfterReads must be > 0");
        }
        this.discardAfterReads = discardAfterReads;
    }

    /**
     * Returns the actual number of readable bytes in the internal cumulative
     * buffer of this decoder. You usually do not need to rely on this value
     * to write a decoder. Use it only when you must use it at your own risk.
     * This method is a shortcut to {@link #internalBuffer() internalBuffer().readableBytes()}.
     */
    protected int actualReadableBytes() {
        return internalBuffer().readableBytes();
    }

    /**
     * Returns the internal cumulative buffer of this decoder. You usually
     * do not need to access the internal buffer directly to write a decoder.
     * Use it only when you must use it at your own risk.
     */
    protected ByteBuf internalBuffer() {
        if (cumulation != null) {
            return cumulation;
        } else {
            return Unpooled.EMPTY_BUFFER;
        }
    }

    @Override
    public final void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        ByteBuf buf = cumulation;
        if (buf != null) {
            // Directly set this to null so we are sure we not access it in any other method here anymore.
            cumulation = null;

            int readable = buf.readableBytes();
            if (readable > 0) {
                ByteBuf bytes = buf.readBytes(readable);
                buf.release();
                ctx.fireChannelRead(bytes);
            } else {
                buf.release();
            }

            numReads = 0;
            ctx.fireChannelReadComplete();
        }
        handlerRemoved0(ctx);
    }

    /**
     * Gets called after the {@link ByteToMessageDecoder} was removed from the actual context and it doesn't handle
     * events anymore.
     */
    protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception { }

    @Override
    //这方法比较长, 我带大家一步步剖析。首先判断如果传来的数据是 ByteBuf, 则进入 if 块中，CodecOutputList out =
    // CodecOutputList.newInstance() 这里就当成一个 ArrayList 就好, 用于保存解码完成的数据 ByteBuf data = (ByteBuf)
    // msg 这步将数据转化成 ByteBuf；first = cumulation == null 表示如果 cumulation == null, 说明没有存储板半包数据,
    // 则将当前的数据保存在属性 cumulation 中；如果 cumulation != null , 说明存储了半包数据, 则通过
    //cumulator.cumulate(ctx.alloc(), cumulation, data)将读取到的数据和原来的数据进行累加, 保存在属性 cumulation 中，
    // 我们看 cumulator 属性的定义：
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        //如果 message 是 byteBuf 类型
        if (msg instanceof ByteBuf) {
            //首先判断如果传来的数据是 ByteBuf, 则进入 if 块中
            //简单当成一个 arrayList, 用于盛放解析到的对象
            //这里就当成一个 ArrayList 就好, 用于保存解码完成的数据
            CodecOutputList out = CodecOutputList.newInstance();
            try {
                //这步将数据转化成 ByteBuf
                ByteBuf data = (ByteBuf) msg;
                //当前累加器为空, 说明这是第一次从 io 流里面读取数据
                //表示如果 cumulation == null, 说明没有存储板半包数据, 则将当前的数据保存在属性 cumulation 中
                first = cumulation == null;
                if (first) {
                    //如果是第一次, 则将累加器赋值为刚读进来的对象
                    cumulation = data;
                } else {
                    //如果不是第一次, 则把当前累加的数据和读进来的数据进行累加
                    //如果 cumulation != null , 说明存储了半包数据, 则通过cumulator.cumulate(ctx.alloc(), cumulation, data)
                    //将读取到的数据和原来的数据进行累加, 保存在属性 cumulation 中，
                    cumulation = cumulator.cumulate(ctx.alloc(), cumulation, data);
                }
                //调用子类的方法进行解析
                //最后调用 callDecode(ctx, cumulation, out)方法进
               // 行解码, 这里传入了 Context 对象, 缓冲区 cumulation 和集合 out。我们跟进到 callDecode(ctx, cumulation, out)方法：
                callDecode(ctx, cumulation, out);
            } catch (DecoderException e) {
                throw e;
            } catch (Throwable t) {
                throw new DecoderException(t);
            } finally {
                //首先判断 cumulation 不为 null, 并且没有可读字节, 则将累加器进行释放, 并设置为 null
                if (cumulation != null && !cumulation.isReadable()) {
                    numReads = 0;
                    cumulation.release();
                    cumulation = null;
                } else if (++ numReads >= discardAfterReads) {
                    // We did enough reads already try to discard some bytes so we not risk to see a OOME.
                    // See https://github.com/netty/netty/issues/4275
                    numReads = 0;
                    discardSomeReadBytes();
                }
                //记录 list 长度
                //之后记录 out 的长度, 通过
                // fireChannelRead(ctx, out, size)将 channelRead 事件进行向下传播
                int size = out.size();
                decodeWasNull = !out.insertSinceRecycled();
                //向下传播
                //并回收 out 对象。我们跟到 fireChannelRead(ctx,out, size)方法来看代码：
                fireChannelRead(ctx, out, size);
                out.recycle();
            }
        } else {
            //不是 byteBuf 类型则向下传播
            ctx.fireChannelRead(msg);
        }
    }

    /**
     * Get {@code numElements} out of the {@link List} and forward these through the pipeline.
     */
    static void fireChannelRead(ChannelHandlerContext ctx, List<Object> msgs, int numElements) {
        if (msgs instanceof CodecOutputList) {
            fireChannelRead(ctx, (CodecOutputList) msgs, numElements);
        } else {
            for (int i = 0; i < numElements; i++) {
                ctx.fireChannelRead(msgs.get(i));
            }
        }
    }

    /**
     * Get {@code numElements} out of the {@link CodecOutputList} and forward these through the pipeline.
     */
    //这里遍历 out 集合, 并将里面的元素逐个向下传递，以上就是有关解码的骨架逻辑
    static void fireChannelRead(ChannelHandlerContext ctx, CodecOutputList msgs, int numElements) {
        for (int i = 0; i < numElements; i ++) {
            ctx.fireChannelRead(msgs.getUnsafe(i));
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        numReads = 0;
        discardSomeReadBytes();
        if (decodeWasNull) {
            decodeWasNull = false;
            if (!ctx.channel().config().isAutoRead()) {
                ctx.read();
            }
        }
        ctx.fireChannelReadComplete();
    }

    protected final void discardSomeReadBytes() {
        if (cumulation != null && !first && cumulation.refCnt() == 1) {
            // discard some bytes if possible to make more room in the
            // buffer but only if the refCnt == 1  as otherwise the user may have
            // used slice().retain() or duplicate().retain().
            //
            // See:
            // - https://github.com/netty/netty/issues/2327
            // - https://github.com/netty/netty/issues/1764
            cumulation.discardSomeReadBytes();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        channelInputClosed(ctx, true);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof ChannelInputShutdownEvent) {
            // The decodeLast method is invoked when a channelInactive event is encountered.
            // This method is responsible for ending requests in some situations and must be called
            // when the input has been shutdown.
            channelInputClosed(ctx, false);
        }
        super.userEventTriggered(ctx, evt);
    }

    private void channelInputClosed(ChannelHandlerContext ctx, boolean callChannelInactive) throws Exception {
        CodecOutputList out = CodecOutputList.newInstance();
        try {
            channelInputClosed(ctx, out);
        } catch (DecoderException e) {
            throw e;
        } catch (Exception e) {
            throw new DecoderException(e);
        } finally {
            try {
                if (cumulation != null) {
                    cumulation.release();
                    cumulation = null;
                }
                int size = out.size();
                fireChannelRead(ctx, out, size);
                if (size > 0) {
                    // Something was read, call fireChannelReadComplete()
                    ctx.fireChannelReadComplete();
                }
                if (callChannelInactive) {
                    ctx.fireChannelInactive();
                }
            } finally {
                // Recycle in all cases
                out.recycle();
            }
        }
    }

    /**
     * Called when the input of the channel was closed which may be because it changed to inactive or because of
     * {@link ChannelInputShutdownEvent}.
     */
    void channelInputClosed(ChannelHandlerContext ctx, List<Object> out) throws Exception {
        if (cumulation != null) {
            callDecode(ctx, cumulation, out);
            decodeLast(ctx, cumulation, out);
        } else {
            decodeLast(ctx, Unpooled.EMPTY_BUFFER, out);
        }
    }

    /**
     * Called once data should be decoded from the given {@link ByteBuf}. This method will call
     * {@link #decode(ChannelHandlerContext, ByteBuf, List)} as long as decoding should take place.
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in            the {@link ByteBuf} from which to read data
     * @param out           the {@link List} to which decoded messages should be added
     */

    //首先循环判断传入的 ByteBuf 是否有可读字节, 如果还有可读字节说明没有解码完成, 则循环继续解码。然后判断集合
    // out 的大小, 如果大小大于 1, 说明 out 中盛放了解码完成之后的数据, 然后将事件向下传播, 并清空 out。因为我们第
    // 一次解码 out 是空的, 所以这里不会进入 if 块, 这部分我们稍后分析, 所以继续往下看，通过 int oldInputLength =
    // in.readableBytes() 获取当前 ByteBuf, 其实也就是属性 cumulation 的可读字节数, 这里就是一个备份用于比较。我们
    // 继续往下看，decode(ctx, in, out)方法是最终的解码操作, 这部会读取 cumulation 并且将解码后的数据放入到集合 out
    // 中, 在 ByteToMessageDecoder 中的该方法是一个抽象方法, 让子类进行实现, 我们使用的 netty 很多的解码都是继承
    // 了 ByteToMessageDecoder 并实现了 decode 方法从而完成了解码操作, 同样我们也可以遵循相应的规则进行自定义
    // 解码器, 在之后的小节中会讲解 netty 定义的解码器, 并剖析相关的实现细节。继续往下看，if (outSize == out.size()) 这
    // 个判断表示解析之前的 out 大小和解析之后 out 大小进行比较, 如果相同, 说明并没有解析出数据, 我们进入到 if 块中。
    // if (oldInputLength == in.readableBytes()) 表示 cumulation 的可读字节数在解析之前和解析之后是相同的, 说明解码
    // 方法中并没有解析数据, 也就是当前的数据并不是一个完整的数据包, 则跳出循环, 留给下次解析, 否则, 说明没有解
    //析到数据, 但是读取了, 所以跳过该次循环进入下次循环。最后判断 if (oldInputLength == in.readableBytes()) , 这里
    // 代表 out 中有数据, 但是并没有从 cumulation 读数据, 说明这个 out 的内容是非法的, 直接抛出异常。现在回到
    // channRead()方法，我们关注 finally 代码块中的内容：
    protected void callDecode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        try {
            //只要累加器里面有数据
            // 首先循环判断传入的 ByteBuf 是否有可读字节, 如果还有可读字节说明没有解码完成, 则循环继续解码。
            while (in.isReadable()) {
                int outSize = out.size();
                //判断当前 List 是否有对象
                //然后判断集合out 的大小, 如果大小大于 1, 说明 out 中盛放了解码完成之后的数据, 然后将事件向下传播, 并清空 out
                if (outSize > 0) {
                    //如果有对象, 则向下传播事件
                    fireChannelRead(ctx, out, outSize);
                    //清空当前 list
                    out.clear();

                    // Check if this handler was removed before continuing with decoding.
                    // If it was removed, it is not safe to continue to operate on the buffer.
                    //
                    // See:
                    // - https://github.com/netty/netty/issues/4635
                    //解码过程中如 ctx 被 removed 掉就 break
                    if (ctx.isRemoved()) {
                        break;
                    }
                    outSize = 0;
                }
                //当前可读数据长度
                //因为我们第
                //     // 一次解码 out 是空的, 所以这里不会进入 if 块, 这部分我们稍后分析, 所以继续往下看，通过 int oldInputLength =
                //     // in.readableBytes() 获取当前 ByteBuf, 其实也就是属性 cumulation 的可读字节数, 这里就是一个备份用于比较。
                int oldInputLength = in.readableBytes();
                //子类实现
                //子类解析, 解析玩对象放到 out 里面
                //我们
                //     // 继续往下看，decode(ctx, in, out)方法是最终的解码操作, 这部会读取 cumulation 并且将解码后的数据放入到集合 out
                //     // 中, 在 ByteToMessageDecoder 中的该方法是一个抽象方法, 让子类进行实现, 我们使用的 netty 很多的解码都是继承
                //     // 了 ByteToMessageDecoder 并实现了 decode 方法从而完成了解码操作, 同样我们也可以遵循相应的规则进行自定义
                //     // 解码器, 在之后的小节中会讲解 netty 定义的解码器, 并剖析相关的实现细节。
                decode(ctx, in, out);

                // Check if this handler was removed before continuing the loop.
                // If it was removed, it is not safe to continue to operate on the buffer.
                //
                // See https://github.com/netty/netty/issues/1664
                //List 解析前大小 和解析后长度一样(什么没有解析出来)
                if (ctx.isRemoved()) {
                    break;
                }
                //继续往下看，if (outSize == out.size()) 这
                //     // 个判断表示解析之前的 out 大小和解析之后 out 大小进行比较, 如果相同, 说明并没有解析出数据, 我们进入到 if 块中。
                if (outSize == out.size()) {
                    //原来可读的长度==解析后可读长度
                    //说明没有读取数据(当前累加的数据并没有拼成一个完整的数据包)
                    //if (oldInputLength == in.readableBytes()) 表示 cumulation 的可读字节数在解析之前和解析之后是相同的, 说明解码
                    //     // 方法中并没有解析数据, 也就是当前的数据并不是一个完整的数据包, 则跳出循环, 留给下次解析, 否则, 说明没有解
                    //     //析到数据, 但是读取了, 所以跳过该次循环进入下次循环。
                    if (oldInputLength == in.readableBytes()) {
                        break;//跳出循环(下次在读取数据才能进行后续的解析)
                    } else {
                        //没有解析到数据, 但是进行读取了
                        continue;
                    }
                }
                //out 里面有数据, 但是没有从累加器读取数据最后判断 if (oldInputLength == in.readableBytes()) , 这里
                //代表 out 中有数据, 但是并没有从 cumulation 读数据, 说明这个 out 的内容是非法的, 直接抛出异常。现在回到channRead()方法，
                // 我们关注 finally 代码块中的内容：
                if (oldInputLength == in.readableBytes()) {
                    throw new DecoderException(
                            StringUtil.simpleClassName(getClass()) +
                            ".decode() did not read anything but decoded a message.");
                }
                if (isSingleDecode()) {
                    break;
                }
            }
        } catch (DecoderException e) {
            throw e;
        } catch (Throwable cause) {
            throw new DecoderException(cause);
        }
    }

    /**
     * Decode the from one {@link ByteBuf} to an other. This method will be called till either the input
     * {@link ByteBuf} has nothing to read when return from this method or till nothing was read from the input
     * {@link ByteBuf}.
     *
     * @param ctx           the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param in            the {@link ByteBuf} from which to read data
     * @param out           the {@link List} to which decoded messages should be added
     * @throws Exception    is thrown if an error accour
     */
    protected abstract void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception;

    /**
     * Is called one last time when the {@link ChannelHandlerContext} goes in-active. Which means the
     * {@link #channelInactive(ChannelHandlerContext)} was triggered.
     *
     * By default this will just call {@link #decode(ChannelHandlerContext, ByteBuf, List)} but sub-classes may
     * override this for some special cleanup operation.
     */
    protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.isReadable()) {
            // Only call decode() if there is something left in the buffer to decode.
            // See https://github.com/netty/netty/issues/4386
            decode(ctx, in, out);
        }
    }

    static ByteBuf expandCumulation(ByteBufAllocator alloc, ByteBuf cumulation, int readable) {
        ByteBuf oldCumulation = cumulation;
        cumulation = alloc.buffer(oldCumulation.readableBytes() + readable);
        cumulation.writeBytes(oldCumulation);
        oldCumulation.release();
        return cumulation;
    }

    /**
     * Cumulate {@link ByteBuf}s.
     */
    public interface Cumulator {
        /**
         * Cumulate the given {@link ByteBuf}s and return the {@link ByteBuf} that holds the cumulated bytes.
         * The implementation is responsible to correctly handle the life-cycle of the given {@link ByteBuf}s and so
         * call {@link ByteBuf#release()} if a {@link ByteBuf} is fully consumed.
         */
        ByteBuf cumulate(ByteBufAllocator alloc, ByteBuf cumulation, ByteBuf in);
    }
}
