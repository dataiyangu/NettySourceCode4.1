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

package io.netty.buffer;

import io.netty.util.internal.LongCounter;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Math.max;

abstract class PoolArena<T> implements PoolArenaMetric {
    static final boolean HAS_UNSAFE = PlatformDependent.hasUnsafe();

    enum SizeClass {
        Tiny,
        Small,
        Normal
    }

    static final int numTinySubpagePools = 512 >>> 4;

    final PooledByteBufAllocator parent;

    private final int maxOrder;
    final int pageSize;
    final int pageShifts;
    final int chunkSize;
    final int subpageOverflowMask;
    final int numSmallSubpagePools;
    //这个属性是一个 PoolSubpage 的数组, 有点类似于一个 subpage 的缓存, 我们创建一个 subpage 之后, 会将创建的
    //subpage 与该属性其中每个关联, 下次在分配的时候可以直接通过该属性的元素去找关联的 subpage。我们其中是在
    // 构造方法中初始化的, 看构造方法中其初始化代码
    private final PoolSubpage<T>[] tinySubpagePools;
    private final PoolSubpage<T>[] smallSubpagePools;

    //这里总共定义了 6 个 chunkList, 并在构造方法将其进行初始化，我们跟到其构造方法中：
    private final PoolChunkList<T> q050;
    private final PoolChunkList<T> q025;
    private final PoolChunkList<T> q000;
    private final PoolChunkList<T> qInit;
    private final PoolChunkList<T> q075;
    private final PoolChunkList<T> q100;

    private final List<PoolChunkListMetric> chunkListMetrics;

    // Metrics for allocations and deallocations
    private long allocationsNormal;
    // We need to use the LongCounter here as this is not guarded via synchronized block.
    private final LongCounter allocationsTiny = PlatformDependent.newLongCounter();
    private final LongCounter allocationsSmall = PlatformDependent.newLongCounter();
    private final LongCounter allocationsHuge = PlatformDependent.newLongCounter();
    private final LongCounter activeBytesHuge = PlatformDependent.newLongCounter();

    private long deallocationsTiny;
    private long deallocationsSmall;
    private long deallocationsNormal;

    // We need to use the LongCounter here as this is not guarded via synchronized block.
    private final LongCounter deallocationsHuge = PlatformDependent.newLongCounter();

    // Number of thread caches backed by this arena.
    final AtomicInteger numThreadCaches = new AtomicInteger();

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    protected PoolArena(PooledByteBufAllocator parent, int pageSize, int maxOrder, int pageShifts, int chunkSize) {
        this.parent = parent;
        this.pageSize = pageSize;
        this.maxOrder = maxOrder;
        this.pageShifts = pageShifts;
        this.chunkSize = chunkSize;
        subpageOverflowMask = ~(pageSize - 1);
        //subpage 与该属性其中每个关联, 下次在分配的时候可以直接通过该属性的元素去找关联的 subpage。我们其中是在
        // 构造方法中初始化的, 看构造方法中其初始化代码
        tinySubpagePools = newSubpagePoolArray(numTinySubpagePools);
        //这里直接创建了一个 PoolSubpage 数组, 长度为 32，在构造方法中创建完毕之后, 会通过循环为其赋值：
        for (int i = 0; i < tinySubpagePools.length; i ++) {
            tinySubpagePools[i] = newSubpagePoolHead(pageSize);
        }

        numSmallSubpagePools = pageShifts - 9;
        smallSubpagePools = newSubpagePoolArray(numSmallSubpagePools);
        for (int i = 0; i < smallSubpagePools.length; i ++) {
            smallSubpagePools[i] = newSubpagePoolHead(pageSize);
        }

        q100 = new PoolChunkList<T>(null, 100, Integer.MAX_VALUE, chunkSize);
        q075 = new PoolChunkList<T>(q100, 75, 100, chunkSize);
        q050 = new PoolChunkList<T>(q075, 50, 100, chunkSize);
        q025 = new PoolChunkList<T>(q050, 25, 75, chunkSize);
        q000 = new PoolChunkList<T>(q025, 1, 50, chunkSize);
        qInit = new PoolChunkList<T>(q000, Integer.MIN_VALUE, 25, chunkSize);
        //用双向链表的方向进行连接
        //参数 50 和 100 表示当前 chunkList 中存储的 chunk 的内存使用率都在 50%到
        // 100%之间,
        q100.prevList(q075);
        q075.prevList(q050);
        q050.prevList(q025);
        q025.prevList(q000);
        q000.prevList(null);
        qInit.prevList(qInit);

        List<PoolChunkListMetric> metrics = new ArrayList<PoolChunkListMetric>(6);
        metrics.add(qInit);
        metrics.add(q000);
        metrics.add(q025);
        metrics.add(q050);
        metrics.add(q075);
        metrics.add(q100);
        chunkListMetrics = Collections.unmodifiableList(metrics);
    }

