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

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 *
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated
 * > chunk - a chunk is a collection of pages
 * > in this code chunkSize = 2^{maxOrder} * pageSize
 *
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 *
 * For simplicity all sizes are normalized according to PoolArena#normalizeCapacity method
 * This ensures that when we request for memory segments of size >= pageSize the normalizedCapacity
 * equals the next nearest power of 2
 *
 * To search for the first offset in chunk that has at least requested size available we construct a
 * complete balanced binary tree and store it in an array (just like heaps) - memoryMap
 *
 * The tree looks like this (the size of each node being mentioned in the parenthesis)
 *
 * depth=0        1 node (chunkSize)
 * depth=1        2 nodes (chunkSize/2)
 * ..
 * ..
 * depth=d        2^d nodes (chunkSize/2^d)
 * ..
 * depth=maxOrder 2^maxOrder nodes (chunkSize/2^{maxOrder} = pageSize)
 *
 * depth=maxOrder is the last level and the leafs consist of pages
 *
 * With this tree available searching in chunkArray translates like this:
 * To allocate a memory segment of size chunkSize/2^k we search for the first node (from left) at height k
 * which is unused
 *
 * Algorithm:
 * ----------
 * Encode the tree in memoryMap with the notation
 *   memoryMap[id] = x => in the subtree rooted at id, the first node that is free to be allocated
 *   is at depth x (counted from depth=0) i.e., at depths [depth_of_id, x), there is no node that is free
 *
 *  As we allocate & free nodes, we update values stored in memoryMap so that the property is maintained
 *
 * Initialization -
 *   In the beginning we construct the memoryMap array by storing the depth of a node at each node
 *     i.e., memoryMap[id] = depth_of_id
 *
 * Observations:
 * -------------
 * 1) memoryMap[id] = depth_of_id  => it is free / unallocated
 * 2) memoryMap[id] > depth_of_id  => at least one of its child nodes is allocated, so we cannot allocate it, but
 *                                    some of its children can still be allocated based on their availability
 * 3) memoryMap[id] = maxOrder + 1 => the node is fully allocated & thus none of its children can be allocated, it
 *                                    is thus marked as unusable
 *
 * Algorithm: [allocateNode(d) => we want to find the first node (from left) at height h that can be allocated]
 * ----------
 * 1) start at root (i.e., depth = 0 or id = 1)
 * 2) if memoryMap[1] > d => cannot be allocated from this chunk
 * 3) if left node value <= h; we can allocate from left subtree so move to left and repeat until found
 * 4) else try in right subtree
 *
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) Compute d = log_2(chunkSize/size)
 * 2) Return allocateNode(d)
 *
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) use allocateNode(maxOrder) to find an empty (i.e., unused) leaf (i.e., page)
 * 2) use this handle to construct the PoolSubpage object or if it already exists just call init(normCapacity)
 *    note that this PoolSubpage object is added to subpagesPool in the PoolArena when we init() it
 *
 * Note:
 * -----
 * In the implementation for improving cache coherence,
 * we store 2 pieces of information (i.e, 2 byte vals) as a short value in memoryMap
 *
 * memoryMap[id]= (depth_of_id, x)
 * where as per convention defined above
 * the second value (i.e, x) indicates that the first node which is free to be allocated is at depth x (from root)
 */
final class PoolChunk<T> implements PoolChunkMetric {

    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;

    final PoolArena<T> arena;
    final T memory;
    final boolean unpooled;

    private final byte[] memoryMap;
    private final byte[] depthMap;
    private final PoolSubpage<T>[] subpages;
    /** Used to determine if the requested capacity is equal to or greater than pageSize. */
    private final int subpageOverflowMask;
    private final int pageSize;
    private final int pageShifts;
    private final int maxOrder;
    private final int chunkSize;
    private final int log2ChunkSize;
    private final int maxSubpageAllocs;
    /** Used to mark memory as unusable */
    private final byte unusable;

    private int freeBytes;

    PoolChunkList<T> parent;
    PoolChunk<T> prev;
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;


