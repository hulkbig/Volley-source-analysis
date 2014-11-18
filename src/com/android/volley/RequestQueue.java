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

import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A request dispatch queue with a thread pool of dispatchers.
 * 一个请求分发队列，包含了一个分发器的线程池。
 *
 * Calling {@link #add(Request)} will enqueue the given Request for dispatch,
 * resolving from either cache or network on a worker thread, and then delivering
 * a parsed response on the main thread.
 * 调用 {@link #add(Request)} 会将指定的请求加入到队列中，然后在工作线程中从cache或者network中解析，
 * 然后将解析的结果在main线程中派发。
 */
public class RequestQueue {

    /** Used for generating monotonically-increasing sequence numbers for requests. */
    /** 序列号生成器，原子变量，保证该生成器是线程安全的。*/
    private AtomicInteger mSequenceGenerator = new AtomicInteger();

    /**
     * Staging area for requests that already have a duplicate request in flight.
     *
     * <ul>
     *     <li>containsKey(cacheKey) indicates that there is a request in flight for the given cache
     *          key.</li>
     *     <li>get(cacheKey) returns waiting requests for the given cache key. The in flight request
     *          is <em>not</em> contained in that list. Is null if no requests are staged.</li>
     * </ul>
     * 
     * 待命区：存储那些已经有一份拷贝，？？？并且在处理中的请求？？？。
     * <ul>
     *     <li>containsKey(cacheKey) 标志着给定的cache关键字来说，已经有一个请求在运行当中了</li>
     *     <li>get(cacheKey) 返回指定key的等待的请求列表，如果请求正在处理当中，那么是不会包含在这个列表中。
     *         如果没有请求被暂时保存等待处理，那么为指定key返回的为null。</li>
     * </ul>
     * 
     */
    private final Map<String, Queue<Request<?>>> mWaitingRequests =
            new HashMap<String, Queue<Request<?>>>();

    /**
     * The set of all requests currently being processed by this RequestQueue. A Request
     * will be in this set if it is waiting in any queue or currently being processed by
     * any dispatcher.
     * 当前RequestQueue正在处理的请求的集合。如果一个请求正在某一个队列里面等待处理或者正在被调度器处理的时候，都会在这个集合中。
     */
    private final Set<Request<?>> mCurrentRequests = new HashSet<Request<?>>();

    /** The cache triage queue. */
    /** cache的分类队列，是一个优先级阻塞队列，Request实现了Comparable接口，根据优先级进行排序 */
    private final PriorityBlockingQueue<Request<?>> mCacheQueue =
        new PriorityBlockingQueue<Request<?>>();

    /** The queue of requests that are actually going out to the network. */
    /** 正在进行网络请求的Request队列  */
    private final PriorityBlockingQueue<Request<?>> mNetworkQueue =
        new PriorityBlockingQueue<Request<?>>();

    /** Number of network request dispatcher threads to start. */
    /** 同时进行网络请求的任务数量，即dispatcher的数量  */
    private static final int DEFAULT_NETWORK_THREAD_POOL_SIZE = 4;

    /** Cache interface for retrieving and storing responses. */
    /** Cache 接口，用来取回或者保存响应结果 */
    private final Cache mCache;

    /** Network interface for performing requests. */
    /** Network 接口，用来执行网络请求 */
    private final Network mNetwork;

    /** Response delivery mechanism. */
    /** 响应结果传递机制 */
    private final ResponseDelivery mDelivery;

    /** The network dispatchers. */
    /** 网络调度员，为一组 */
    private NetworkDispatcher[] mDispatchers;

    /** The cache dispatcher. */
    /** cache调度员，为一个 */
    private CacheDispatcher mCacheDispatcher;

    /**
     * Creates the worker pool. Processing will not begin until {@link #start()} is called.
     *
     * @param cache A Cache to use for persisting responses to disk
     * @param network A Network interface for performing HTTP requests
     * @param threadPoolSize Number of network dispatcher threads to create
     * @param delivery A ResponseDelivery interface for posting responses and errors
     * 
     * 创建worker线程。只有当 {@link #start()} 被调用的时候，才会开始处理.
     *
     * @param cache A Cache to use for persisting responses to disk
     * @param network A Network interface for performing HTTP requests
     * @param threadPoolSize Number of network dispatcher threads to create
     * @param delivery A ResponseDelivery interface for posting responses and errors
     * 
     */
    public RequestQueue(Cache cache, Network network, int threadPoolSize,
            ResponseDelivery delivery) {
        mCache = cache;
        mNetwork = network;
        mDispatchers = new NetworkDispatcher[threadPoolSize];
        mDelivery = delivery;
    }

    /**
     * Creates the worker pool. Processing will not begin until {@link #start()} is called.
     *
     * 同上，创建一个worker线程。但不同的是，默认启动一个分发器，运行在主线程当中。
     * @param cache A Cache to use for persisting responses to disk
     * @param network A Network interface for performing HTTP requests
     * @param threadPoolSize Number of network dispatcher threads to create
     */
    public RequestQueue(Cache cache, Network network, int threadPoolSize) {
        this(cache, network, threadPoolSize,
                new ExecutorDelivery(new Handler(Looper.getMainLooper())));
    }

    /**
     * Creates the worker pool. Processing will not begin until {@link #start()} is called.
     *
     * 同上，创建一个worker线程，但不同的是，设定默认的线程池大小为 {@link #DEFAULT_NETWORK_THREAD_POOL_SIZE}
     * @param cache A Cache to use for persisting responses to disk
     * @param network A Network interface for performing HTTP requests
     */
    public RequestQueue(Cache cache, Network network) {
        this(cache, network, DEFAULT_NETWORK_THREAD_POOL_SIZE);
    }

    /**
     * Starts the dispatchers in this queue.
     * 
     * 启动队列中的调度器。
     */
    public void start() {
        stop();  // Make sure any currently running dispatchers are stopped. 确保当前正在运行的调度器已经停止。
        // Create the cache dispatcher and start it. 创建调度器，并且启动它
        mCacheDispatcher = new CacheDispatcher(mCacheQueue, mNetworkQueue, mCache, mDelivery);
        mCacheDispatcher.start();

        // Create network dispatchers (and corresponding threads) up to the pool size.
        // 创建网络的调度器，最高启动线程池大小个调度器。
        for (int i = 0; i < mDispatchers.length; i++) {
            NetworkDispatcher networkDispatcher = new NetworkDispatcher(mNetworkQueue, mNetwork,
                    mCache, mDelivery);
            mDispatchers[i] = networkDispatcher;
            networkDispatcher.start();
        }
    }

    /**
     * Stops the cache and network dispatchers.
     * 
     * 停止cache和network的调度器。
     */
    public void stop() {
        if (mCacheDispatcher != null) {
            mCacheDispatcher.quit();
        }
        for (int i = 0; i < mDispatchers.length; i++) {
            if (mDispatchers[i] != null) {
                mDispatchers[i].quit();
            }
        }
    }

    /**
     * Gets a sequence number.
     */
    public int getSequenceNumber() {
        return mSequenceGenerator.incrementAndGet();
    }

    /**
     * Gets the {@link Cache} instance being used.
     */
    public Cache getCache() {
        return mCache;
    }

    /**
     * A simple predicate or filter interface for Requests, for use by
     * {@link RequestQueue#cancelAll(RequestFilter)}.
     * 
     * 一个简单的Request的预测器或者过滤器的接口。当{@link RequestQueue#cancelAll(RequestFilter)}的时候使用。
     */
    public interface RequestFilter {
        public boolean apply(Request<?> request);
    }

    /**
     * Cancels all requests in this queue for which the given filter applies.
     * @param filter The filtering function to use
     */
    public void cancelAll(RequestFilter filter) {
        synchronized (mCurrentRequests) {
            for (Request<?> request : mCurrentRequests) {
                if (filter.apply(request)) {
                    request.cancel();
                }
            }
        }
    }

    /**
     * Cancels all requests in this queue with the given tag. Tag must be non-null
     * and equality is by identity.
     */
    public void cancelAll(final Object tag) {
        if (tag == null) {
            throw new IllegalArgumentException("Cannot cancelAll with a null tag");
        }
        cancelAll(new RequestFilter() {
            @Override
            public boolean apply(Request<?> request) {
                return request.getTag() == tag;
            }
        });
    }

    /**
     * Adds a Request to the dispatch queue.
     * 
     * 添加一个请求到调度队列当中。
     *  
     * @param request The request to service
     * @return The passed-in request
     */
    public <T> Request<T> add(Request<T> request) {
        // Tag the request as belonging to this queue and add it to the set of current requests.
        //将request与该任务队列相关联。
        request.setRequestQueue(this);
        //将该请求放在任务队列的待做任务当中。
        synchronized (mCurrentRequests) {
            mCurrentRequests.add(request);
        }

        // Process requests in the order they are added.
        // 设定序列号
        request.setSequence(getSequenceNumber());
        // 添加日志
        request.addMarker("add-to-queue");

        // If the request is uncacheable, skip the cache queue and go straight to the network.
        // 如果该日志不需要Cache的话，那么跳过cache的队列，直接进行网络请求
        if (!request.shouldCache()) {
            mNetworkQueue.add(request);
            return request;
        }

        // 如果程序运行到这里，说明需要缓存cache，那么进行的操作是，先检查当前的任务有没有在cache的运行当中，
        // 如果正在进行，或者说cache对应的cacheKey有Reqeust正在执行，那么则直接加入到cacheKey对应的队列当中即可。
        // 如果需要cache，而且没有正在这行，则添加到等待队列和cache队列当中。
        
        // Insert request into stage if there's already a request with the same cache key in flight.
        // 同步任务队列，根据该请求是否添加到RequestQueue的不同情况，分别处理
        synchronized (mWaitingRequests) {
            //判断等待队列是否包含当前添加的任务
            String cacheKey = request.getCacheKey();
            if (mWaitingRequests.containsKey(cacheKey)) {
                // There is already a request in flight. Queue up.
                // 该cacheKey对应的任务之前添加过，并且还没有处理完成。则取出cacheKey对应的任务队列，将该任务添加进去。
                Queue<Request<?>> stagedRequests = mWaitingRequests.get(cacheKey);
                if (stagedRequests == null) {
                    stagedRequests = new LinkedList<Request<?>>();
                }
                stagedRequests.add(request);
                mWaitingRequests.put(cacheKey, stagedRequests);
                if (VolleyLog.DEBUG) {
                    VolleyLog.v("Request for cacheKey=%s is in flight, putting on hold.", cacheKey);
                }
            } else {
                // Insert 'null' queue for this cacheKey, indicating there is now a request in
                // flight.
                // 与上面不同的是，当前的任务没有处理过，所以将任务添加到等待队列中，然后添加到cache的队列中。
                mWaitingRequests.put(cacheKey, null);
                mCacheQueue.add(request);
            }
            return request;
        }
    }

    /**
     * Called from {@link Request#finish(String)}, indicating that processing of the given request
     * has finished.
     *
     * 该调用来自{@link Request#finish(String)}，标志着该请求已经完成。
     * 
     * <p>Releases waiting requests for <code>request.getCacheKey()</code> if
     *      <code>request.shouldCache()</code>.</p>
     *      
     * 如果request.shouldCache()为真的话，那么释放reqeust.getCacheKey()所需要的请求队列。
     */
    void finish(Request<?> request) {
        // Remove from the set of requests currently being processed.
        // 从当前的任务队列中删除。
        synchronized (mCurrentRequests) {
            mCurrentRequests.remove(request);
        }

        
        // 理解：当一个request从网络或者其他的位置上完成的时候，则需要考虑将其cache住，那么这个时候，就要添加从cache的等待队列上删除，
        //     然后添加到cache的队列当中。
        
        // 此请求应该cache住
        if (request.shouldCache()) {
            //同步等待队列
            synchronized (mWaitingRequests) {
                //获取该Request对应的cacheKey
                String cacheKey = request.getCacheKey();
                //获取该key对应的请求队列，并删除key                
                Queue<Request<?>> waitingRequests = mWaitingRequests.remove(cacheKey);
                if (waitingRequests != null) {
                    if (VolleyLog.DEBUG) {
                        VolleyLog.v("Releasing %d waiting requests for cacheKey=%s.",
                                waitingRequests.size(), cacheKey);
                    }
                    // Process all queued up requests. They won't be considered as in flight, but
                    // that's not a problem as the cache has been primed by 'request'.
                    // 处理所有的排队等候的请求。这些请求并不任务正在执行当中，但没关系的是，这些请求已经被'request'准备好。
                    // 将所有的需要cache的请求添加到cache队列当中。
                    mCacheQueue.addAll(waitingRequests);
                }
            }
        }
    }
}
