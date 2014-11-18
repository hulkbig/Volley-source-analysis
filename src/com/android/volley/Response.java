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

/**
 * Encapsulates a parsed response for delivery.
 *
 * @param <T> Parsed type of this response
 */
public class Response<T> {

    /** Callback interface for delivering parsed responses. */
    /** 用来分发响应的回调接口 */
    public interface Listener<T> {
        /** Called when a response is received. */
        public void onResponse(T response);
    }

    /** Callback interface for delivering error responses. */
    /** 用来分发错误信息的回调接口*/
    public interface ErrorListener {
        /**
         * Callback method that an error has been occurred with the
         * provided error code and optional user-readable message.
         */
        public void onErrorResponse(VolleyError error);
    }

    /** Returns a successful response containing the parsed result. */
    /** 返回一个成功的response，包括已经解析处理过的结果 */
    public static <T> Response<T> success(T result, Cache.Entry cacheEntry) {
        return new Response<T>(result, cacheEntry);
    }

    /**
     * Returns a failed response containing the given error code and an optional
     * localized message displayed to the user.
     */
    public static <T> Response<T> error(VolleyError error) {
        return new Response<T>(error);
    }

    /** Parsed response, or null in the case of error. */
    /** 解析过之后的相应，如果出现错误的时候，result的数据为null*/
    public final T result;

    /** Cache metadata for this response, or null in the case of error. */
    /** 此response所对应的Cache的元数据信息，如果出错的话，则置为null*/
    public final Cache.Entry cacheEntry;

    /** Detailed error information if <code>errorCode != OK</code>. */
    /** 详细的错误信息*/
    public final VolleyError error;

    /** True if this response was a soft-expired one and a second one MAY be coming. */
    /** 如果该变量为真，那么意味着一个这是一个暂时的中间的结果，后面可能还会有一个结果*/
    public boolean intermediate = false;

    /**
     * Returns whether this response is considered successful.
     */
    public boolean isSuccess() {
        return error == null;
    }


    private Response(T result, Cache.Entry cacheEntry) {
        this.result = result;
        this.cacheEntry = cacheEntry;
        this.error = null;
    }

    private Response(VolleyError error) {
        this.result = null;
        this.cacheEntry = null;
        this.error = error;
    }
}