    PoolChunk(PoolArena<T> arena, T memory, int pageSize, int maxOrder, int pageShifts, int chunkSize) {
        unpooled = false;
        this.arena = arena;
        //首先将参数传入的值进行赋值 this.memory = memory 就是将参数中创建的堆外内存进行保存, 就是 chunk 所指向的
        // 那块连续的内存, 在这个 chunk 中所分配的 ByteBuf, 都会在这块内存中进行读写
        this.memory = memory;
        this.pageSize = pageSize;
        this.pageShifts = pageShifts;
        this.maxOrder = maxOrder;
        this.chunkSize = chunkSize;
        unusable = (byte) (maxOrder + 1);
        log2ChunkSize = log2(chunkSize);
        subpageOverflowMask = ~(pageSize - 1);
        freeBytes = chunkSize;

        assert maxOrder < 30 : "maxOrder should be < 30, but is: " + maxOrder;
        maxSubpageAllocs = 1 << maxOrder;

        //首先看 memoryMap = new byte[maxSubpageAllocs << 1]；这里初始化了一个字节数组 memoryMap, 大
        // 小为 maxSubpageAllocs << 1, 也就是 4096；depthMap = new byte[memoryMap.length] 同样也是初始化了一个字
        // 节数组, 大小为 memoryMap 的大小, 也就是 4096

        // Generate the memory map.
        //节点数量为 4096
        memoryMap = new byte[maxSubpageAllocs << 1];
        //也是 4096 个节点
        depthMap = new byte[memoryMap.length];
        int memoryMapIndex = 1;
        //d 相当于一个深度, 赋值的内容代表当前节点的深度

        //实际上这个 for 循环就是将上面的结构包装成一个字节数组 memoryMap, 外层循环用于控制层数, 内层循环用于控制
        // 里面每层的节点, 这里经过循环之后, memoryMap 和 depthMap 内容为以下表现形式：
        // [0, 0, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4...........]
        // 这里注意一下, 因为程序中数组的下标是从 1 开始设置的, 所以第零个节点元素为默认值 0。
        // 这里数字代表层级, 同时也代表了当前层级的节点, 相同的数字个数就是这一层级的节点数。
        // 其中 0 为 2 个(因为这里分配时下标是从 1 开始的, 所以第 0 个位置是默认值 0, 实际上第零层元素只有一个, 就是头结
        // 点), 1 为 2 个, 2 为 4 个, 3 为 8 个, 4 为 16 个, n 为 2 的 n 次方个, 直到 11, 也就是 11 有 2 的 11 次方个
        for (int d = 0; d <= maxOrder; ++ d) { // move down the tree one level at a time
            int depth = 1 << d;
            for (int p = 0; p < depth; ++ p) {
                // in each level traverse left to right and set value to the depth of subtree
                memoryMap[memoryMapIndex] = (byte) d;
                depthMap[memoryMapIndex] = (byte) d;
                memoryMapIndex ++;
            }
        }

        subpages = newSubpageArray(maxSubpageAllocs);
    }

