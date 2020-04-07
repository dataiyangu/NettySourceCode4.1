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
package io.netty.channel.nio;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.FileRegion;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.util.internal.StringUtil;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on bytes.
 */
public abstract class AbstractNioByteChannel extends AbstractNioChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);
    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ", " +
            StringUtil.simpleClassName(FileRegion.class) + ')';

    private Runnable flushTask;

    /**
     * Create a new instance
     *
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     */
    protected AbstractNioByteChannel(Channel parent, SelectableChannel ch) {
        super(parent, ch, SelectionKey.OP_READ);
    }

    /**
     * Shutdown the input side of the channel.
     */
    protected abstract ChannelFuture shutdownInput();

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioByteUnsafe();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    protected class NioByteUnsafe extends AbstractNioUnsafe {

        private void closeOnRead(ChannelPipeline pipeline) {
            if (isOpen()) {
                if (Boolean.TRUE.equals(config().getOption(ChannelOption.ALLOW_HALF_CLOSURE))) {
                    shutdownInput();
                    SelectionKey key = selectionKey();
                    key.interestOps(key.interestOps() & ~readInterestOp);
                    pipeline.fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                } else {
                    close(voidPromise());
                }
            }
        }

        private void handleReadException(ChannelPipeline pipeline, ByteBuf byteBuf, Throwable cause, boolean close,
                RecvByteBufAllocator.Handle allocHandle) {
            if (byteBuf != null) {
                if (byteBuf.isReadable()) {
                    readPending = false;
                    pipeline.fireChannelRead(byteBuf);
                } else {
                    byteBuf.release();
                }
            }
            allocHandle.readComplete();
            pipeline.fireChannelReadComplete();
            pipeline.fireExceptionCaught(cause);
            if (close || cause instanceof IOException) {
                closeOnRead(pipeline);
            }
        }

        @Override
        //首先获取 SocketChannel 的 config, pipeline 等相关属性，final ByteBufAllocator allocator = config.getAllocator(); 这
        // 一步是获取一个 ByteBuf 的内存分配器, 用于分配 ByteBuf。这里会走到 DefaultChannelConfig 的 getAllocator 方法中:
        public final void read() {
            //首先获取 SocketChannel 的 config, pipeline 等相关属性
            final ChannelConfig config = config();
            final ChannelPipeline pipeline = pipeline();

            //一步是获取一个 ByteBuf 的内存分配器 用于分配 ByteBuf
            //这里会走到 DefaultChannelConfig 的 getAllocator 方法中:

            //这里 ByteBufAllocator allocator = config.getAllocator()中的 allocator , 就是 PooledByteBufAllocator。
            final ByteBufAllocator allocator = config.getAllocator();
            //final RecvByteBufAllocator.Handle allocHandle = recvBufAllocHandle() 是创建一个 handle, 我们之前的章节讲过,
            //handle 是对 RecvByteBufAllocator 进行实际操作的对象，我们跟进 recvBufAllocHandle
            final RecvByteBufAllocator.Handle allocHandle = recvBufAllocHandle();
            //allocHandle.reset(config)是将配置重置,
            allocHandle.reset(config);

            ByteBuf byteBuf = null;
            boolean close = false;
            try {
                //进行 do-while
                // 循环, 有关循环终止条件 allocHandle.continueReading(), 之前小节也有过详细剖析, 这里也不再赘述
                do {
                    //byteBuf = allocHandle.allocate(allocator)
                    //这一步, 这里传入了刚才创建的 allocate 对象, 也就是
                    // PooledByteBufAllocator，这里会进入 DefaultMaxMessagesRecvByteBufAllocator 类的 allocate()方法中：
                    byteBuf = allocHandle.allocate(allocator);
                    //回到 NioByteUnsafe 的 read()方
                    // 法中，分配完了 ByteBuf 之后, 再看这一步 allocHandle.lastBytesRead(doReadBytes(byteBuf))。
                    // 首先看参数 doReadBytes(byteBuf)方法, 这步是将 channel 中的数据读取到我们刚分配的 ByteBuf 中, 并返回读取到的
                    // 字节数，这里会调用到 NioSocketChannel 的 doReadBytes()方法：
                    allocHandle.lastBytesRead(doReadBytes(byteBuf));
                    //如果最后
                    // 一次读取数据为 0, 说明已经将 channel 中的数据全部读取完毕, 将新创建的 ByteBuf 释放循环利用, 并跳出循环。
                    if (allocHandle.lastBytesRead() <= 0) {
                        // nothing was read. release the buffer.
                        byteBuf.release();
                        byteBuf = null;
                        close = allocHandle.lastBytesRead() < 0;
                        break;
                    }

                    allocHandle.incMessagesRead(1);
                    readPending = false;
                    //一次读取完毕之后, 会通过 pipeline.fireChannelRead(byteBuf)将传递 channelRead 事件, 有关 channelRead
                    // 事件, 我们在前面的章节也进行了详细的剖析
                    pipeline.fireChannelRead(byteBuf);
                    byteBuf = null;
                } while (allocHandle.continueReading());
                //我们知道第一次分配ByteBuf的初始容量是1024, 但是初始容量不一定一定满足所有的业务场景, netty中, 将每次读取
                // 数据的字节数进行记录, 然后之后次分配 ByteBuf 的时候, 容量会尽可能的符合业务场景所需要大小, 具体实现方式,
                // 就是在 readComplete()这一步体现的。我们跟到 AdaptiveRecvByteBufAllocator 的 readComplete()方法中：
                allocHandle.readComplete();
                pipeline.fireChannelReadComplete();

                if (close) {
                    closeOnRead(pipeline);
                }
            } catch (Throwable t) {
                handleReadException(pipeline, byteBuf, t, close, allocHandle);
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                if (!readPending && !config.isAutoRead()) {
                    removeReadOp();
                }
            }
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        int writeSpinCount = -1;

        boolean setOpWrite = false;
        for (;;) {
            Object msg = in.current();
            if (msg == null) {
                // Wrote all messages.
                clearOpWrite();
                // Directly return here so incompleteWrite(...) is not called.
                return;
            }

            if (msg instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) msg;
                int readableBytes = buf.readableBytes();
                if (readableBytes == 0) {
                    in.remove();
                    continue;
                }

                boolean done = false;
                long flushedAmount = 0;
                if (writeSpinCount == -1) {
                    writeSpinCount = config().getWriteSpinCount();
                }
                for (int i = writeSpinCount - 1; i >= 0; i --) {
                    int localFlushedAmount = doWriteBytes(buf);
                    if (localFlushedAmount == 0) {
                        setOpWrite = true;
                        break;
                    }

                    flushedAmount += localFlushedAmount;
                    if (!buf.isReadable()) {
                        done = true;
                        break;
                    }
                }

                in.progress(flushedAmount);

                if (done) {
                    in.remove();
                } else {
                    // Break the loop and so incompleteWrite(...) is called.
                    break;
                }
            } else if (msg instanceof FileRegion) {
                FileRegion region = (FileRegion) msg;
                boolean done = region.transferred() >= region.count();

                if (!done) {
                    long flushedAmount = 0;
                    if (writeSpinCount == -1) {
                        writeSpinCount = config().getWriteSpinCount();
                    }

                    for (int i = writeSpinCount - 1; i >= 0; i--) {
                        long localFlushedAmount = doWriteFileRegion(region);
                        if (localFlushedAmount == 0) {
                            setOpWrite = true;
                            break;
                        }

                        flushedAmount += localFlushedAmount;
                        if (region.transferred() >= region.count()) {
                            done = true;
                            break;
                        }
                    }

                    in.progress(flushedAmount);
                }

                if (done) {
                    ////代码省略
                    //这
                    // 里也省略了大段代码, 我们重点关注 in.remove()这里, 之前介绍过, 如果 done 为 true, 说明刷新事件已完成, 则移
                    // 除当前 entry 节点，我们跟到 remove()方法中
                    in.remove();
                } else {
                    // Break the loop and so incompleteWrite(...) is called.
                    break;
                }
            } else {
                // Should not reach here.
                throw new Error();
            }
        }
        incompleteWrite(setOpWrite);
    }

    @Override
    protected final Object filterOutboundMessage(Object msg) {
        //首
        // 先判断 msg 是否 byteBuf 对象,
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            //是堆外内存, 直接返回
            //如果是, 判断是否堆外内存
            if (buf.isDirect()) {
                //如果是堆外内存, 则直接返回,
                return msg;
            }
            // 否则, 通过newDirectBuffer(buf)这种方式转化为堆外内存
            return newDirectBuffer(buf);
        }

        if (msg instanceof FileRegion) {
            return msg;
        }

        throw new UnsupportedOperationException(
                "unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
    }

    protected final void incompleteWrite(boolean setOpWrite) {
        // Did not write completely.
        if (setOpWrite) {
            setOpWrite();
        } else {
            // Schedule flush again later so other tasks can be picked up in the meantime
            Runnable flushTask = this.flushTask;
            if (flushTask == null) {
                flushTask = this.flushTask = new Runnable() {
                    @Override
                    public void run() {
                        flush();
                    }
                };
            }
            eventLoop().execute(flushTask);
        }
    }

    /**
     * Write a {@link FileRegion}
     *
     * @param region        the {@link FileRegion} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract long doWriteFileRegion(FileRegion region) throws Exception;

    /**
     * Read bytes into the given {@link ByteBuf} and return the amount.
     */
    protected abstract int doReadBytes(ByteBuf buf) throws Exception;

    /**
     * Write bytes form the given {@link ByteBuf} to the underlying {@link java.nio.channels.Channel}.
     * @param buf           the {@link ByteBuf} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract int doWriteBytes(ByteBuf buf) throws Exception;

    protected final void setOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) == 0) {
            key.interestOps(interestOps | SelectionKey.OP_WRITE);
        }
    }

    protected final void clearOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) != 0) {
            key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
        }
    }
}
