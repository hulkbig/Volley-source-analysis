/**
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.volley.toolbox;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response.ErrorListener;
import com.android.volley.Response.Listener;
import com.android.volley.VolleyError;

import java.util.HashMap;
import java.util.LinkedList;

/**
 * Helper that handles loading and caching images from remote URLs.
 * 一个工具用来处理从URL加载和缓存图片。
 * The simple way to use this class is to call {@link ImageLoader#get(String, ImageListener)}
 * and to pass in the default image listener provided by
 * {@link ImageLoader#getImageListener(ImageView, int, int)}. Note that all function calls to
 * this class must be made from the main thead, and all responses will be delivered to the main
 * thread as well.
 * 使用该类的最简单的方式是调用{@link ImageLoader#get(String, ImageListener)}，并且传入
 * {@link ImageLoader#getImageListener(ImageView, int, int)}提供的默认的image listener。
 * 注意，该类中的所有的方法必须在main线程调用，所有的response都同样的在主线程分发。
 */
public class ImageLoader {
    /** RequestQueue for dispatching ImageRequests onto. */
    private final RequestQueue mRequestQueue;

    /** Amount of time to wait after first response arrives before delivering all responses. */
    private int mBatchResponseDelayMs = 100;

    /** The cache implementation to be used as an L1 cache before calling into volley. */
    private final ImageCache mCache;

    /**
     * HashMap of Cache keys -> BatchedImageRequest used to track in-flight requests so
     * that we can coalesce multiple requests to the same URL into a single network request.
     * 
     * HashMap保存那些Cache keys，BatchedImageRequest用来跟踪那些同样URL路径的，可以合并的为一个网络请求的请求。
     */
    private final HashMap<String, BatchedImageRequest> mInFlightRequests =
            new HashMap<String, BatchedImageRequest>();

    /** HashMap of the currently pending responses (waiting to be delivered). */
    /** 等待分发的response */
    private final HashMap<String, BatchedImageRequest> mBatchedResponses =
            new HashMap<String, BatchedImageRequest>();

    /** Handler to the main thread. */
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /** Runnable for in-flight response delivery. */
    private Runnable mRunnable;

    /**
     * Simple cache adapter interface. If provided to the ImageLoader, it
     * will be used as an L1 cache before dispatch to Volley. Implementations
     * must not block. Implementation with an LruCache is recommended.
     */
    public interface ImageCache {
        public Bitmap getBitmap(String url);
        public void putBitmap(String url, Bitmap bitmap);
    }

    /**
     * Constructs a new ImageLoader.
     * @param queue The RequestQueue to use for making image requests.
     * @param imageCache The cache to use as an L1 cache.
     */
    public ImageLoader(RequestQueue queue, ImageCache imageCache) {
        mRequestQueue = queue;
        mCache = imageCache;
    }

    /**
     * The default implementation of ImageListener which handles basic functionality
     * of showing a default image until the network response is received, at which point
     * it will switch to either the actual image or the error image.
     * 
     * ImageListener的默认实现，会搜线显示一个默认的图片，当network返回之后，则会显示一个真实的图片。
     * 
     * @param imageView The imageView that the listener is associated with.
     * @param defaultImageResId Default image resource ID to use, or 0 if it doesn't exist.
     * @param errorImageResId Error image resource ID to use, or 0 if it doesn't exist.
     */
    public static ImageListener getImageListener(final ImageView view,
            final int defaultImageResId, final int errorImageResId) {
        return new ImageListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                if (errorImageResId != 0) {
                    view.setImageResource(errorImageResId);
                }
            }

