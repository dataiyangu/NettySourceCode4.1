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

final class PoolSubpage<T> implements PoolSubpageMetric {

    final PoolChunk<T> chunk;
    private final int memoryMapIdx;
    private final int runOffset;
    private final int pageSize;
    private final long[] bitmap;

    PoolSubpage<T> prev;
    PoolSubpage<T> next;

    boolean doNotDestroy;
    int elemSize;
    private int maxNumElems;
    private int bitmapLength;
    private int nextAvail;
    private int numAvail;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    //传入 head, 就是我们刚
    // 才提到过的 tinySubpagePools 属性中的节点, 如果我们分配的 16 字节的缓冲区, 则这里对应的就是第一个节点
    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        //chunk 代表其子页属于哪个 chunk；bitmap 用于记录子页的内存分配情况；prev 和 next, 代表子页是按照双向链表进
        // 行关联的, 这里分别指向上一个和下一个节点；elemSize 属性, 代表的就是这个子页是按照多大内存进行划分的, 如果
        // 按照 1KB 划分, 则可以划分出 8 个子页；简单介绍了内存分配的数据结构
        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;
        this.runOffset = runOffset;
        this.pageSize = pageSize;
        //这
        // 里重点关注属性 bitmap, 这是一个 long 类型的数组, 初始大小为 8, 这里只是初始化的大小, 真正的大小要根据将
        // page 切分多少块而确定，这里将属性进行了赋值, 我们跟到 init()方法中：
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64
        //进入
        init(head, elemSize);
    }

    void init(PoolSubpage<T> head, int elemSize) {
        doNotDestroy = true;
        //this.elemSize = elemSize 表示保存当前分配的缓冲区大小, 这里我们以 16 字节举例, 所以这里是 16。
        this.elemSize = elemSize;
        if (elemSize != 0) {
            //maxNumElems= numAvail = pageSize / elemSize  这里初始化了两个属性 maxNumElems, numAvail, 值都为 pageSize / elemSize,
            // 表示一个 page 大小除以分配的缓冲区大小, 也就是表示当前 page 被划分了多少分。
            //分成多少份
            //numAvail 则表示剩余可用的块数, 由于第一次分配都是可用的, 所以 numAvail=maxNumElems；
            maxNumElems = numAvail = pageSize / elemSize;
            nextAvail = 0;
            //bitmapLength 表示 bitmap 的实际大小, 刚才我们分析过, bitmap 初始化的大小为 8, 但实际上并不一定需要 8 个元素,
            // 元素个数要根据 page 切分的子块而定, 这里的大小是所切分的子块数除以 64。
            //为什么除以64？ bitmap中是long类型的节点，long类型节点是64位的，通过page/64决定bitmap中的数量
            bitmapLength = maxNumElems >>> 6;
            //再往下看, if ((maxNumElems & 63) != 0) 判断 maxNumElems 也就是当前配置所切分的子块是不是 64 的倍数, 如果
            // 不是, 则 bitmapLength 加 1,最后通过循环, 将其分配的大小中的元素赋值为 0。
            if ((maxNumElems & 63) != 0) {
                bitmapLength ++;
            }

            for (int i = 0; i < bitmapLength; i ++) {
                ////bitmap 标识哪个子 page 被分配
                // //0 标识未分配, 1 表示已分配
                bitmap[i] = 0;
            }
        }
        // /加到 arena 里面
        //进入 PoolSubpage 的 addToPool(head)方法：
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     */
    long allocate() {
        if (elemSize == 0) {
            return toHandle(0);
        }

        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }
        //取一个 bitmap 中可用的 id(绝对 id)

        //其中 bitmapIdx 表示从 bitmap 中找到一个可用的 bit 位的下标, 注意, 这里是 bit 的下标, 并不是数组的下标, 我们之
        // 前分析过, 因为每一比特位代表一个子块的内存分配情况, 通过这个下标就可以知道那个比特位是未分配状态，我们跟
        // 进去：
        //找到一个可用的下标
        final int bitmapIdx = getNextAvail();
        //除以 64(bitmap 的相对下标)
        //通过 int q = bitmapIdx >>> 6 获取 bitmap 中 bitmapIdx 所属元素的数组下标。
        int q = bitmapIdx >>> 6;
        //除以 64 取余, 其实就是当前绝对 id 的偏移量
        //表示获取 bitmapIdx 的位置是从当前元素最低位开始的第几个比特位
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) == 0;
        //当前位标记为 1
        //bitmap[q] |= 1L << r 是将
        // bitmap 的位置设置为不可用, 也就是比特位设置为 1, 表示已占用
        bitmap[q] |= 1L << r;
        //如果可用的子 page 为 0
        //可用的子 page-1
        //然后将可用子配置的数量 numAvail 减 1
        //已经没有地方可以用了。
        if (-- numAvail == 0) {
            //则移除相关子 pag
            //如果没有可用子 page 的数量, 则会将 PoolArena 中的数组 tinySubpagePools 所关联的 subpage 进行移除

            removeFromPool();
        }
        // bitmapIdx 转换成 handler
        // 最后通过 toHandle(bitmapIdx)获取当前子块的handle,
        // 上一小节我们知道handle指向的是当前chunk中的唯一的一块内存, 我
        // 们跟进 toHandle(bitmapIdx)中：
        //返回对的数字就可以指向 chunk 中唯一的一块内存
        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        if (elemSize == 0) {
            return true;
        }
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        bitmap[q] ^= 1L << r;

        setNextAvail(bitmapIdx);

        if (numAvail ++ == 0) {
            addToPool(head);
            return true;
        }

        if (numAvail != maxNumElems) {
            return true;
        } else {
            // Subpage not in use (numAvail == maxNumElems)
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            removeFromPool();
            return false;
        }
    }

    // 这里的 head 我们刚才讲过, 是 Arena 中数组 tinySubpagePools 中的元素, 通过以上逻辑, 就会将新创建的 Subpage
    // 通过双向链表的方式关联到 tinySubpagePools 中的元素, 我们以 16 字节为例, 关联关系如图所示：
    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }
    ////上述代码片段中的 nextAvail, 表示下一个可用的 bitmapIdx, 在释放的时候的会被标记, 标记被释放的子块对应
    //     // bitmapIdx 的下标, 如果<0 则代表没有被释放的子块, 则通过 findNextAvail 方法进行查找，继续跟进 findNextAvail()
    //     // 方法:
    private int getNextAvail() {
        int nextAvail = this.nextAvail;
        if (nextAvail >= 0) {
            //一个子 page 被释放之后, 会记录当前子 page 的 bitmapIdx 的位置, 下次分配可以直接通过 bitmapIdx 拿到一个子page
            this.nextAvail = -1;
            return nextAvail;
        }
        return findNextAvail();
    }

    //这里会遍历 bitmap 中的每一个元素, 如果当前元素中所有的比特位并没有全部标记被使用, 则通过 findNextAvail0(i,
    // bits)方法一个一个往后找标记未使用的比特位。再继续跟 findNextAvail0()：
    private int findNextAvail() {
        //当前 long 数组
        final long[] bitmap = this.bitmap;
        //获取其长度
        final int bitmapLength = this.bitmapLength;
        for (int i = 0; i < bitmapLength; i ++) {
            //第 i 个
            long bits = bitmap[i];
            //!=-1 说明 64 位没有全部占满
            if (~bits != 0) {
                //找下一个节点     如果当前的比特位没有都被占满
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }
    //这里从当前元素的第一个比特位开始找, 直到找到一个标记为 0 的比特位, 并返回当前比特位的下标, 大致流程如下图
    // 所示：
    private int findNextAvail0(int i, long bits) {
        //多少份
        final int maxNumElems = this.maxNumElems;
        //乘以 64, 代表当前 long 的第一个下标
        final int baseVal = i << 6;
        //循环 64 次(指代当前的下标)
        for (int j = 0; j < 64; j ++) {
            //第一位为 0(如果是 2 的倍数, 则第一位就是 0)
            if ((bits & 1) == 0) {
                //这里相当于加, 将 i*64 之后加上 j, 获取绝对下标
                int val = baseVal | j;
                //小于块数(不能越界)
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            //当前下标不为 0
            //右移一位
            bits >>>= 1;
        }
        return -1;
    }
    //(long) bitmapIdx << 32 是将 bitmapIdx 左移 32 位, 而 32 位正好是一个 int 的长度, 这样, 通过 (long) bitmapIdx <<
    // 32 | memoryMapIdx 计算, 就可以将 memoryMapIdx, 也就是 page 所属的下标的二进制数保存在 (long) bitmapIdx
    // << 32 的低 32 位中。0x4000000000000000L 是一个最高位是 1 并且所有低位都是 0 的二进制数, 这样通过按位或的
    // 方式可以将 (long) bitmapIdx << 32 | memoryMapIdx 计算出来的结果保存在 0x4000000000000000L 的所有低位中,
    // 这样, 返回对的数字就可以指向 chunk 中唯一的一块内存
    private long toHandle(int bitmapIdx) {
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }

    @Override
    public String toString() {
        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return String.valueOf('(') + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
               ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        return maxNumElems;
    }

    @Override
    public int numAvailable() {
        return numAvail;
    }

    @Override
    public int elementSize() {
        return elemSize;
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
