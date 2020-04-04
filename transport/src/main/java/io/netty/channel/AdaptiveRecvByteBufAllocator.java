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
package io.netty.channel;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@link RecvByteBufAllocator} that automatically increases and
 * decreases the predicted buffer size on feed back.
 * <p>
 * It gradually increases the expected number of readable bytes if the previous
 * read fully filled the allocated buffer.  It gradually decreases the expected
 * number of readable bytes if the read operation was not able to fill a certain
 * amount of the allocated buffer two times consecutively.  Otherwise, it keeps
 * returning the same prediction.
 */
public class AdaptiveRecvByteBufAllocator extends DefaultMaxMessagesRecvByteBufAllocator {

    static final int DEFAULT_MINIMUM = 64;
    static final int DEFAULT_INITIAL = 1024;
    static final int DEFAULT_MAXIMUM = 65536;

    private static final int INDEX_INCREMENT = 4;
    private static final int INDEX_DECREMENT = 1;

    private static final int[] SIZE_TABLE;

    static {
        List<Integer> sizeTable = new ArrayList<Integer>();
        for (int i = 16; i < 512; i += 16) {
            sizeTable.add(i);
        }

        for (int i = 512; i > 0; i <<= 1) {
            sizeTable.add(i);
        }

        SIZE_TABLE = new int[sizeTable.size()];
        for (int i = 0; i < SIZE_TABLE.length; i ++) {
            SIZE_TABLE[i] = sizeTable.get(i);
        }
    }

    /**
     * @deprecated There is state for {@link #maxMessagesPerRead()} which is typically based upon channel type.
     */
    @Deprecated
    public static final AdaptiveRecvByteBufAllocator DEFAULT = new AdaptiveRecvByteBufAllocator();

    private static int getSizeTableIndex(final int size) {
        for (int low = 0, high = SIZE_TABLE.length - 1;;) {
            if (high < low) {
                return low;
            }
            if (high == low) {
                return high;
            }

            int mid = low + high >>> 1;
            int a = SIZE_TABLE[mid];
            int b = SIZE_TABLE[mid + 1];
            if (size > b) {
                low = mid + 1;
            } else if (size < a) {
                high = mid - 1;
            } else if (size == a) {
                return mid;
            } else {
                return mid + 1;
            }
        }
    }

    private final class HandleImpl extends MaxMessageHandle {
        private final int minIndex;
        private final int maxIndex;
        private int index;
        private int nextReceiveBufferSize;
        private boolean decreaseNow;

        public HandleImpl(int minIndex, int maxIndex, int initial) {
            this.minIndex = minIndex;
            this.maxIndex = maxIndex;

            index = getSizeTableIndex(initial);
            nextReceiveBufferSize = SIZE_TABLE[index];
        }

        @Override
        //这里会返回 AdaptiveRecvByteBufAllocator 的成员变量 nextReceiveBufferSize, 也就是下次所分配缓冲区的大小, 根据
        // 我们之前学习的内容, 第一次分配的时候会分配初始大小, 也就是 1024 字节。这样, alloc.ioBuffer(guess())就会分配一
        // 个 PooledByteBuf，我们跟到 AbstractByteBufAllocator 的 ioBuffer 方法中
        public int guess() {
            return nextReceiveBufferSize;
        }

