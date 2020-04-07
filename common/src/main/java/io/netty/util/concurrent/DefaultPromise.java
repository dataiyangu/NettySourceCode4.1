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

import io.netty.util.Signal;
import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.ThrowableUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static io.netty.util.internal.ObjectUtil.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class DefaultPromise<V> extends AbstractFuture<V> implements Promise<V> {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(DefaultPromise.class);
    private static final InternalLogger rejectedExecutionLogger =
            InternalLoggerFactory.getInstance(DefaultPromise.class.getName() + ".rejectedExecution");
    private static final int MAX_LISTENER_STACK_DEPTH = Math.min(8,
            SystemPropertyUtil.getInt("io.netty.defaultPromise.maxListenerStackDepth", 8));
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultPromise, Object> RESULT_UPDATER;
    private static final Signal SUCCESS = Signal.valueOf(DefaultPromise.class, "SUCCESS");
    private static final Signal UNCANCELLABLE = Signal.valueOf(DefaultPromise.class, "UNCANCELLABLE");
    private static final CauseHolder CANCELLATION_CAUSE_HOLDER = new CauseHolder(ThrowableUtil.unknownStackTrace(
            new CancellationException(), DefaultPromise.class, "cancel(...)"));

    static {
        @SuppressWarnings("rawtypes")
        AtomicReferenceFieldUpdater<DefaultPromise, Object> updater =
                PlatformDependent.newAtomicReferenceFieldUpdater(DefaultPromise.class, "result");
        RESULT_UPDATER = updater == null ? AtomicReferenceFieldUpdater.newUpdater(DefaultPromise.class,
                                                                                  Object.class, "result") : updater;
    }

    private volatile Object result;
    private final EventExecutor executor;
    /**
     * One or more listeners. Can be a {@link GenericFutureListener} or a {@link DefaultFutureListeners}.
     * If {@code null}, it means either 1) no listeners were added yet or 2) all listeners were notified.
     *
     * Threading - synchronized(this). We must support adding listeners when there is no EventExecutor.
     */
    private Object listeners;
    /**
     * Threading - synchronized(this). We are required to hold the monitor to use Java's underlying wait()/notifyAll().
     */
    private short waiters;

    /**
     * Threading - synchronized(this). We must prevent concurrent notification and FIFO listener notification if the
     * executor changes.
     */
    private boolean notifyingListeners;

    /**
     * Creates a new instance.
     *
     * It is preferable to use {@link EventExecutor#newPromise()} to create a new promise
     *
     * @param executor
     *        the {@link EventExecutor} which is used to notify the promise once it is complete.
     *        It is assumed this executor will protect against {@link StackOverflowError} exceptions.
     *        The executor may be used to avoid {@link StackOverflowError} by executing a {@link Runnable} if the stack
     *        depth exceeds a threshold.
     *
     */
    public DefaultPromise(EventExecutor executor) {
        this.executor = checkNotNull(executor, "executor");
    }

    /**
     * See {@link #executor()} for expectations of the executor.
     */
    protected DefaultPromise() {
        // only for subclasses
        executor = null;
    }

    @Override
    public Promise<V> setSuccess(V result) {
        if (setSuccess0(result)) {
            notifyListeners();
            return this;
        }
        throw new IllegalStateException("complete already: " + this);
    }

    @Override
    public boolean trySuccess(V result) {
        if (setSuccess0(result)) {
            //设
            // 置完成功状态之后, 则会通过 notifyListeners()执行监听中的回调。我们看用户代码
            notifyListeners();
            return true;
        }
        return false;
    }

    @Override
    public Promise<V> setFailure(Throwable cause) {
        if (setFailure0(cause)) {
            notifyListeners();
            return this;
        }
        throw new IllegalStateException("complete already: " + this, cause);
    }

    @Override
    public boolean tryFailure(Throwable cause) {
        //跟进setFailure0
        if (setFailure0(cause)) {
            //我们关注 notifyListeners()这个方法, 这个方法是执行添加监听的回调函数, 当 writeAndFlush 和
            // addListener 是异步执行的时候, 这里有可能添加已经添加, 所以通过这个方法可以调用添加监听后的回调。如果
            // writeAndFlush 和 addListener 是同步执行的时候, 也就是都在 NioEventLoop 线程中执行的时候, 那么走到这里
            // addListener 还没执行, 所以这里不能回调添加监听的回调函数, 那么回调是什么时候执行的呢?我们在剖析
            // addListener 步骤的时候会给大家分析
            notifyListeners();
            return true;
        }
        return false;
    }

    @Override
    public boolean setUncancellable() {
        if (RESULT_UPDATER.compareAndSet(this, null, UNCANCELLABLE)) {
            return true;
        }
        Object result = this.result;
        return !isDone0(result) || !isCancelled0(result);
    }

    @Override
    //我
    // 们看到首先会拿到result对象, 然后判断result不为空, 并且不是UNCANCELLABLE, 并且不属于CauseHolder对象.
    // 我们刚才分析如果promise设置为成功装载, 则result为SUCCESS, 所以这里条件成立,
    public boolean isSuccess() {
        Object result = this.result;
        return result != null && result != UNCANCELLABLE && !(result instanceof CauseHolder);
    }

    @Override
    public boolean isCancellable() {
        return result == null;
    }

    @Override
    public Throwable cause() {
        Object result = this.result;
        return (result instanceof CauseHolder) ? ((CauseHolder) result).cause : null;
    }

    @Override
    //这
    // 里通过 addListener0 方法添加 listener, 因为添加 listener 有可能会在不同的线程中操作, 比如用户线程和
    // NioEventLoop 线程, 为了防止并发问题, 这里简单粗暴的加了个 synchronized 关键字。跟到 addListener0 方法中：
    public Promise<V> addListener(GenericFutureListener<? extends Future<? super V>> listener) {
        checkNotNull(listener, "listener");

        synchronized (this) {
            // addListener0 方法添加 listener
            //因为添加 listener 有可能会在不同的线程中操作  比如用户线程和
            //NioEventLoop 线程, 为了防止并发问题, 这里简单粗暴的加了个 synchronized 关键字。跟到 addListener0 方法中：
            addListener0(listener);
        }

        //这
        // 里判断 result 不为 null 并且不为 UNCANCELLABLE, 则就表示完成。因为成功的状态是 SUCCESS, 所以 flush 成功
        // 这里会返回 true
        //这也解释刚才的问题, 在
        // 同步操作中, writeAndFlush 在执行回调时并没有添加 listener, 所以添加 listener 的时候会判断 writeAndFlush 的执行状
        //     //态, 如果状态时完成, 则会这里执行回调。同样, 在异步操作中, 走到这里 writeAndFlush 可能还没完成, 所以这里不会
        //     // 执行回调, 由 writeAndFlush 执行回调。所以, 无论 writeAndFlush 和 addListener 谁先完成, 都可以执行到回调方法。
        //     // 跟到 notifyListeners()方法中
        if (isDone()) {
            notifyListeners();
        }

        return this;
    }

    @Override
    public Promise<V> addListeners(GenericFutureListener<? extends Future<? super V>>... listeners) {
        checkNotNull(listeners, "listeners");

        synchronized (this) {
            for (GenericFutureListener<? extends Future<? super V>> listener : listeners) {
                if (listener == null) {
                    break;
                }
                addListener0(listener);
            }
        }

        if (isDone()) {
            notifyListeners();
        }

        return this;
    }

    @Override
    public Promise<V> removeListener(final GenericFutureListener<? extends Future<? super V>> listener) {
        checkNotNull(listener, "listener");

        synchronized (this) {
            removeListener0(listener);
        }

        return this;
    }

    @Override
    public Promise<V> removeListeners(final GenericFutureListener<? extends Future<? super V>>... listeners) {
        checkNotNull(listeners, "listeners");

        synchronized (this) {
            for (GenericFutureListener<? extends Future<? super V>> listener : listeners) {
                if (listener == null) {
                    break;
                }
                removeListener0(listener);
            }
        }

        return this;
    }

    @Override
    public Promise<V> await() throws InterruptedException {
        if (isDone()) {
            return this;
        }

        if (Thread.interrupted()) {
            throw new InterruptedException(toString());
        }

        checkDeadLock();

        synchronized (this) {
            while (!isDone()) {
                incWaiters();
                try {
                    wait();
                } finally {
                    decWaiters();
                }
            }
        }
        return this;
    }

    @Override
    public Promise<V> awaitUninterruptibly() {
        if (isDone()) {
            return this;
        }

        checkDeadLock();

        boolean interrupted = false;
        synchronized (this) {
            while (!isDone()) {
                incWaiters();
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Interrupted while waiting.
                    interrupted = true;
                } finally {
                    decWaiters();
                }
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        return this;
    }

    @Override
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return await0(unit.toNanos(timeout), true);
    }

    @Override
    public boolean await(long timeoutMillis) throws InterruptedException {
        return await0(MILLISECONDS.toNanos(timeoutMillis), true);
    }

    @Override
    public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
        try {
            return await0(unit.toNanos(timeout), false);
        } catch (InterruptedException e) {
            // Should not be raised at all.
            throw new InternalError();
        }
    }

    @Override
    public boolean awaitUninterruptibly(long timeoutMillis) {
        try {
            return await0(MILLISECONDS.toNanos(timeoutMillis), false);
        } catch (InterruptedException e) {
            // Should not be raised at all.
            throw new InternalError();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public V getNow() {
        Object result = this.result;
        if (result instanceof CauseHolder || result == SUCCESS) {
            return null;
        }
        return (V) result;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (RESULT_UPDATER.compareAndSet(this, null, CANCELLATION_CAUSE_HOLDER)) {
            checkNotifyWaiters();
            notifyListeners();
            return true;
        }
        return false;
    }

    @Override
    public boolean isCancelled() {
        return isCancelled0(result);
    }

    @Override
    public boolean isDone() {
        return isDone0(result);
    }

    @Override
    public Promise<V> sync() throws InterruptedException {
        await();
        rethrowIfFailed();
        return this;
    }

    @Override
    public Promise<V> syncUninterruptibly() {
        awaitUninterruptibly();
        rethrowIfFailed();
        return this;
    }

    @Override
    public String toString() {
        return toStringBuilder().toString();
    }

    protected StringBuilder toStringBuilder() {
        StringBuilder buf = new StringBuilder(64)
                .append(StringUtil.simpleClassName(this))
                .append('@')
                .append(Integer.toHexString(hashCode()));

        Object result = this.result;
        if (result == SUCCESS) {
            buf.append("(success)");
        } else if (result == UNCANCELLABLE) {
            buf.append("(uncancellable)");
        } else if (result instanceof CauseHolder) {
            buf.append("(failure: ")
                    .append(((CauseHolder) result).cause)
                    .append(')');
        } else if (result != null) {
            buf.append("(success: ")
                    .append(result)
                    .append(')');
        } else {
            buf.append("(incomplete)");
        }

        return buf;
    }

    /**
     * Get the executor used to notify listeners when this promise is complete.
     * <p>
     * It is assumed this executor will protect against {@link StackOverflowError} exceptions.
     * The executor may be used to avoid {@link StackOverflowError} by executing a {@link Runnable} if the stack
     * depth exceeds a threshold.
     * @return The executor used to notify listeners when this promise is complete.
     */
    protected EventExecutor executor() {
        return executor;
    }

    protected void checkDeadLock() {
        EventExecutor e = executor();
        if (e != null && e.inEventLoop()) {
            throw new BlockingOperationException(toString());
        }
    }

    /**
     * Notify a listener that a future has completed.
     * <p>
     * This method has a fixed depth of {@link #MAX_LISTENER_STACK_DEPTH} that will limit recursion to prevent
     * {@link StackOverflowError} and will stop notifying listeners added after this threshold is exceeded.
     * @param eventExecutor the executor to use to notify the listener {@code listener}.
     * @param future the future that is complete.
     * @param listener the listener to notify.
     */
    protected static void notifyListener(
            EventExecutor eventExecutor, final Future<?> future, final GenericFutureListener<?> listener) {
        checkNotNull(eventExecutor, "eventExecutor");
        checkNotNull(future, "future");
        checkNotNull(listener, "listener");
        notifyListenerWithStackOverFlowProtection(eventExecutor, future, listener);
    }
    //这
    // 里首先判断是否是 eventLoop 线程, 如果是 eventLoop 线程则执行 if 块中的逻辑, 如果不是 eventLoop 线程, 则把
    // 执行回调的逻辑封装成 task 丢到 EventLoop 的任务队列中异步执行。我们重点关注 notifyListenersNow()方法, 跟进去
    private void notifyListeners() {
        EventExecutor executor = executor();
        //如果是 eventLoop 线程则执行 if 块中的逻辑
        if (executor.inEventLoop()) {
            final InternalThreadLocalMap threadLocals = InternalThreadLocalMap.get();
            final int stackDepth = threadLocals.futureListenerStackDepth();
            if (stackDepth < MAX_LISTENER_STACK_DEPTH) {
                threadLocals.setFutureListenerStackDepth(stackDepth + 1);
                try {
                    notifyListenersNow();
                } finally {
                    threadLocals.setFutureListenerStackDepth(stackDepth);
                }
                return;
            }
        }
        //如果不是 eventLoop 线程, 则把
        //     // 执行回调的逻辑封装成 task 丢到 EventLoop 的任务队列中异步执行。我们重点关注 notifyListenersNow()方法, 跟进去
        //
        safeExecute(executor, new Runnable() {
            @Override
            public void run() {
                notifyListenersNow();
            }
        });
    }

    /**
     * The logic in this method should be identical to {@link #notifyListeners()} but
     * cannot share code because the listener(s) cannot be cached for an instance of {@link DefaultPromise} since the
     * listener(s) may be changed and is protected by a synchronized operation.
     */
    private static void notifyListenerWithStackOverFlowProtection(final EventExecutor executor,
                                                                  final Future<?> future,
                                                                  final GenericFutureListener<?> listener) {
        if (executor.inEventLoop()) {
            final InternalThreadLocalMap threadLocals = InternalThreadLocalMap.get();
            final int stackDepth = threadLocals.futureListenerStackDepth();
            if (stackDepth < MAX_LISTENER_STACK_DEPTH) {
                threadLocals.setFutureListenerStackDepth(stackDepth + 1);
                try {
                    notifyListener0(future, listener);
                } finally {
                    threadLocals.setFutureListenerStackDepth(stackDepth);
                }
                return;
            }
        }

        safeExecute(executor, new Runnable() {
            @Override
            public void run() {
                notifyListener0(future, listener);
            }
        });
    }

    private void notifyListenersNow() {
        Object listeners;
        synchronized (this) {
            // Only proceed if there are listeners to notify and we are not already notifying listeners.
            if (notifyingListeners || this.listeners == null) {
                return;
            }
            notifyingListeners = true;
            listeners = this.listeners;
            this.listeners = null;
        }
        ////在
        //     // 无限 for 循环中,
        for (;;) {
            if (listeners instanceof DefaultFutureListeners) {
                notifyListeners0((DefaultFutureListeners) listeners);
            } else {
                //首先首先判断 listeners 是不是 DefaultFutureListeners 类型, 根据我们之前的逻辑, 如果只添加了一
                //         //     // 个 listener, 则 listeners 是 GenericFutureListener 类型。通常在添加的时候只会添加一个 listener, 所以我们跟到 else
                //         //     // 块中的 notifyListener0 方法：
                notifyListener0(this, (GenericFutureListener<? extends Future<V>>) listeners);
            }
            // /代码省略
            synchronized (this) {
                if (this.listeners == null) {
                    // Nothing can throw from within this method, so setting notifyingListeners back to false does not
                    // need to be in a finally block.
                    notifyingListeners = false;
                    return;
                }
                listeners = this.listeners;
                this.listeners = null;
            }
        }
    }

    private void notifyListeners0(DefaultFutureListeners listeners) {
        GenericFutureListener<?>[] a = listeners.listeners();
        int size = listeners.size();
        for (int i = 0; i < size; i ++) {
            notifyListener0(this, a[i]);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    //我
    // 们看到, 这里执行了 GenericFutureListener 的中我们重写的回调函数 operationComplete。以上就是执行回调的相
    // 关逻辑。
    private static void notifyListener0(Future future, GenericFutureListener l) {
        try {
            l.operationComplete(future);
        } catch (Throwable t) {
            logger.warn("An exception was thrown by " + l.getClass().getName() + ".operationComplete()", t);
        }
    }
    //如
    // 果是第一次添加 listener, 则成员变量 listeners 为 null, 这样就把参数传入的 GenericFutureListener 赋值到成员变量
    // listeners 。 如 果 是 第 二 次 添 加 listener, listeners 不 为 空 , 会 走 到 else if 判 断 , 因 为 第 一 次 添 加 的 listener 是
    // GenericFutureListener 类型, 并不是 DefaultFutureListeners 类型, 所以 else if 判断返回 false, 进入到 else 块中。else
    // 块中, 通过 new 的方式创建一个 DefaultFutureListeners 对象并赋值到成员变量 listeners 中。DefaultFutureListeners
    // 的构造方法中, 第一个参数传入 DefaultPromise 中的成员变量 listeners, 也就是第一次添加的 GenericFutureListener
    // 对象, 第二个参数为第二次添加的 GenericFutureListener 对象, 这里通过两个 GenericFutureListener 对象包装成一个
    // DefaultFutureListeners 对象。我们看 listeners 的定义：
    private void addListener0(GenericFutureListener<? extends Future<? super V>> listener) {
        if (listeners == null) {
            //如果是第一次添加 listener, 则成员变量 listeners 为 null, 这样就把参数传入的 GenericFutureListener 赋值到成员变量
            // listeners 。
            listeners = listener;
        } else if (listeners instanceof DefaultFutureListeners) {

            //经
            // 过两次添加 listener, 属性 listeners 的值就变成了 DefaultFutureListeners 类型的对象, 如果第三次添加 listener, 则会
            // 走到 else if 块中, DefaultFutureListeners 对象通过调用 add 方法继续添加 listener。跟到 add 方法中：
            ((DefaultFutureListeners) listeners).add(listener);
        } else {
            //如 果 是 第 二 次 添 加 listener, listeners 不 为 空 , 会 走 到 else if 判 断 , 因 为 第 一 次 添 加 的 listener 是
            //     // GenericFutureListener 类型, 并不是 DefaultFutureListeners 类型, 所以 else if 判断返回 false, 进入到 else 块中。else
            //     // 块中, 通过 new 的方式创建一个 DefaultFutureListeners 对象并赋值到成员变量 listeners 中

            //DefaultFutureListeners
            //     // 的构造方法中, 第一个参数传入 DefaultPromise 中的成员变量 listeners, 也就是第一次添加的 GenericFutureListener
            //     // 对象, 第二个参数为第二次添加的 GenericFutureListener 对象, 这里通过两个 GenericFutureListener 对象包装成一个
            //     // DefaultFutureListeners 对象。我们看 listeners 的定义：

            // 这里是个 Object 类型, 所以可以保存任何类型的对象。再看 DefaultFutureListeners 的构造方法：
            listeners = new DefaultFutureListeners((GenericFutureListener<? extends Future<V>>) listeners, listener);
        }
    }

    private void removeListener0(GenericFutureListener<? extends Future<? super V>> listener) {
        if (listeners instanceof DefaultFutureListeners) {
            ((DefaultFutureListeners) listeners).remove(listener);
        } else if (listeners == listener) {
            listeners = null;
        }
    }

    private boolean setSuccess0(V result) {
        return setValue0(result == null ? SUCCESS : result);
    }

    private boolean setFailure0(Throwable cause) {
        return setValue0(new CauseHolder(checkNotNull(cause, "cause")));
    }
    //这
    // 里在 if 块中的 cas 操作, 会将参数 objResult 的值设置到 DefaultPromise 的成员变量 result 中, 表示当前操作为异常
    // 状态

    //回到 tryFailure 方法

    //同
    // 样, 在 if 判断中, 通过 cas 操作将参数传入的 SUCCESS 对象赋值到 DefaultPromise 的属性 result 中, 我们看这个属
    // 性: private volatile Object result; 这里是 Object 类型, 也就是可以赋值成任何类型。SUCCESS 是一个 Signal 类型的对
    // 象, 这里我们可以简单理解成一种状态, SUCCESS 表示一种成功的状态。通过上述 cas 操作, result 的值将赋值成
    // SUCCESS，我们回到 trySuccess 方法：
    private boolean setValue0(Object objResult) {
        if (RESULT_UPDATER.compareAndSet(this, null, objResult) ||
            RESULT_UPDATER.compareAndSet(this, UNCANCELLABLE, objResult)) {
            checkNotifyWaiters();
            return true;
        }
        return false;
    }

    private synchronized void checkNotifyWaiters() {
        if (waiters > 0) {
            notifyAll();
        }
    }

    private void incWaiters() {
        if (waiters == Short.MAX_VALUE) {
            throw new IllegalStateException("too many waiters: " + this);
        }
        ++waiters;
    }

    private void decWaiters() {
        --waiters;
    }

    private void rethrowIfFailed() {
        Throwable cause = cause();
        if (cause == null) {
            return;
        }

        PlatformDependent.throwException(cause);
    }

    private boolean await0(long timeoutNanos, boolean interruptable) throws InterruptedException {
        if (isDone()) {
            return true;
        }

        if (timeoutNanos <= 0) {
            return isDone();
        }

        if (interruptable && Thread.interrupted()) {
            throw new InterruptedException(toString());
        }

        checkDeadLock();

        long startTime = System.nanoTime();
        long waitTime = timeoutNanos;
        boolean interrupted = false;
        try {
            for (;;) {
                synchronized (this) {
                    if (isDone()) {
                        return true;
                    }
                    incWaiters();
                    try {
                        wait(waitTime / 1000000, (int) (waitTime % 1000000));
                    } catch (InterruptedException e) {
                        if (interruptable) {
                            throw e;
                        } else {
                            interrupted = true;
                        }
                    } finally {
                        decWaiters();
                    }
                }
                if (isDone()) {
                    return true;
                } else {
                    waitTime = timeoutNanos - (System.nanoTime() - startTime);
                    if (waitTime <= 0) {
                        return isDone();
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Notify all progressive listeners.
     * <p>
     * No attempt is made to ensure notification order if multiple calls are made to this method before
     * the original invocation completes.
     * <p>
     * This will do an iteration over all listeners to get all of type {@link GenericProgressiveFutureListener}s.
     * @param progress the new progress.
     * @param total the total progress.
     */
    @SuppressWarnings("unchecked")
    void notifyProgressiveListeners(final long progress, final long total) {
        final Object listeners = progressiveListeners();
        if (listeners == null) {
            return;
        }

        final ProgressiveFuture<V> self = (ProgressiveFuture<V>) this;

        EventExecutor executor = executor();
        if (executor.inEventLoop()) {
            if (listeners instanceof GenericProgressiveFutureListener[]) {
                notifyProgressiveListeners0(
                        self, (GenericProgressiveFutureListener<?>[]) listeners, progress, total);
            } else {
                notifyProgressiveListener0(
                        self, (GenericProgressiveFutureListener<ProgressiveFuture<V>>) listeners, progress, total);
            }
        } else {
            if (listeners instanceof GenericProgressiveFutureListener[]) {
                final GenericProgressiveFutureListener<?>[] array =
                        (GenericProgressiveFutureListener<?>[]) listeners;
                safeExecute(executor, new Runnable() {
                    @Override
                    public void run() {
                        notifyProgressiveListeners0(self, array, progress, total);
                    }
                });
            } else {
                final GenericProgressiveFutureListener<ProgressiveFuture<V>> l =
                        (GenericProgressiveFutureListener<ProgressiveFuture<V>>) listeners;
                safeExecute(executor, new Runnable() {
                    @Override
                    public void run() {
                        notifyProgressiveListener0(self, l, progress, total);
                    }
                });
            }
        }
    }

    /**
     * Returns a {@link GenericProgressiveFutureListener}, an array of {@link GenericProgressiveFutureListener}, or
     * {@code null}.
     */
    private synchronized Object progressiveListeners() {
        Object listeners = this.listeners;
        if (listeners == null) {
            // No listeners added
            return null;
        }

        if (listeners instanceof DefaultFutureListeners) {
            // Copy DefaultFutureListeners into an array of listeners.
            DefaultFutureListeners dfl = (DefaultFutureListeners) listeners;
            int progressiveSize = dfl.progressiveSize();
            switch (progressiveSize) {
                case 0:
                    return null;
                case 1:
                    for (GenericFutureListener<?> l: dfl.listeners()) {
                        if (l instanceof GenericProgressiveFutureListener) {
                            return l;
                        }
                    }
                    return null;
            }

            GenericFutureListener<?>[] array = dfl.listeners();
            GenericProgressiveFutureListener<?>[] copy = new GenericProgressiveFutureListener[progressiveSize];
            for (int i = 0, j = 0; j < progressiveSize; i ++) {
                GenericFutureListener<?> l = array[i];
                if (l instanceof GenericProgressiveFutureListener) {
                    copy[j ++] = (GenericProgressiveFutureListener<?>) l;
                }
            }

            return copy;
        } else if (listeners instanceof GenericProgressiveFutureListener) {
            return listeners;
        } else {
            // Only one listener was added and it's not a progressive listener.
            return null;
        }
    }

    private static void notifyProgressiveListeners0(
            ProgressiveFuture<?> future, GenericProgressiveFutureListener<?>[] listeners, long progress, long total) {
        for (GenericProgressiveFutureListener<?> l: listeners) {
            if (l == null) {
                break;
            }
            notifyProgressiveListener0(future, l, progress, total);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void notifyProgressiveListener0(
            ProgressiveFuture future, GenericProgressiveFutureListener l, long progress, long total) {
        try {
            l.operationProgressed(future, progress, total);
        } catch (Throwable t) {
            logger.warn("An exception was thrown by " + l.getClass().getName() + ".operationProgressed()", t);
        }
    }

    private static boolean isCancelled0(Object result) {
        return result instanceof CauseHolder && ((CauseHolder) result).cause instanceof CancellationException;
    }
    //这
    // 里判断 result 不为 null 并且不为 UNCANCELLABLE, 则就表示完成。因为成功的状态是 SUCCESS, 所以 flush 成功
    // 这里会返回 true。回到 addListener 中，如果执行完成, 就通过 notifyListeners()方法执行回调,
    private static boolean isDone0(Object result) {
        return result != null && result != UNCANCELLABLE;
    }

    private static final class CauseHolder {
        final Throwable cause;
        CauseHolder(Throwable cause) {
            this.cause = cause;
        }
    }

    private static void safeExecute(EventExecutor executor, Runnable task) {
        try {
            executor.execute(task);
        } catch (Throwable t) {
            rejectedExecutionLogger.error("Failed to submit a listener notification task. Event loop shut down?", t);
        }
    }
}
