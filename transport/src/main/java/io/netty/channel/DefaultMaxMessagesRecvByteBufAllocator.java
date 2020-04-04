/*
 * Copyright 2015 The Netty Project
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
package io.netty.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * Default implementation of {@link MaxMessagesRecvByteBufAllocator} which respects {@link ChannelConfig#isAutoRead()}
 * and also prevents overflow.
 */
public abstract class DefaultMaxMessagesRecvByteBufAllocator implements MaxMessagesRecvByteBufAllocator {
    private volatile int maxMessagesPerRead;

    public DefaultMaxMessagesRecvByteBufAllocator() {
        this(1);
    }

    public DefaultMaxMessagesRecvByteBufAllocator(int maxMessagesPerRead) {
        maxMessagesPerRead(maxMessagesPerRead);
    }

    @Override
    public int maxMessagesPerRead() {
        return maxMessagesPerRead;
    }

    @Override
    public MaxMessagesRecvByteBufAllocator maxMessagesPerRead(int maxMessagesPerRead) {
        if (maxMessagesPerRead <= 0) {
            throw new IllegalArgumentException("maxMessagesPerRead: " + maxMessagesPerRead + " (expected: > 0)");
        }
        this.maxMessagesPerRead = maxMessagesPerRead;
        return this;
    }

    /**
     * Focuses on enforcing the maximum messages per read condition for {@link #continueReading()}.
     */
    public abstract class MaxMessageHandle implements Handle {
        private ChannelConfig config;
        private int maxMessagePerRead;
        private int totalMessages;
        private int totalBytesRead;
        private int attemptedBytesRead;
        private int lastBytesRead;

        /**
         * Only {@link ChannelConfig#getMaxMessagesPerRead()} is used.
         */
        @Override
        public void reset(ChannelConfig config) {
            this.config = config;
            maxMessagePerRead = maxMessagesPerRead();
            totalMessages = totalBytesRead = 0;
        }

        @Override
        public ByteBuf allocate(ByteBufAllocator alloc) {
            //这里的 guess 方法, 会调用 AdaptiveRecvByteBufAllocator 的 guess 方法：
            return alloc.ioBuffer(guess());
        }

        @Override
        public final void incMessagesRead(int amt) {
            totalMessages += amt;
        }

        @Override
        //这里会赋值两个属性, lastBytesRead 代表最后读取的字节数, 这里赋值为我们刚才写入 ByteBuf 的字节数,
        // totalBytesRead 表示总共读取的字节数, 这里将写入的字节数追加。继续来到 NioByteUnsafe 的 read()方法，如果最后
        // 一次读取数据为 0, 说明已经将 channel 中的数据全部读取完毕, 将新创建的 ByteBuf 释放循环利用, 并跳出循环。
        // allocHandle.incMessagesRead(1)这步是增加消息的读取次数, 因为我们循环最多 16次, 所以当增加消息次数增加到 16
        // 会结束循环。读取完毕之后, 会通过 pipeline.fireChannelRead(byteBuf)将传递 channelRead 事件, 有关 channelRead
        // 事件, 我们在前面的章节也进行了详细的剖析。
        public final void lastBytesRead(int bytes) {
            //lastBytesRead 代表最后读取的字节数, 这里赋值为我们刚才写入 ByteBuf 的字节数,
            lastBytesRead = bytes;
            // Ignore if bytes is negative, the interface contract states it will be detected externally after call.
            // The value may be "invalid" after this point, but it doesn't matter because reading will be stopped.
            //totalBytesRead 表示总共读取的字节数, 这里将写入的字节数追加。
            totalBytesRead += bytes;
            if (totalBytesRead < 0) {
                totalBytesRead = Integer.MAX_VALUE;
            }
        }

        @Override
        public final int lastBytesRead() {
            return lastBytesRead;
        }

        @Override
        public boolean continueReading() {
            return config.isAutoRead() &&
                   attemptedBytesRead == lastBytesRead &&
                   totalMessages < maxMessagePerRead &&
                   totalBytesRead < Integer.MAX_VALUE;
        }

        @Override
        public void readComplete() {
        }

        @Override
        public int attemptedBytesRead() {
            return attemptedBytesRead;
        }

        @Override
        public void attemptedBytesRead(int bytes) {
            attemptedBytesRead = bytes;
        }

        protected final int totalBytesRead() {
            return totalBytesRead;
        }
    }
}