    /** Creates a special chunk that is not pooled. */
    PoolChunk(PoolArena<T> arena, T memory, int size) {
        unpooled = true;
        this.arena = arena;
        this.memory = memory;
        memoryMap = null;
        depthMap = null;
        subpages = null;
        subpageOverflowMask = 0;
        pageSize = 0;
        pageShifts = 0;
        maxOrder = 0;
        unusable = (byte) (maxOrder + 1);
        chunkSize = size;
        log2ChunkSize = log2(chunkSize);
        maxSubpageAllocs = 0;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpageArray(int size) {
        return new PoolSubpage[size];
    }

    @Override
    public int usage() {
        final int freeBytes = this.freeBytes;
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    long allocate(int normCapacity) {
        //如果分配的是以page为单位
        if ((normCapacity & subpageOverflowMask) != 0) { // >= pageSize
            return allocateRun(normCapacity);
        } else {
            //否则  这次以16字节为例子，所以走的是allocateSubpage
            return allocateSubpage(normCapacity);
        }
    }

    /**
     * Update method used by allocate
     * This is triggered only when a successor is allocated and all its predecessors
     * need to update their state
     * The minimal depth at which subtree rooted at id has some free space
     *
     * @param id id
     */
    //这里其实是将循环将兄弟节点的值替换成父节点的值, 我们可以通过注释仔细的进行逻辑分析。如果实在理解有困难,
    // 我通过画图帮助大家理解，简单起见, 我们这里只设置三层：
    private void updateParentsAlloc(int id) {
        while (id > 1) {
            //取到当前节点的父节点的 id
            int parentId = id >>> 1;
            //获取当前节点的值
            byte val1 = value(id);
            //找到当前节点的兄弟节点
            byte val2 = value(id ^ 1);
            //如果当前节点值小于兄弟节点, 则保存当前节点值到 val, 否则, 保存兄弟节点值到 val
            //如果当前节点是不可用, 则当前节点值是 12, 大于兄弟节点的值, 所以这里将兄弟节点的值进行保存
            byte val = val1 < val2 ? val1 : val2;
            //将 val 的值设置为父节点下标所对应的值
            setValue(parentId, val);
            //id 设置为父节点 id, 继续循环
            id = parentId;
        }
    }

    /**
     * Update method used by free
     * This needs to handle the special case when both children are completely free
     * in which case parent be directly allocated on request of size = child-size * 2
     *
     * @param id id
     */
    private void updateParentsFree(int id) {
        int logChild = depth(id) + 1;
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1);
            logChild -= 1; // in first iteration equals log, subsequently reduce 1 from logChild as we traverse up

            if (val1 == logChild && val2 == logChild) {
                setValue(parentId, (byte) (logChild - 1));
            } else {
                byte val = val1 < val2 ? val1 : val2;
                setValue(parentId, val);
            }

            id = parentId;
        }
    }

    /**
     * Algorithm to allocate an index in memoryMap when we query for a free node
     * at depth d
     *
     * @param d depth
     * @return index in memoryMap
     */

    //这里是实际上是从第一个节点往下找, 找到层级为 d 未被使用的节点, 我们可以通过注释体会其逻辑。找到相关节点后
    // 通过 setValue 将当前节点设置为不可用, 其中 id 是当前节点的下标, unusable 代表一个不可用的值, 这里是 12, 因为我
    // 们的层级只有 12 层, 所以设置为 12 之后就相当于标记不可用。设置成不可用之后, 通过 updateParentsAlloc(id)逐层设
    // 置为被使用。我们跟进 updateParentsAlloc()方法：
    private int allocateNode(int d) {
        //下标初始值为 1
        int id = 1;
        //代表当前层级第一个节点的初始下标
        int initial = - (1 << d); // has last d bits = 0 and rest all = 1
        //获取第一个节点的值
        byte val = value(id);
        //如果值大于层级, 说明 chunk 不可用
        if (val > d) { // unusable
            return -1;
        }
        //当前下标对应的节点值如果小于层级, 或者当前下标小于层级的初始下标
        while (val < d || (id & initial) == 0) { // id & initial == 1 << d for all ids at depth d, for < d it is 0
            //当前下标乘以 2, 代表下当前节点的子节点的起始位置
            id <<= 1;
            //获得 id 位置的值
            val = value(id);
            //如果当前节点值大于层数(节点不可用)
            if (val > d) {
                //id 为偶数则+1, id 为奇数则-1(拿的是其兄弟节点)
                id ^= 1;
                //获取 id 的值
                val = value(id);
            }
        }
        byte value = value(id);
        assert value == d && (id & initial) == 1 << d : String.format("val = %d, id & initial = %d, d = %d",
                value, id & initial, d);
        //将找到的节点设置为不可用
        setValue(id, unusable); // mark as unusable
        //逐层往上标记被使用
        updateParentsAlloc(id);
        return id;
    }

