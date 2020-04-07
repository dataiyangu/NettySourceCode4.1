/*
 * Copyright 2013 The Netty Project
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
package io.netty.util.concurrent;

import java.util.Arrays;

final class DefaultFutureListeners {

    private GenericFutureListener<? extends Future<?>>[] listeners;
    private int size;
    private int progressiveSize; // the number of progressive listeners

    @SuppressWarnings("unchecked")
    //        在
        // DefaultFutureListeners 类中也定义了一个成员变量 listeners, 类型为 GenericFutureListener 数组。构造方法中初始
        // 化 listeners 这个数组, 并且数组中第一个值赋值为我们第一次添加的 GenericFutureListener, 第二个赋值为我们第二次
        // 添加的 GenericFutureListener。回到 addListener0 方法中：
    DefaultFutureListeners(
            GenericFutureListener<? extends Future<?>> first, GenericFutureListener<? extends Future<?>> second) {
        listeners = new GenericFutureListener[2];
        //第 0 个
        listeners[0] = first;
        //第 1 个
        listeners[1] = second;
        //代码省略
        size = 2;
        if (first instanceof GenericProgressiveFutureListener) {
            progressiveSize ++;
        }
        if (second instanceof GenericProgressiveFutureListener) {
            progressiveSize ++;
        }
    }

    // 这
    // 里的逻辑也比较简单, 就是为当前的数组对象 listeners 中追加新的 GenericFutureListener 对象, 如果 listeners 容量
    // 不足则进行扩容操作。
    // 根据以上逻辑, 就完成了 listener 的添加逻辑。那么再看我们刚才遗留的问题, 如果
    // writeAndFlush 和 addListener 是同步进行的, writeAndFlush 执行回调时还没有 addListener 还没有执行回调, 那么回
    // 调是如何执行的呢?回到 DefaultPromise 的 addListener 中：
    public void add(GenericFutureListener<? extends Future<?>> l) {
        GenericFutureListener<? extends Future<?>>[] listeners = this.listeners;
        final int size = this.size;
        if (size == listeners.length) {
            this.listeners = listeners = Arrays.copyOf(listeners, size << 1);
        }
        listeners[size] = l;
        this.size = size + 1;

        if (l instanceof GenericProgressiveFutureListener) {
            progressiveSize ++;
        }
    }

    public void remove(GenericFutureListener<? extends Future<?>> l) {
        final GenericFutureListener<? extends Future<?>>[] listeners = this.listeners;
        int size = this.size;
        for (int i = 0; i < size; i ++) {
            if (listeners[i] == l) {
                int listenersToMove = size - i - 1;
                if (listenersToMove > 0) {
                    System.arraycopy(listeners, i + 1, listeners, i, listenersToMove);
                }
                listeners[-- size] = null;
                this.size = size;

                if (l instanceof GenericProgressiveFutureListener) {
                    progressiveSize --;
                }
                return;
            }
        }
    }

    public GenericFutureListener<? extends Future<?>>[] listeners() {
        return listeners;
    }

    public int size() {
        return size;
    }

    public int progressiveSize() {
        return progressiveSize;
    }
}
