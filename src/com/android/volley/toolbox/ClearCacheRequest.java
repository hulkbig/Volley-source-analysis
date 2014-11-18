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

package com.android.volley.toolbox;

import com.android.volley.Cache;
import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.Response;

import android.os.Handler;
import android.os.Looper;

/**
 * A synthetic request used for clearing the cache.
 * 一个认为的用来清空cache的缓存
 */
public class ClearCacheRequest extends Request<Object> {
    private final Cache mCache;
    private final Runnable mCallback;

    /**
     * Creates a synthetic request for clearing the cache.
     * @param cache Cache to clear
     * @param callback Callback to make on the main thread once the cache is clear,
     * or null for none
     */
    public ClearCacheRequest(Cache cache, Runnable callback) {
        super(Method.GET, null, null);
        mCache = cache;
        mCallback = callback;
    }

    @Override
    public boolean isCanceled() {
        // This is a little bit of a hack, but hey, why not.
        // 清空cache
        mCache.clear();
        if (mCallback != null) {
            Handler handler = new Handler(Looper.getMainLooper());
            //将该任务放到handler的最前面，使其首先得到处理。此时已经完成任务了，收尾工作。
            handler.postAtFrontOfQueue(mCallback);
        }
        return true;
    }

    //优先级设置为最高，使得调度的时候首先得到处理
    @Override
    public Priority getPriority() {
        return Priority.IMMEDIATE;
    }

    @Override
    protected Response<Object> parseNetworkResponse(NetworkResponse response) {
        return null;
    }

    @Override
    protected void deliverResponse(Object response) {
    }
}