    private PoolSubpage<T> newSubpagePoolHead(int pageSize) {
        PoolSubpage<T> head = new PoolSubpage<T>(pageSize);
        //subpage是一个双向链表，上一个节点和下一个节点都设置为自身，循环创建总共32个
        head.prev = head;
        head.next = head;
        return head;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpagePoolArray(int size) {
        return new PoolSubpage[size];
    }

    abstract boolean isDirect();

    PooledByteBuf<T> allocate(PoolThreadCache cache, int reqCapacity, int maxCapacity) {
        //在这个地方其实就非常清晰了，首先调用了 newByteBuf()方法去拿到一个 PooledByteBuf 对象，接下来通过 allocate()
        // 方法在线程私有的 PoolThreadCache 中分配一块内存，然后 buf 里面的内存地址之类的值进行初始化。我们可以跟进
        // 到 newByteBuf()看一下，选择 DirectArena 对象：
        PooledByteBuf<T> buf = newByteBuf(maxCapacity);
        //进入  分配内存
        allocate(cache, buf, reqCapacity);
        return buf;
    }

    //这里相当于直接将 normCapacity 除以 16, 通过前面的内容我们知道, tiny 类型缓存数组中每个元素规格化的数据都是
    // 16 的倍数, 所以通过这种方式可以找到其下标, 参考图 5-2, 如果是 16Byte 会拿到下标为 1 的元素, 如果是 32Byte 则会
    // 拿到下标为 2 的元素
    static int tinyIdx(int normCapacity) {
        //normCapacity就是16  除以16 也就是1
        return normCapacity >>> 4;
    }

    static int smallIdx(int normCapacity) {
        int tableIdx = 0;
        int i = normCapacity >>> 10;
        while (i != 0) {
            i >>>= 1;
            tableIdx ++;
        }
        return tableIdx;
    }

    // capacity < pageSize
    //这个方法是判断如果 normCapacity 小于一个 page 的大小, 也就是 8k 代表其实 tiny 或者 small。
    boolean isTinyOrSmall(int normCapacity) {
        return (normCapacity & subpageOverflowMask) == 0;
    }

    // normCapacity < 512
    //是判断如果小于 512, 则认为是 tiny。
    static boolean isTiny(int normCapacity) {
        return (normCapacity & 0xFFFFFE00) == 0;
    }

    //基本上都是判断不同规格大小，从其对应的缓存中获取内存。
    //如果所有规格都不满足，那就直接调用 allocateHuge()方法进行真实的内存分配。
    // 其先命中缓存, 如果命中不到, 则区分配一款连续内存
    private void allocate(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity) {
        //没存规格化
        final int normCapacity = normalizeCapacity(reqCapacity);
        //这里是根据规格化后的大小判断是否 tiny 或者 small 类型
        //是否小于8k
        if (isTinyOrSmall(normCapacity)) { // capacity < pageSize
            //如果分配16字节的缓冲区，进入到这里面
            int tableIdx;
            PoolSubpage<T>[] table;
            //判断是不是 tinty
            boolean tiny = isTiny(normCapacity);
            //是tiny
            if (tiny) { // < 512
                //缓存分配  //进入allocateTiny看看
                if (cache.allocateTiny(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    return;
                }
                //如果分配不到的话
                //通过 tinyIdx 拿到 tableIdx
                //返回的是1
                tableIdx = tinyIdx(normCapacity);
                //subpage 的数组
                // tinySubpagePools 赋值到局部变量 table 中,
                table = tinySubpagePools;
            } else {
                if (cache.allocateSmall(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    return;
                }
                tableIdx = smallIdx(normCapacity);
                table = smallSubpagePools;
            }
            //拿到对应的节点
            //这步时通过下标拿到一个 PoolSubpage, 因为我们以 16 字节为例, 所
            // 以我们拿到下标为 1 的 PoolSubpage, 对应的内存大小也就是 16Byte。
            final PoolSubpage<T> head = table[tableIdx];

            /**
             * Synchronize on the head. This is needed as {@link PoolChunk#allocateSubpage(int)} and
             * {@link PoolChunk#free(long)} may modify the doubly linked list as well.
             */
            synchronized (head) {
                final PoolSubpage<T> s = head.next;
                //默认情况下, head 的 next 也是自身
                //再看 final PoolSubpage<T> s = head.next 这
                // 一步, 跟我们刚才了解的的 tinySubpagePools 属性, 默认情况下 head.next 也是自身
                //所以 if (s != head)会返回 false,
                if (s != head) {
                    assert s.doNotDestroy && s.elemSize == normCapacity;
                    long handle = s.allocate();
                    assert handle >= 0;
                    s.chunk.initBufWithSubpage(buf, handle, reqCapacity);

                    if (tiny) {
                        allocationsTiny.increment();
                    } else {
                        allocationsSmall.increment();
                    }
                    return;
                }
            }
            allocateNormal(buf, reqCapacity, normCapacity);
            return;
        }
        //说明小于16m，
        if (normCapacity <= chunkSize) {
            //首先在缓存上进行内存分配
            //首先 if (normCapacity <= chunkSize) 说明其小于 16MB, 然后首先在缓存中分配, 因为最初缓存中没有值, 所以会走
            // 到 allocateNormal(buf, reqCapacity, normCapacity), 这里实际上就是在 page 级别上进行分配, 分配一个或者多个
            // page 的空间。
            if (cache.allocateNormal(this, buf, reqCapacity, normCapacity)) {
                // was able to allocate out of the cache so move on
                //分配成功, 返回
                return;
            }
            //分配不成功, 做实际的内存分配
            allocateNormal(buf, reqCapacity, normCapacity);
        } else {
            // Huge allocations are never served via the cache so just call allocateHuge
            //进行真实内存分配
            //大于这个值, 就不在缓存上分配
            allocateHuge(buf, reqCapacity);
        }
    }

    //1. 在原有的 chunk 中进行分配；
    // 2. 创建 chunk 进行分配；
    // 3. 初始化 ByteBuf。
    private synchronized void allocateNormal(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        //首先在原来的 chunk 上进行内存分配(1)
        if (q050.allocate(buf, reqCapacity, normCapacity) || q025.allocate(buf, reqCapacity, normCapacity) ||
            q000.allocate(buf, reqCapacity, normCapacity) || qInit.allocate(buf, reqCapacity, normCapacity) ||
            q075.allocate(buf, reqCapacity, normCapacity)) {
            ++allocationsNormal;
            return;
        }

        // Add a new chunk.
        //创建 chunk 进行内存分配(2)
        //看第二步 PoolChunk<T> c = newChunk(pageSize, maxOrder, pageShifts, chunkSize)，这里的参数 pageSize 是 8192,
        // 也就是 8KB。
        // maxOrder 为 11；
        // pageShifts 为 13, 2 的 13 次方正好是 8192, 也就是 8KB；
        // chunkSize 为 16777216, 也就是 16MB。
        // 因 为 我们 分 析 的是 堆 外内 存 , newChunk(pageSize, maxOrder, pageShifts, chunkSize) 所 以 会走 到 DirectArena 的
        // newChunk()方法
        PoolChunk<T> c = newChunk(pageSize, maxOrder, pageShifts, chunkSize);
        //进入
        //我们分析完了 long handle = c.allocate(normCapacity)这步, 这里返回的 handle 就指向 chunk 中的某个 page 中的某
        //个子块所对应的连续内存
        long handle = c.allocate(normCapacity);
        ++allocationsNormal;
        assert handle > 0;
        //初始化 byteBuf(3)
        //进入看看
            //最后, 通过 iniBuf 初始化之后, 将创建的 chunk 加到 ChunkList 里面，我们跟到 initBuf 方
        // 法中
        c.initBuf(buf, handle, reqCapacity);
        //将新创建的chunk添加到chunkLIst中
        //进入看看
        qInit.add(c);
    }

    private void allocateHuge(PooledByteBuf<T> buf, int reqCapacity) {
        PoolChunk<T> chunk = newUnpooledChunk(reqCapacity);
        activeBytesHuge.add(chunk.chunkSize());
        buf.initUnpooled(chunk, reqCapacity);
        allocationsHuge.increment();
    }

    //首先判断是不是 unpooled, 我们这里是 Pooled, 所以会走到 else 块中：
    // sizeClass(normCapacity)计算是哪种级别的 size, 我们按照 tiny 级别进行分析；
    // cache.add(this, chunk, handle, normCapacity, sizeClass)是将当前当前 ByteBuf 进行缓存；
    // 我们之前讲过, 再分配 ByteBuf 时首先在缓存上分配, 而这步, 就是将其缓存的过程, 继续跟进去：
    void free(PoolChunk<T> chunk, long handle, int normCapacity, PoolThreadCache cache) {
        //是否为 unpooled
        if (chunk.unpooled) {
            int size = chunk.chunkSize();
            destroyChunk(chunk);
            activeBytesHuge.add(-size);
            deallocationsHuge.increment();
        } else {
            // 首先判断是不是 unpooled, 我们这里是 Pooled, 所以会走到 else 块中：
            //那种级别的 Size
            //sizeClass(normCapacity)计算是哪种级别的 size, 我们按照 tiny 级别进行分析；
            SizeClass sizeClass = sizeClass(normCapacity);
            //加到缓存里
            //cache.add(this, chunk, handle, normCapacity, sizeClass)是将当前当前 ByteBuf 进行缓存；
            //我们之前讲过, 再分配 ByteBuf 时首先在缓存上分配, 而这步, 就是将其缓存的过程, 继续跟进去：
            if (cache != null && cache.add(this, chunk, handle, normCapacity, sizeClass)) {
                // cached so not free it.
                return;
            }
            //将缓存对象标记为未使用
            //这里加到缓存之后, 如果成功, 就会 return, 如果不成功, 就会调用 freeChunk(chunk, handle, sizeClass)方法, 这个方
            // 法的意义是, 将原先给 ByteBuf 分配的内存区段标记为未使用，跟进 freeChunk()简单分析下：
            freeChunk(chunk, handle, sizeClass);
        }
    }

    private SizeClass sizeClass(int normCapacity) {
        if (!isTinyOrSmall(normCapacity)) {
            return SizeClass.Normal;
        }
        return isTiny(normCapacity) ? SizeClass.Tiny : SizeClass.Small;
    }

    void freeChunk(PoolChunk<T> chunk, long handle, SizeClass sizeClass) {
        final boolean destroyChunk;
        synchronized (this) {
            switch (sizeClass) {
            case Normal:
                ++deallocationsNormal;
                break;
            case Small:
                ++deallocationsSmall;
                break;
            case Tiny:
                ++deallocationsTiny;
                break;
            default:
                throw new Error();
            }
            destroyChunk = !chunk.parent.free(chunk, handle);
        }
        if (destroyChunk) {
            // destroyChunk not need to be called while holding the synchronized lock.
            destroyChunk(chunk);
        }
    }

    PoolSubpage<T> findSubpagePoolHead(int elemSize) {
        int tableIdx;
        PoolSubpage<T>[] table;
        if (isTiny(elemSize)) { // < 512
            tableIdx = elemSize >>> 4;
            table = tinySubpagePools;
        } else {
            tableIdx = 0;
            elemSize >>>= 10;
            while (elemSize != 0) {
                elemSize >>>= 1;
                tableIdx ++;
            }
            table = smallSubpagePools;
        }

        return table[tableIdx];
    }

    //代码中 if (!isTiny(reqCapacity)) 代表如果大于 tiny 类型的大小, 也就是 512, 则会找一个 2 的幂次方的数值, 确保
    // 这个数值大于等于 reqCapacity。如果是 tiny, 则继续往下 if ((reqCapacity & 15) == 0) 这里判断如果是 16 的倍数, 则
    // 直接返回。如果不是 16 的倍数, 则返回 (reqCapacity & ~15) + 16 , 也就是变成最小大于当前值的 16 的倍数值。从上
    // 面规格化逻辑看出, 这里将缓存大小规格化成固定大小, 确保每个缓存对象缓存的 ByteBuf 容量统一。
    int normalizeCapacity(int reqCapacity) {
        if (reqCapacity < 0) {
            throw new IllegalArgumentException("capacity: " + reqCapacity + " (expected: 0+)");
        }
        if (reqCapacity >= chunkSize) {
            return reqCapacity;
        }
        // /如果>tiny
        if (!isTiny(reqCapacity)) { // >= 512
            // Doubled
            //找一个 2 的幂次方的数值, 确保数值大于等于 reqCapacity
            int normalizedCapacity = reqCapacity;
            normalizedCapacity --;
            normalizedCapacity |= normalizedCapacity >>>  1;
            normalizedCapacity |= normalizedCapacity >>>  2;
            normalizedCapacity |= normalizedCapacity >>>  4;
            normalizedCapacity |= normalizedCapacity >>>  8;
            normalizedCapacity |= normalizedCapacity >>> 16;
            normalizedCapacity ++;
            //如果是 16 的倍数
            if (normalizedCapacity < 0) {
                normalizedCapacity >>>= 1;
            }
            //不是 16 的倍数, 变成最大小于当前值的值+16
            return normalizedCapacity;
        }

        // Quantum-spaced
        if ((reqCapacity & 15) == 0) {
            return reqCapacity;
        }

        return (reqCapacity & ~15) + 16;
    }

    void reallocate(PooledByteBuf<T> buf, int newCapacity, boolean freeOldMemory) {
        if (newCapacity < 0 || newCapacity > buf.maxCapacity()) {
            throw new IllegalArgumentException("newCapacity: " + newCapacity);
        }

        int oldCapacity = buf.length;
        if (oldCapacity == newCapacity) {
            return;
        }

        PoolChunk<T> oldChunk = buf.chunk;
        long oldHandle = buf.handle;
        T oldMemory = buf.memory;
        int oldOffset = buf.offset;
        int oldMaxLength = buf.maxLength;
        int readerIndex = buf.readerIndex();
        int writerIndex = buf.writerIndex();

        allocate(parent.threadCache(), buf, newCapacity);
        if (newCapacity > oldCapacity) {
            memoryCopy(
                    oldMemory, oldOffset,
                    buf.memory, buf.offset, oldCapacity);
        } else if (newCapacity < oldCapacity) {
            if (readerIndex < newCapacity) {
                if (writerIndex > newCapacity) {
                    writerIndex = newCapacity;
                }
                memoryCopy(
                        oldMemory, oldOffset + readerIndex,
                        buf.memory, buf.offset + readerIndex, writerIndex - readerIndex);
            } else {
                readerIndex = writerIndex = newCapacity;
            }
        }

        buf.setIndex(readerIndex, writerIndex);

        if (freeOldMemory) {
            free(oldChunk, oldHandle, oldMaxLength, buf.cache);
        }
    }

    @Override
    public int numThreadCaches() {
        return numThreadCaches.get();
    }

    @Override
    public int numTinySubpages() {
        return tinySubpagePools.length;
    }

    @Override
    public int numSmallSubpages() {
        return smallSubpagePools.length;
    }

    @Override
    public int numChunkLists() {
        return chunkListMetrics.size();
    }

    @Override
    public List<PoolSubpageMetric> tinySubpages() {
        return subPageMetricList(tinySubpagePools);
    }

    @Override
    public List<PoolSubpageMetric> smallSubpages() {
        return subPageMetricList(smallSubpagePools);
    }

    @Override
    public List<PoolChunkListMetric> chunkLists() {
        return chunkListMetrics;
    }

    private static List<PoolSubpageMetric> subPageMetricList(PoolSubpage<?>[] pages) {
        List<PoolSubpageMetric> metrics = new ArrayList<PoolSubpageMetric>();
        for (int i = 0; i < pages.length; i ++) {
            PoolSubpage<?> head = pages[i];
            if (head.next == head) {
                continue;
            }
            PoolSubpage<?> s = head.next;
            for (;;) {
                metrics.add(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
        return metrics;
    }

    @Override
    public long numAllocations() {
        final long allocsNormal;
        synchronized (this) {
            allocsNormal = allocationsNormal;
        }
        return allocationsTiny.value() + allocationsSmall.value() + allocsNormal + allocationsHuge.value();
    }

    @Override
    public long numTinyAllocations() {
        return allocationsTiny.value();
    }

    @Override
    public long numSmallAllocations() {
        return allocationsSmall.value();
    }

    @Override
    public synchronized long numNormalAllocations() {
        return allocationsNormal;
    }

    @Override
    public long numDeallocations() {
        final long deallocs;
        synchronized (this) {
            deallocs = deallocationsTiny + deallocationsSmall + deallocationsNormal;
        }
        return deallocs + deallocationsHuge.value();
    }

    @Override
    public synchronized long numTinyDeallocations() {
        return deallocationsTiny;
    }

    @Override
    public synchronized long numSmallDeallocations() {
        return deallocationsSmall;
    }

    @Override
    public synchronized long numNormalDeallocations() {
        return deallocationsNormal;
    }

    @Override
    public long numHugeAllocations() {
        return allocationsHuge.value();
    }

    @Override
    public long numHugeDeallocations() {
        return deallocationsHuge.value();
    }

    @Override
    public  long numActiveAllocations() {
        long val = allocationsTiny.value() + allocationsSmall.value() + allocationsHuge.value()
                - deallocationsHuge.value();
        synchronized (this) {
            val += allocationsNormal - (deallocationsTiny + deallocationsSmall + deallocationsNormal);
        }
        return max(val, 0);
    }

    @Override
    public long numActiveTinyAllocations() {
        return max(numTinyAllocations() - numTinyDeallocations(), 0);
    }

    @Override
    public long numActiveSmallAllocations() {
        return max(numSmallAllocations() - numSmallDeallocations(), 0);
    }

    @Override
    public long numActiveNormalAllocations() {
        final long val;
        synchronized (this) {
            val = allocationsNormal - deallocationsNormal;
        }
        return max(val, 0);
    }

    @Override
    public long numActiveHugeAllocations() {
        return max(numHugeAllocations() - numHugeDeallocations(), 0);
    }

    @Override
    public long numActiveBytes() {
        long val = activeBytesHuge.value();
        synchronized (this) {
            for (int i = 0; i < chunkListMetrics.size(); i++) {
                for (PoolChunkMetric m: chunkListMetrics.get(i)) {
                    val += m.chunkSize();
                }
            }
        }
        return max(0, val);
    }

    protected abstract PoolChunk<T> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize);
    protected abstract PoolChunk<T> newUnpooledChunk(int capacity);
    protected abstract PooledByteBuf<T> newByteBuf(int maxCapacity);
    protected abstract void memoryCopy(T src, int srcOffset, T dst, int dstOffset, int length);
    protected abstract void destroyChunk(PoolChunk<T> chunk);

    @Override
    public synchronized String toString() {
        StringBuilder buf = new StringBuilder()
            .append("Chunk(s) at 0~25%:")
            .append(StringUtil.NEWLINE)
            .append(qInit)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 0~50%:")
            .append(StringUtil.NEWLINE)
            .append(q000)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 25~75%:")
            .append(StringUtil.NEWLINE)
            .append(q025)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 50~100%:")
            .append(StringUtil.NEWLINE)
            .append(q050)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 75~100%:")
            .append(StringUtil.NEWLINE)
            .append(q075)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 100%:")
            .append(StringUtil.NEWLINE)
            .append(q100)
            .append(StringUtil.NEWLINE)
            .append("tiny subpages:");
        appendPoolSubPages(buf, tinySubpagePools);
        buf.append(StringUtil.NEWLINE)
           .append("small subpages:");
        appendPoolSubPages(buf, smallSubpagePools);
        buf.append(StringUtil.NEWLINE);

        return buf.toString();
    }

    private static void appendPoolSubPages(StringBuilder buf, PoolSubpage<?>[] subpages) {
        for (int i = 0; i < subpages.length; i ++) {
            PoolSubpage<?> head = subpages[i];
            if (head.next == head) {
                continue;
            }

            buf.append(StringUtil.NEWLINE)
                    .append(i)
                    .append(": ");
            PoolSubpage<?> s = head.next;
            for (;;) {
                buf.append(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
    }

    @Override
    protected final void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            destroyPoolSubPages(smallSubpagePools);
            destroyPoolSubPages(tinySubpagePools);
            destroyPoolChunkLists(qInit, q000, q025, q050, q075, q100);
        }
    }

    private static void destroyPoolSubPages(PoolSubpage<?>[] pages) {
        for (PoolSubpage<?> page : pages) {
            page.destroy();
        }
    }

    private void destroyPoolChunkLists(PoolChunkList<T>... chunkLists) {
        for (PoolChunkList<T> chunkList: chunkLists) {
            chunkList.destroy(this);
        }
    }

    static final class HeapArena extends PoolArena<byte[]> {

        HeapArena(PooledByteBufAllocator parent, int pageSize, int maxOrder, int pageShifts, int chunkSize) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize);
        }

        @Override
        boolean isDirect() {
            return false;
        }

        @Override
        protected PoolChunk<byte[]> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize) {
            return new PoolChunk<byte[]>(this, new byte[chunkSize], pageSize, maxOrder, pageShifts, chunkSize);
        }

        @Override
        protected PoolChunk<byte[]> newUnpooledChunk(int capacity) {
            return new PoolChunk<byte[]>(this, new byte[capacity], capacity);
        }

        @Override
        protected void destroyChunk(PoolChunk<byte[]> chunk) {
            // Rely on GC.
        }

        @Override
        protected PooledByteBuf<byte[]> newByteBuf(int maxCapacity) {
            return HAS_UNSAFE ? PooledUnsafeHeapByteBuf.newUnsafeInstance(maxCapacity)
                    : PooledHeapByteBuf.newInstance(maxCapacity);
        }

        @Override
        protected void memoryCopy(byte[] src, int srcOffset, byte[] dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            System.arraycopy(src, srcOffset, dst, dstOffset, length);
        }
    }

    static final class DirectArena extends PoolArena<ByteBuffer> {

        DirectArena(PooledByteBufAllocator parent, int pageSize, int maxOrder, int pageShifts, int chunkSize) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize);
        }

        @Override
        boolean isDirect() {
            return true;
        }

        @Override
        //这里直接通过构造函数创建了一个 chunk。allocateDirect(chunkSize)这里是通过 jdk 的 api 的申请了一块直接内存, 我
        // 们跟到 PoolChunk 的构造函数中
        protected PoolChunk<ByteBuffer> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize) {
            return new PoolChunk<ByteBuffer>(
                    this, allocateDirect(chunkSize),
                    pageSize, maxOrder, pageShifts, chunkSize);
        }

        @Override
        protected PoolChunk<ByteBuffer> newUnpooledChunk(int capacity) {
            return new PoolChunk<ByteBuffer>(this, allocateDirect(capacity), capacity);
        }

        private static ByteBuffer allocateDirect(int capacity) {
            return PlatformDependent.useDirectBufferNoCleaner() ?
                    PlatformDependent.allocateDirectNoCleaner(capacity) : ByteBuffer.allocateDirect(capacity);
        }

        @Override
        protected void destroyChunk(PoolChunk<ByteBuffer> chunk) {
            if (PlatformDependent.useDirectBufferNoCleaner()) {
                PlatformDependent.freeDirectNoCleaner(chunk.memory);
            } else {
                PlatformDependent.freeDirectBuffer(chunk.memory);
            }
        }

        @Override
        protected PooledByteBuf<ByteBuffer> newByteBuf(int maxCapacity) {
            if (HAS_UNSAFE) {//首 先 判 断 是 否 支 持 UnSafe ， 默 认 情 况 下 一 般 是 支 持 Unsafe 的
                //所 以 我 们 继 续 进 入 到
                // PooledUnsafeDirectByteBuf 的 newInstance()方法：
                return PooledUnsafeDirectByteBuf.newInstance(maxCapacity);
            } else {
                return PooledDirectByteBuf.newInstance(maxCapacity);
            }
        }

        @Override
        protected void memoryCopy(ByteBuffer src, int srcOffset, ByteBuffer dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            if (HAS_UNSAFE) {
                PlatformDependent.copyMemory(
                        PlatformDependent.directBufferAddress(src) + srcOffset,
                        PlatformDependent.directBufferAddress(dst) + dstOffset, length);
            } else {
                // We must duplicate the NIO buffers because they may be accessed by other Netty buffers.
                src = src.duplicate();
                dst = dst.duplicate();
                src.position(srcOffset).limit(srcOffset + length);
                dst.position(dstOffset);
                dst.put(src);
            }
        }
    }
}
