/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opengoofy.index12306.framework.starter.common.threadpool.support.eager;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 快速消费线程池
 * 解决 JDK 线程池中不超过最大线程数下即时快速消费任务，而不是在队列中堆积的问题。
 * 说白了就是直接“移除了”阻塞队列的功能（不是真的移除，而是把阻塞队列和创建核心线程的顺序调换）
 *
 * 为什么移除阻塞队列呢？
 * 先提交的任务不一定先执行；核心线程阻塞队列队列中如果有任务，且阻塞队列已满，
 * 线程池会新建非核心线程，新创建的线程还是会优先处理这个新提交的任务，而不是从阻塞队列中获取已有的任务执行，
 * 因此，移除阻塞队列牺牲最大线程数来保证线程执行的实时性
 *
 */
public class EagerThreadPoolExecutor extends ThreadPoolExecutor {

//    任务队列交给TaskQueue处理
//    一阶段：任务数<空闲核心线程，核心线程直接运行任务 execute（重写前的）
//    二阶段：小于>任务数>空闲核心线程 && 阻塞队列空闲，阻塞队列提交任务执行 offer方法（重写后的）
//    三阶段：小于>任务数>空闲核心线程 && 阻塞队列满，非核心线程发力 execute（重写前的）
//    四阶段：小于<任务数，线程池 execute（重写后的），抛出RejectedExecutionException错误，TaskQueue调用retryOffer重试提交
    public EagerThreadPoolExecutor(int corePoolSize,
                                   int maximumPoolSize,
                                   long keepAliveTime,
                                   TimeUnit unit,
                                   TaskQueue<Runnable> workQueue,
                                   ThreadFactory threadFactory,
                                   RejectedExecutionHandler handler) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, handler);
    }

//    提交任务数量
    private final AtomicInteger submittedTaskCount = new AtomicInteger(0);

    public int getSubmittedTaskCount() {
        return submittedTaskCount.get();
    }

//    递减已提交任务数量，说明任务执行完成
    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        submittedTaskCount.decrementAndGet();
    }

    @Override
    public void execute(Runnable command) {
//        任务数量加一
        submittedTaskCount.incrementAndGet();
        try {
//            任务执行
            super.execute(command);
        } catch (RejectedExecutionException ex) {
            TaskQueue taskQueue = (TaskQueue) super.getQueue();
            try {
                if (!taskQueue.retryOffer(command, 0, TimeUnit.MILLISECONDS)) {
                    submittedTaskCount.decrementAndGet();
                    throw new RejectedExecutionException("Queue capacity is full.", ex);
                }
            } catch (InterruptedException iex) {
                submittedTaskCount.decrementAndGet();
                throw new RejectedExecutionException(iex);
            }
        } catch (Exception ex) {
            submittedTaskCount.decrementAndGet();
            throw ex;
        }
    }
}
