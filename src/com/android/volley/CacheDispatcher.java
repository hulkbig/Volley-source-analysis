/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.volley;

import android.os.Process;

import java.util.concurrent.BlockingQueue;

/**
 * Provides a thread for performing cache triage on a queue of requests.
 * 提供一个线程来执行对队列中的请求执行cache的分类的工作。
 *
 * Requests added to the specified cache queue are resolved from cache.
 * Any deliverable response is posted back to the caller via a
 * {@link ResponseDelivery}.  Cache misses and responses that require
 * refresh are enqueued on the specified network queue for processing
 * by a {@link NetworkDispatcher}.
 * 
 * 添加到指定的cache队列中的请求都会从cache中是解析。任何可以分发的响应都会通过{@link ResponseDelivery}回调。
 * 如果Cache缺失或者响应需要刷新的话，那么请求就会通过 {@link NetworkDispatcher}被添加到指定的network队列。
 */
public class CacheDispatcher extends Thread {

    private static final boolean DEBUG = VolleyLog.DEBUG;

    /** The queue of requests coming in for triage. */
    /** 处理Cache分类的队列 */
    private final BlockingQueue<Request<?>> mCacheQueue;

    /** The queue of requests going out to the network. */
    /** 处理需要重新进行网络访问的队列，包括cache缺失或者需要强制刷新的请求 */
    private final BlockingQueue<Request<?>> mNetworkQueue;

    /** The cache to read from. */
    private final Cache mCache;

    /** For posting responses. */
    private final ResponseDelivery mDelivery;

    /** Used for telling us to die. */
    /** 线程退出标志 */
    private volatile boolean mQuit = false;

    /**
     * Creates a new cache triage dispatcher thread.  You must call {@link #start()}
     * in order to begin processing.
     * 
     * 创建一个新的cache调度器线程。调用{@link #start()}后开始处理
     *
     * @param cacheQueue Queue of incoming requests for triage
     * @param networkQueue Queue to post requests that require network to
     * @param cache Cache interface to use for resolution
     * @param delivery Delivery interface to use for posting responses
     */
    public CacheDispatcher(
            BlockingQueue<Request<?>> cacheQueue, BlockingQueue<Request<?>> networkQueue,
            Cache cache, ResponseDelivery delivery) {
        mCacheQueue = cacheQueue;
        mNetworkQueue = networkQueue;
        mCache = cache;
        mDelivery = delivery;
    }

    /**
     * Forces this dispatcher to quit immediately.  If any requests are still in
     * the queue, they are not guaranteed to be processed.
     * 
     * 强制调度器LIKE退出。如果在队列中还有任何请求的话，这些请求将无法保证得到处理。
     */
    public void quit() {
        mQuit = true;
        interrupt();
    }

    @Override
    public void run() {
        if (DEBUG) VolleyLog.v("start new dispatcher");
        //设定优先级会后台优先级。
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        // Make a blocking call to initialize the cache.
        // 阻塞调用，初始化cache
        mCache.initialize();

        while (true) {
            try {
                // Get a request from the cache triage queue, blocking until
                // at least one is available.
                // 从cache中获取一个待分类的cache，采用了blocking的队列，会一直阻塞直到有可用的位置。
                final Request<?> request = mCacheQueue.take();
                request.addMarker("cache-queue-take");

                // If the request has been canceled, don't bother dispatching it.
                // 检查请求是否已经被取消，如果是的话，则放弃该请求，重新获取新的进行调度。
                if (request.isCanceled()) {
                    request.finish("cache-discard-canceled");
                    continue;
                }

                // Attempt to retrieve this item from cache.
                // 尝试从Cache中获取数据
                Cache.Entry entry = mCache.get(request.getCacheKey());
                if (entry == null) {
                    // 从Cache中获取数据失败，意味着cache miss，需要从网络中重新获取
                    request.addMarker("cache-miss");
                    // Cache miss; send off to the network dispatcher.
                    // 将请求添加到网络任务队列中。
                    mNetworkQueue.put(request);
                    continue;
                }

                // If it is completely expired, just send it to the network.
                // 此处和上面情况类似，但是不是cache缺失，而是cache过期
                if (entry.isExpired()) {
                    request.addMarker("cache-hit-expired");
                    // 更新之前，首先将数据保存一份。
                    request.setCacheEntry(entry);
                    // 将Request添加到网络任务队列当中。
                    mNetworkQueue.put(request);
                    continue;
                }

                // We have a cache hit; parse its data for delivery back to the request.
                // 到此处已经检查过Cache是否确实，Cache是否过期，此时说明数据从Cache中取回即可。
                request.addMarker("cache-hit");
                // 首先将cache中的raw数据进行解析。
                Response<?> response = request.parseNetworkResponse(
                        new NetworkResponse(entry.data, entry.responseHeaders));
                request.addMarker("cache-hit-parsed");

                if (!entry.refreshNeeded()) {
                    // Completely unexpired cache hit. Just deliver the response.
                    // 此时数据不需要更新，直接将数据分发出去。
                    mDelivery.postResponse(request, response);
                } else {
                    // Soft-expired cache hit. We can deliver the cached response,
                    // but we need to also send the request to the network for
                    // refreshing.
                    // 此时数据虽然说是cache命中了，但数据需要进行更新。
                    request.addMarker("cache-hit-refresh-needed");
                    request.setCacheEntry(entry);

                    // Mark the response as intermediate.
                    // 标记该response为一个中间结果，以后还会需要更新
                    response.intermediate = true;

                    // Post the intermediate response back to the user and have
                    // the delivery then forward the request along to the network.
                    // 将中间结果返回给用户，并且将请求转发给网络层。
                    // 但不清楚为什么要将添加到网络队列的过程放在其他线程中去做-->查看该方法的签名，该方法是首先将结果传递给用户，然后再执行Runnable
                    mDelivery.postResponse(request, response, new Runnable() {
                        @Override
                        public void run() {
                            try {
                                mNetworkQueue.put(request);
                            } catch (InterruptedException e) {
                                // Not much we can do about this.
                            }
                        }
                    });
                }

            } catch (InterruptedException e) {
                // We may have been interrupted because it was time to quit.
                if (mQuit) {
                    return;
                }
                continue;
            }
        }
    }
}
