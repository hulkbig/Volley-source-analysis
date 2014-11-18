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

import android.annotation.TargetApi;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Process;

import java.util.concurrent.BlockingQueue;

/**
 * Provides a thread for performing network dispatch from a queue of requests.
 * 提供一个线程用来从请求队列中执行网络任务的分发
 * 
 * Requests added to the specified queue are processed from the network via a
 * specified {@link Network} interface. Responses are committed to cache, if
 * eligible, using a specified {@link Cache} interface. Valid responses and
 * errors are posted back to the caller via a {@link ResponseDelivery}.
 */
public class NetworkDispatcher extends Thread {
    /** The queue of requests to service. */
    /** 需要服务的请求队列 */
    private final BlockingQueue<Request<?>> mQueue;
    /** The network interface for processing requests. */
    /** 执行请求的网络接口 */
    private final Network mNetwork;
    /** The cache to write to. */
    /** 要写入的Cache */
    private final Cache mCache;
    /** For posting responses and errors. */
    /** 对于响应或者错误信息的分发 */
    private final ResponseDelivery mDelivery;
    /** Used for telling us to die. */
    /** 是否要退出该线程 */
    private volatile boolean mQuit = false;

    /**
     * Creates a new network dispatcher thread.  You must call {@link #start()}
     * in order to begin processing.
     * 创建一个网络分发线程。我们必须调用{@link #start()}才会启动。
     * 
     * @param queue Queue of incoming requests for triage
     * @param network Network interface to use for performing requests
     * @param cache Cache interface to use for writing responses to cache
     * @param delivery Delivery interface to use for posting responses
     */
    public NetworkDispatcher(BlockingQueue<Request<?>> queue,
            Network network, Cache cache,
            ResponseDelivery delivery) {
        mQueue = queue;
        mNetwork = network;
        mCache = cache;
        mDelivery = delivery;
    }

    /**
     * Forces this dispatcher to quit immediately.  If any requests are still in
     * the queue, they are not guaranteed to be processed.
     * 强制该分发线程退出。及时此时队列中仍然有需要处理的请求，这些请求不能保证得到处理。
     */
    public void quit() {
        mQuit = true;
        interrupt();
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    private void addTrafficStatsTag(Request<?> request) {
        // Tag the request (if API >= 14)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            //如果系统版本大于ICE_CREAM，那么就设置流量信息采集
            TrafficStats.setThreadStatsTag(request.getTrafficStatsTag());
        }
    }

    @Override
    public void run() {
        //设置进程的优先级为后台进程。
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        while (true) {
            Request<?> request;
            try {
                // Take a request from the queue.
                // 从任务队列中获取一个请求
                request = mQueue.take();
            } catch (InterruptedException e) {
                // We may have been interrupted because it was time to quit.
                if (mQuit) {
                    return;
                }
                continue;
            }

            try {
                //添加日志
                request.addMarker("network-queue-take");

                // If the request was cancelled already, do not perform the
                // network request.
                // 如果该请求已经被取消，那么就不再处理
                if (request.isCanceled()) {
                    request.finish("network-discard-cancelled");
                    continue;
                }
                //添加流量统计信息标记。
                addTrafficStatsTag(request);

                // Perform the network request.
                // 从网络中获取请求数据
                NetworkResponse networkResponse = mNetwork.performRequest(request);
                request.addMarker("network-http-complete");

                // If the server returned 304 AND we delivered a response already,
                // we're done -- don't deliver a second identical response.
                // 如果服务器已经返回了304，并且我们已经进行过同样的请求，那么就不需要再处理
                // HTTP 304 =  如果客户端发送了一个带条件的 GET 请求且该请求已被允许，而文档的内容（自上次访问以来或者根据请求的条件）并没有改变，则服务器应当返回这个状态码。
                if (networkResponse.notModified && request.hasHadResponseDelivered()) {
                    request.finish("not-modified");
                    continue;
                }

                // Parse the response here on the worker thread.
                // 在工作线程中，对网络请求返回的数据进行解析
                Response<?> response = request.parseNetworkResponse(networkResponse);
                request.addMarker("network-parse-complete");

                // Write to cache if applicable.
                // TODO: Only update cache metadata instead of entire record for 304s.
                // 如果需要，则写入cache。注意，对于304，则只写入元数据，而非整个全部数据
                if (request.shouldCache() && response.cacheEntry != null) {
                    mCache.put(request.getCacheKey(), response.cacheEntry);
                    request.addMarker("network-cache-written");
                }

                // Post the response back.
                // 标记该请求已经分发过。
                request.markDelivered();
                // 将解析的结果分发给main线程。
                mDelivery.postResponse(request, response);
            } catch (VolleyError volleyError) {
                parseAndDeliverNetworkError(request, volleyError);
            } catch (Exception e) {
                VolleyLog.e(e, "Unhandled exception %s", e.toString());
                mDelivery.postError(request, new VolleyError(e));
            }
        }
    }

    private void parseAndDeliverNetworkError(Request<?> request, VolleyError error) {
        error = request.parseNetworkError(error);
        mDelivery.postError(request, error);
    }
}