        //首先看判断条件 if (actualReadBytes <= SIZE_TABLE[Math.max(0, index - INDEX_DECREMENT - 1)]) 。这里 index 是
        // 当前分配的缓冲区大小所在的 SIZE_TABLE 中的索引, 将这个索引进行缩进, 然后根据缩进后的所以找出 SIZE_TABLE
        // 中所存储的内存值, 再判断是否大于等于这次读取的最大字节数, 如果条件成立, 说明分配的内存过大, 需要缩容操作,
        // 我们看 if 块中缩容相关的逻辑。首先 if (decreaseNow) 会判断是否立刻进行收缩操作, 通常第一次不会进行收缩操作,
        // 然后会将 decreaseNow 设置为 true, 代表下一次直接进行收缩操作。假设需要立刻进行收缩操作, 我们看收缩操作的
        // 相关逻辑：
        // index = Math.max(index - INDEX_DECREMENT, minIndex) 这一步将索引缩进一步, 但不能小于最小索引值；
        // 然后通过 nextReceiveBufferSize = SIZE_TABLE[index] 获取设置索引之后的内存, 赋值在 nextReceiveBufferSize, 也
        // 就是下次需要分配的大小, 下次就会根据这个大小分配 ByteBuf 了, 这样就实现了缩容操作。
        // 再看 else if (actualReadBytes >= nextReceiveBufferSize) ，这里判断这次读取字节的总量比上次分配的大小还要大,
        //则进行扩容操作。扩容操作也很简单, 索引步进, 然后拿到步进后的索引所对应的内存值, 作为下次所需要分配的大小
        // 在 NioByteUnsafe 的 read()方法中，经过了缩容或者扩容操作之后, 通过 pipeline.fireChannelReadComplete()传播
        // ChannelReadComplete()事件，以上就是读取客户端消息的相关流程
        private void record(int actualReadBytes) {
            ///首先看判断条件 if (actualReadBytes <= SIZE_TABLE[Math.max(0, index - INDEX_DECREMENT - 1)])
            //这里 index 是当前分配的缓冲区大小所在的 SIZE_TABLE 中的索引, 将这个索引进行缩进, 然后根据缩进后的所以找出 SIZE_TABLE
            //中所存储的内存值, 再判断是否大于等于这次读取的最大字节数, 如果条件成立, 说明分配的内存过大, 需要缩容操作,

            if (actualReadBytes <= SIZE_TABLE[Math.max(0, index - INDEX_DECREMENT - 1)]) {
                // 我们看 if 块中缩容相关的逻辑。首先 if (decreaseNow) 会判断是否立刻进行收缩操作, 通常第一次不会进行收缩操作,
                //然后会将 decreaseNow 设置为 true, 代表下一次直接进行收缩操作。假设需要立刻进行收缩操作, 我们看收缩操作的相关逻辑：
                if (decreaseNow) {
                    // index = Math.max(index - INDEX_DECREMENT, minIndex) 这一步将索引缩进一步, 但不能小于最小索引值；
                    // 然后通过 nextReceiveBufferSize = SIZE_TABLE[index] 获取设置索引之后的内存, 赋值在 nextReceiveBufferSize, 也
                    // 就是下次需要分配的大小, 下次就会根据这个大小分配 ByteBuf 了, 这样就实现了缩容操作。
                    index = Math.max(index - INDEX_DECREMENT, minIndex);
                    nextReceiveBufferSize = SIZE_TABLE[index];
                    decreaseNow = false;
                } else {
                    decreaseNow = true;
                }
            } else if (actualReadBytes >= nextReceiveBufferSize) {
                // 再看 else if (actualReadBytes >= nextReceiveBufferSize) ，这里判断这次读取字节的总量比上次分配的大小还要大,
                //则进行扩容操作。扩容操作也很简单, 索引步进, 然后拿到步进后的索引所对应的内存值, 作为下次所需要分配的大小
                // 在 NioByteUnsafe 的 read()方法中，经过了缩容或者扩容操作之后, 通过 pipeline.fireChannelReadComplete()传播
                // ChannelReadComplete()事件，以上就是读取客户端消息的相关流程
                index = Math.min(index + INDEX_INCREMENT, maxIndex);
                nextReceiveBufferSize = SIZE_TABLE[index];
                decreaseNow = false;
            }
        }

        @Override
        public void readComplete() {
            //这里调用了 record()方法, 并且传入了这一次所读取的字节总数，跟到 record()方法中：
            record(totalBytesRead());
        }
    }

    private final int minIndex;
    private final int maxIndex;
    private final int initial;

    /**
     * Creates a new predictor with the default parameters.  With the default
     * parameters, the expected buffer size starts from {@code 1024}, does not
     * go down below {@code 64}, and does not go up above {@code 65536}.
     */
    public AdaptiveRecvByteBufAllocator() {
        this(DEFAULT_MINIMUM, DEFAULT_INITIAL, DEFAULT_MAXIMUM);
    }

    /**
     * Creates a new predictor with the specified parameters.
     *
     * @param minimum  the inclusive lower bound of the expected buffer size
     * @param initial  the initial buffer size when no feed back was received
     * @param maximum  the inclusive upper bound of the expected buffer size
     */
    public AdaptiveRecvByteBufAllocator(int minimum, int initial, int maximum) {
        if (minimum <= 0) {
            throw new IllegalArgumentException("minimum: " + minimum);
        }
        if (initial < minimum) {
            throw new IllegalArgumentException("initial: " + initial);
        }
        if (maximum < initial) {
            throw new IllegalArgumentException("maximum: " + maximum);
        }

        int minIndex = getSizeTableIndex(minimum);
        if (SIZE_TABLE[minIndex] < minimum) {
            this.minIndex = minIndex + 1;
        } else {
            this.minIndex = minIndex;
        }

        int maxIndex = getSizeTableIndex(maximum);
        if (SIZE_TABLE[maxIndex] > maximum) {
            this.maxIndex = maxIndex - 1;
        } else {
            this.maxIndex = maxIndex;
        }

        this.initial = initial;
    }

    @Override
    public Handle newHandle() {
        return new HandleImpl(minIndex, maxIndex, initial);
    }
}