    /**
     * Allocate a run of pages (>=1)
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateRun(int normCapacity) {
        //int d = maxOrder - (log2(normCapacity) - pageShifts) 表示根据 normCapacity 计算出第几层；
        int d = maxOrder - (log2(normCapacity) - pageShifts);
        //int id = allocateNode(d) 表示根据层级关系, 去分配一个节点, 其中 id 代表 memoryMap 中的下标。
        //我们跟到 allocateNode()方法中：
        int id = allocateNode(d);
        if (id < 0) {
            return id;
        }
        freeBytes -= runLength(id);
        return id;
    }

    /**
     * Create/ initialize a new PoolSubpage of normCapacity
     * Any PoolSubpage created/ initialized here is added to subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    //首先, 通过 PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity) 这种方式找到 head 节点, 实际
    // 上这里 head, 就是我们刚才分析的 tinySubpagePools 属性的第一个节点, 也就是对应 16B 的那个节点。int d =
    // maxOrder 是将 11 赋值给 d, 也就是在内存树的第 11 层取节点, 这部分上一小节剖析过了。int id = allocateNode(d) 这
    // 里获取的是上一小节我们分析过的, 字节数组 memoryMap 的下标, 这里指向一个 page, 如果第一次分配, 指向的是
    // 0-8k 的那个 page, 上一小节对此进行详细的剖析这里不再赘述。final PoolSubpage<T>[] subpages = this.subpages
    // 这一步, 是拿到 PoolChunk 中成员变量 subpages 的值, 也是个 PoolSubpage 的数组, 在 PoolChunk 进行初始化的时
    // 候, 也会初始化该数组, 长度为 2048。也就是说每个 chunk 都维护着一个 subpage 的列表, 如果每一个 page 级别的
    // 内存都需要被切分成子 page, 则会将这个这个 page 放入该列表中, 专门用于分配子 page, 所以这个列表中的
    // subpage, 其实就是一个用于切分的 page。
    private long allocateSubpage(int normCapacity) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.

        //通过 PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity) 这种方式找到 head 节点, 实际
        // 上这里 head, 就是我们刚才分析的 tinySubpagePools 属性的第一个节点, 也就是对应 16B 的那个节点。
        PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity);
        synchronized (head) {
            //int d = maxOrder 是将 11 赋值给 d, 也就是在内存树的第 11 层取节点
            int d = maxOrder; // subpages are only be allocated from pages i.e., leaves
            //表示在第 11 层分配节点
            //int id = allocateNode(d) 这
            // 里获取的是上一小节我们分析过的, 字节数组 memoryMap 的下标, 这里指向一个 page, 如果第一次分配, 指向的是
            // 0-8k 的那个 page, 上一小节对此进行详细的剖析这里不再赘述。
            int id = allocateNode(d);
            if (id < 0) {
                return id;
            }
            //获取初始化的 subpage
            //final PoolSubpage<T>[] subpages = this.subpages
            // 这一步, 是拿到 PoolChunk 中成员变量 subpages 的值, 也是个 PoolSubpage 的数组, 在 PoolChunk 进行初始化的时
            // 候, 也会初始化该数组, 长度为 2048。也就是说每个 chunk 都维护着一个 subpage 的列表, 如果每一个 page 级别的
            // 内存都需要被切分成子 page, 则会将这个这个 page 放入该列表中, 专门用于分配子 page, 所以这个列表中的
            // subpage, 其实就是一个用于切分的 page
            final PoolSubpage<T>[] subpages = this.subpages;
            final int pageSize = this.pageSize;

            freeBytes -= pageSize;
            //表示第几个 subpageIdx
            //int subpageIdx = subpageIdx(id) 这一步是通过 id 拿到这个 PoolSubpage 数组的下标, 如果 id 对应的 page 是 0-8k
            // 的节点, 这里拿到的下标就是 0。
            //memorymap是为了快速找到，这里是去subpages中找到对应的
            int subpageIdx = subpageIdx(id);
            PoolSubpage<T> subpage = subpages[subpageIdx];
            // 因为默认 subpages 只是创建一个数组, 并没有往数组中
            // 赋值, 所以第一次走到这里会返回 true, 跟到 if 块中：
            if (subpage == null) {
                //如果 subpage 为空
                subpage = new PoolSubpage<T>(head, this, id, runOffset(id), pageSize, normCapacity);
                //则将当前的下标赋值为 subpage
                subpages[subpageIdx] = subpage;
            } else {
                subpage.init(head, normCapacity);
            }
            //去除一个子page
            //创建完了一个 subpage, 我们就可以通过 subpage.allocate()方法进行内存分配了。我们跟到 allocate()方法中:
            return subpage.allocate();
        }
    }

    /**
     * Free a subpage or a run of pages
     * When a subpage is freed from PoolSubpage, it might be added back to subpage pool of the owning PoolArena
     * If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize, we can
     * completely free the owning Page so it is available for subsequent allocations
     *
     * @param handle handle to free
     */