            @Override
            public void onResponse(ImageContainer response, boolean isImmediate) {
                if (response.getBitmap() != null) {
                    view.setImageBitmap(response.getBitmap());
                } else if (defaultImageResId != 0) {
                    view.setImageResource(defaultImageResId);
                }
            }
        };
    }

    /**
     * Interface for the response handlers on image requests.
     * 用来处理response的回调接口
     *
     * The call flow is this:
     * 1. Upon being  attached to a request, onResponse(response, true) will
     * be invoked to reflect any cached data that was already available. If the
     * data was available, response.getBitmap() will be non-null.
     * 1、如果和一个request绑定之后，onResponse(response, true)会在被调用，表明在cache中有对应的数据。
     * 如果数据是可用的，那么response.getBitmap()不是null。
     *
     * 2. After a network response returns, only one of the following cases will happen:
     *   - onResponse(response, false) will be called if the image was loaded.
     *   or
     *   - onErrorResponse will be called if there was an error loading the image.
     * 2、当network的数据返回的时候，仅仅会发生下面的其中一种情况：
     *   - onResponse(response, false)会在image加载之后调用
     *   - onErrorResponse会在出错的时候调用
     */
    public interface ImageListener extends ErrorListener {
        /**
         * Listens for non-error changes to the loading of the image request.
         *
         * @param response Holds all information pertaining to the request, as well
         * as the bitmap (if it is loaded).
         * @param isImmediate True if this was called during ImageLoader.get() variants.
         * This can be used to differentiate between a cached image loading and a network
         * image loading in order to, for example, run an animation to fade in network loaded
         * images.
         */
        public void onResponse(ImageContainer response, boolean isImmediate);
    }

    /**
     * Checks if the item is available in the cache.
     * @param requestUrl The url of the remote image
     * @param maxWidth The maximum width of the returned image.
     * @param maxHeight The maximum height of the returned image.
     * @return True if the item exists in cache, false otherwise.
     */
    public boolean isCached(String requestUrl, int maxWidth, int maxHeight) {
        throwIfNotOnMainThread();

        String cacheKey = getCacheKey(requestUrl, maxWidth, maxHeight);
        return mCache.getBitmap(cacheKey) != null;
    }

    /**
     * Returns an ImageContainer for the requested URL.
     * 返回requestedURL所对应的ImageContainer
     *
     * The ImageContainer will contain either the specified default bitmap or the loaded bitmap.
     * If the default was returned, the {@link ImageLoader} will be invoked when the
     * request is fulfilled.
     * ImageContainer会包含或者说默认的bitmpa，或者说已经加载的bitmap。如果说返回了默认的值，那么
     * {@link ImageLoader}会在reqeust完成的时候进行调用。
     * 
     *
     * @param requestUrl The URL of the image to be loaded.
     * @param defaultImage Optional default image to return until the actual image is loaded.
     */
    public ImageContainer get(String requestUrl, final ImageListener listener) {
        return get(requestUrl, listener, 0, 0);
    }

    /**
     * Issues a bitmap request with the given URL if that image is not available
     * in the cache, and returns a bitmap container that contains all of the data
     * relating to the request (as well as the default image if the requested
     * image is not available).
     * 判断一个指定URL的bitmap的请求是否已经在cache中存在。如果container包含了所需要的所有的相关数据，那么返回该container
     * 
     * @param requestUrl The url of the remote image
     * @param imageListener The listener to call when the remote image is loaded
     * @param maxWidth The maximum width of the returned image.
     * @param maxHeight The maximum height of the returned image.
     * @return A container object that contains all of the properties of the request, as well as
     *     the currently available image (default if remote is not loaded).
     */
    public ImageContainer get(String requestUrl, ImageListener imageListener,
            int maxWidth, int maxHeight) {
        // only fulfill requests that were initiated from the main thread.
        // 仅仅去完成那些在主线程上发起调用的请求，因为最后需要设置ImageView，只能在UI线程，即主线程上操作。
        throwIfNotOnMainThread();

        final String cacheKey = getCacheKey(requestUrl, maxWidth, maxHeight);

        // Try to look up the request in the cache of remote images.
        // 尝试从cache中查找对应的图片
        Bitmap cachedBitmap = mCache.getBitmap(cacheKey);
        if (cachedBitmap != null) {
            // Return the cached bitmap.
            // 如果有，那么就分配一个ImageContainer
            ImageContainer container = new ImageContainer(cachedBitmap, requestUrl, null, null);
            // 设置相应的图片
            imageListener.onResponse(container, true);
            return container;
        }

        // The bitmap did not exist in the cache, fetch it!
        // 图片在cache中不存在，需要从网络上获取
        ImageContainer imageContainer =
                new ImageContainer(null, requestUrl, cacheKey, imageListener);

        // Update the caller to let them know that they should use the default bitmap.
        // 让其显示默认的图片
        imageListener.onResponse(imageContainer, true);

        // Check to see if a request is already in-flight.
        // 检查是否有可以合并的请求，已经在处理当中
        BatchedImageRequest request = mInFlightRequests.get(cacheKey);
        if (request != null) {
            // If it is, add this request to the list of listeners.
            // 如果有，则添加到相应的队列当中，并返回
            request.addContainer(imageContainer);
            return imageContainer;
        }

        // The request is not already in flight. Send the new request to the network and
        // track it.
        // 发起一个新的Image请求
        Request<Bitmap> newRequest = makeImageRequest(requestUrl, maxWidth, maxHeight, cacheKey);

        //添加到RequestQueue当中
        mRequestQueue.add(newRequest);
        mInFlightRequests.put(cacheKey,
                new BatchedImageRequest(newRequest, imageContainer));
        return imageContainer;
    }

    protected Request<Bitmap> makeImageRequest(String requestUrl, int maxWidth, int maxHeight, final String cacheKey) {
        return new ImageRequest(requestUrl, new Listener<Bitmap>() {
            @Override
            public void onResponse(Bitmap response) {
                onGetImageSuccess(cacheKey, response);
            }
        }, maxWidth, maxHeight,
        Config.RGB_565, new ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                onGetImageError(cacheKey, error);
            }
        });
    }

    /**
     * Sets the amount of time to wait after the first response arrives before delivering all
     * responses. Batching can be disabled entirely by passing in 0.
     * @param newBatchedResponseDelayMs The time in milliseconds to wait.
     */
    public void setBatchedResponseDelay(int newBatchedResponseDelayMs) {
        mBatchResponseDelayMs = newBatchedResponseDelayMs;
    }

    /**
     * Handler for when an image was successfully loaded.
     * 当图片下载完成之后的回调函数。
     * @param cacheKey The cache key that is associated with the image request.
     * @param response The bitmap that was returned from the network.
     */
    protected void onGetImageSuccess(String cacheKey, Bitmap response) {
        // cache the image that was fetched.
        // 将图片放入到cache当中
        mCache.putBitmap(cacheKey, response);

        // remove the request from the list of in-flight requests.
        // 已经完成，所以从正在执行的列表中删除,并看看还有没有同样的请求的其他的任务
        BatchedImageRequest request = mInFlightRequests.remove(cacheKey);

        //如果有其他的任务，则更新相应的任务
        if (request != null) {
            // Update the response bitmap.
            request.mResponseBitmap = response;

            // Send the batched response
            batchResponse(cacheKey, request);
        }
    }

    /**
     * Handler for when an image failed to load.
     * @param cacheKey The cache key that is associated with the image request.
     */
    protected void onGetImageError(String cacheKey, VolleyError error) {
        // Notify the requesters that something failed via a null result.
        // Remove this request from the list of in-flight requests.
        BatchedImageRequest request = mInFlightRequests.remove(cacheKey);

        if (request != null) {
            // Set the error for this request
            request.setError(error);

            // Send the batched response
            batchResponse(cacheKey, request);
        }
    }

    /**
     * Container object for all of the data surrounding an image request.
     * 一个容器，其中包含了围绕着image请求的所有的数据
     */
    public class ImageContainer {
        /**
         * The most relevant bitmap for the container. If the image was in cache, the
         * Holder to use for the final bitmap (the one that pairs to the requested URL).
         * 
         * 容器最相关的bitmap。如果图片是在cache中，那么容器会使用这个来指向最终的bitmap。
         */
        private Bitmap mBitmap;

        private final ImageListener mListener;

        /** The cache key that was associated with the request */
        private final String mCacheKey;

        /** The request URL that was specified */
        private final String mRequestUrl;

        /**
         * Constructs a BitmapContainer object.
         * @param bitmap The final bitmap (if it exists).
         * @param requestUrl The requested URL for this container.
         * @param cacheKey The cache key that identifies the requested URL for this container.
         */
        public ImageContainer(Bitmap bitmap, String requestUrl,
                String cacheKey, ImageListener listener) {
            mBitmap = bitmap;
            mRequestUrl = requestUrl;
            mCacheKey = cacheKey;
            mListener = listener;
        }

        /**
         * Releases interest in the in-flight request (and cancels it if no one else is listening).
         */
        public void cancelRequest() {
            if (mListener == null) {
                return;
            }

            BatchedImageRequest request = mInFlightRequests.get(mCacheKey);
            if (request != null) {
                //此时reqeust对应的已经开始在执行
              
                //判断是否需要取消整个任务，即同一个URL是否还有其他的地方在用
                boolean canceled = request.removeContainerAndCancelIfNecessary(this);
                if (canceled) {
                    mInFlightRequests.remove(mCacheKey);
                }
            } else {
                // check to see if it is already batched for delivery.
                //此时尚未执行
                request = mBatchedResponses.get(mCacheKey);
                if (request != null) {
                    request.removeContainerAndCancelIfNecessary(this);
                    if (request.mContainers.size() == 0) {
                        mBatchedResponses.remove(mCacheKey);
                    }
                }
            }
        }

        /**
         * Returns the bitmap associated with the request URL if it has been loaded, null otherwise.
         */
        public Bitmap getBitmap() {
            return mBitmap;
        }

        /**
         * Returns the requested URL for this container.
         */
        public String getRequestUrl() {
            return mRequestUrl;
        }
    }

    /**
     * Wrapper class used to map a Request to the set of active ImageContainer objects that are
     * interested in its results.
     * 一个包装来，用来包装那些活跃的ImageContainer的集合。
     */
    private class BatchedImageRequest {
        /** The request being tracked */
        private final Request<?> mRequest;

        /** The result of the request being tracked by this item */
        private Bitmap mResponseBitmap;

        /** Error if one occurred for this response */
        private VolleyError mError;

        /** List of all of the active ImageContainers that are interested in the request */
        /** 该图片的所有的请求，注意此reqeust并不是Volley中的Reqeust，而是需要做的任务，使用的ImageContainer类来保存相关的信息 */
        private final LinkedList<ImageContainer> mContainers = new LinkedList<ImageContainer>();

        /**
         * Constructs a new BatchedImageRequest object
         * @param request The request being tracked
         * @param container The ImageContainer of the person who initiated the request.
         */
        public BatchedImageRequest(Request<?> request, ImageContainer container) {
            mRequest = request;
            mContainers.add(container);
        }

        /**
         * Set the error for this response
         */
        public void setError(VolleyError error) {
            mError = error;
        }

        /**
         * Get the error for this response
         */
        public VolleyError getError() {
            return mError;
        }

        /**
         * Adds another ImageContainer to the list of those interested in the results of
         * the request.
         */
        public void addContainer(ImageContainer container) {
            mContainers.add(container);
        }

        /**
         * Detatches the bitmap container from the request and cancels the request if no one is
         * left listening.
         * 从容器中删掉对应的任务，如果删掉之后，对应的任务列表为空，则可以取消该任务，否则返回false
         * @param container The container to remove from the list
         * @return True if the request was canceled, false otherwise.
         */
        public boolean removeContainerAndCancelIfNecessary(ImageContainer container) {
            mContainers.remove(container);
            if (mContainers.size() == 0) {
                mRequest.cancel();
                return true;
            }
            return false;
        }
    }

    /**
     * Starts the runnable for batched delivery of responses if it is not already started.
     * 如果合并的任务，其分发线程还没有启动的话，则启动它。
     * 
     * @param cacheKey The cacheKey of the response being delivered.
     * @param request The BatchedImageRequest to be delivered.
     * @param error The volley error associated with the request (if applicable).
     */
    private void batchResponse(String cacheKey, BatchedImageRequest request) {
        //将cacheKey和对应的request添加到map当中
        mBatchedResponses.put(cacheKey, request);
        // If we don't already have a batch delivery runnable in flight, make a new one.
        // 如果没有启动合并分发线程的话，则启动它
        // Note that this will be used to deliver responses to all callers in mBatchedResponses.
        // 该线程会用来分发mBatchedResponses中对应的所有的回调。
        if (mRunnable == null) {
            mRunnable = new Runnable() {
                @Override
                public void run() {
                    //遍历所有的BatchedImageRequest
                    for (BatchedImageRequest bir : mBatchedResponses.values()) {
                        //依次进行回调
                        for (ImageContainer container : bir.mContainers) {
                            // If one of the callers in the batched request canceled the request
                            // after the response was received but before it was delivered,
                            // skip them.
                            if (container.mListener == null) {
                                continue;
                            }
                            if (bir.getError() == null) {
                                container.mBitmap = bir.mResponseBitmap;
                                container.mListener.onResponse(container, false);
                            } else {
                                container.mListener.onErrorResponse(bir.getError());
                            }
                        }
                    }
                    mBatchedResponses.clear();
                    mRunnable = null;
                }

            };
            // Post the runnable.
            mHandler.postDelayed(mRunnable, mBatchResponseDelayMs);
        }
    }

    /**
     * 判断当前是否工作在主线程上，如果不是，那么就抛出异常，保证一些任务是工作在主线程之上的。
     * */
    private void throwIfNotOnMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("ImageLoader must be invoked from the main thread.");
        }
    }
    /**
     * Creates a cache key for use with the L1 cache.
     * 创建L1 cache所使用的cache key
     * @param url The URL of the request.
     * @param maxWidth The max-width of the output.
     * @param maxHeight The max-height of the output.
     */
    private static String getCacheKey(String url, int maxWidth, int maxHeight) {
        return new StringBuilder(url.length() + 12).append("#W").append(maxWidth)
                .append("#H").append(maxHeight).append(url).toString();
    }
}