    //if (bitmapIdx != 0)这 里判断是当前缓冲区分配的级别是 Page 还是 Subpage, 如果是 Subpage, 则会找到相关的
    // Subpage 将其位图标记为 0，如果不是 subpage, 这里通过分配内存的反向标记, 将该内存标记为未使用。这段逻辑大
    // 家可以自行分析, 如果之前分配相关的知识掌握扎实的话, 这里的逻辑也不是很难。回到 PooledByteBuf 的 deallocate
    // 方法中
    void free(long handle) {
        int memoryMapIdx = memoryMapIdx(handle);
        int bitmapIdx = bitmapIdx(handle);

        if (bitmapIdx != 0) { // free a subpage
            PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
            assert subpage != null && subpage.doNotDestroy;

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            PoolSubpage<T> head = arena.findSubpagePoolHead(subpage.elemSize);
            synchronized (head) {
                if (subpage.free(head, bitmapIdx & 0x3FFFFFFF)) {
                    return;
                }
            }
        }
        freeBytes += runLength(memoryMapIdx);
        setValue(memoryMapIdx, depth(memoryMapIdx));
        updateParentsFree(memoryMapIdx);
    }

    //从上面代码中，看出通过 memoryMapIdx(handle)找到 memoryMap 的下标, 其实就是 handle 的值。bitmapIdx(handle)
    // 是有关 subPage 中使用到的逻辑, 如果是 page 级别的分配, 这里只返回 0, 所以进入到 if 块中。if 中首先断言当前节
    // 点是不是不可用状态, 然后通过 init 方法进行初始化。其中 runOffset(memoryMapIdx)表示偏移量, 偏移量相当于分配
    // 给缓冲区的这块内存相对于 chunk 中申请的内存的首地址偏移了多少。参数 runLength(memoryMapIdx), 表示根据下
    // 标获取可分配的最大长度。我们跟到 init()方法中, 这里会走到 PooledByteBuf 的 init()方法：
    void initBuf(PooledByteBuf<T> buf, long handle, int reqCapacity) {
        //memoryMapIdx(handle)找到 memoryMap 的下标, 其实就是 handle 的值。
        int memoryMapIdx = memoryMapIdx(handle);
        //bitmapIdx(handle)如果是 page 级别的分配, 这里只返回 0
        int bitmapIdx = bitmapIdx(handle);
        if (bitmapIdx == 0) {
            byte val = value(memoryMapIdx);
            //if 中首先断言当前节点是不是不可用状态
            assert val == unusable : String.valueOf(val);
            //然后通过 init 方法进行初始化
            //其中 runOffset(memoryMapIdx)表示偏移量   偏移量相当于分配给缓冲区的这块内存相对于 chunk 中申请的内存的首地址偏移了多少
            //参数 runLength(memoryMapIdx), 表示根据下标获取可分配的最大长度
            //我们跟到 init()方法中, 这里会走到 PooledByteBuf 的 init()方法：
            buf.init(this, handle, runOffset(memoryMapIdx), reqCapacity, runLength(memoryMapIdx),
                     arena.parent.threadCache());
        } else {
            //这部分在前面我们剖析过, 相信大家不会陌生, 这里有区别的是 if (bitmapIdx == 0) 的判断, 这里的bitmapIdx不会是
            // 0, 这样, 就会走到 initBufWithSubpage(buf, handle, bitmapIdx, reqCapacity)方法中，跟到 initBufWithSubpage()方法：
            initBufWithSubpage(buf, handle, bitmapIdx, reqCapacity);
        }
    }

    void initBufWithSubpage(PooledByteBuf<T> buf, long handle, int reqCapacity) {
        //面代码中，调用了 bitmapIdx()方法，有关 bitmapIdx(handle)相关的逻辑, 会在后续的章节进行剖析, 这里继续往里
        // 跟，看 initBufWithSubpage()的逻辑：
        initBufWithSubpage(buf, handle, bitmapIdx(handle), reqCapacity);
    }

    private void initBufWithSubpage(PooledByteBuf<T> buf, long handle, int bitmapIdx, int reqCapacity) {
        assert bitmapIdx != 0;
        // 首先拿到 memoryMapIdx, 这里会将我们之前计算 handle 传入, 跟进去：
        int memoryMapIdx = memoryMapIdx(handle);

        PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
        assert subpage.doNotDestroy;
        assert reqCapacity <= subpage.elemSize;
        //init 方法, 因为我们是以 PooledUnsafeDirectByteBuf 为例, 所以这里走的是 PooledUnsafeDirectByteBuf
        // 的 init()方法。进入 init()方法：
        //进入PooledByteBuf

        //将 PooledUnsafeDirectByteBuf 的各个属性进行了初始化。
        // this.chunk = chunk 这里初始化了 chunk, 代表当前的 ByteBuf 是在哪一块内存中分配的。
        //this.handle = handle 这里初始化了 handle, 代表当前的 ByteBuf 是这块内存的哪个连续内存
        buf.init(
            this, handle,
            //这里的偏移量就是, 原来 page 的偏移量+子块的偏移量：bitmapIdx & 0x3FFFFFFF 代表当前分配
                // 的子 page 是属于第几个子 page。(bitmapIdx & 0x3FFFFFFF) * subpage.elemSize 表示在当前 page 的偏移量。这样, 分
                // 配的 ByteBuf 在内存读写的时候, 就会根据偏移量进行读写。最后，我们跟到 init()方法中：
            runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize, reqCapacity, subpage.elemSize,
            arena.parent.threadCache());
    }

    private byte value(int id) {
        return memoryMap[id];
    }

    private void setValue(int id, byte val) {
        memoryMap[id] = val;
    }

    private byte depth(int id) {
        return depthMap[id];
    }

    private static int log2(int val) {
        // compute the (0-based, with lsb = 0) position of highest set bit i.e, log2
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
    }

    private int runLength(int id) {
        // represents the size in #bytes supported by node 'id' in the tree
        return 1 << log2ChunkSize - depth(id);
    }

    private int runOffset(int id) {
        // represents the 0-based offset in #bytes from start of the byte-array chunk
        int shift = id ^ 1 << depth(id);
        return shift * runLength(id);
    }

    private int subpageIdx(int memoryMapIdx) {
        return memoryMapIdx ^ maxSubpageAllocs; // remove highest set bit, to get offset
    }
    //这里将其强制转化为 int 类型, 也就是去掉高 32 位, 这样就得到 memoryMapIdx
    private static int memoryMapIdx(long handle) {
        return (int) handle;
    }

    private static int bitmapIdx(long handle) {
        return (int) (handle >>> Integer.SIZE);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        return freeBytes;
    }

    @Override
    public String toString() {
        return new StringBuilder()
            .append("Chunk(")
            .append(Integer.toHexString(System.identityHashCode(this)))
            .append(": ")
            .append(usage())
            .append("%, ")
            .append(chunkSize - freeBytes)
            .append('/')
            .append(chunkSize)
            .append(')')
            .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }
}
