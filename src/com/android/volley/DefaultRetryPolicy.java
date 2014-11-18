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
 * Default retry policy for requests.
 * 默认重试机制
 */
public class DefaultRetryPolicy implements RetryPolicy {
    /** The current timeout in milliseconds. */
    /** 当前的超时时间 */
    private int mCurrentTimeoutMs;

    /** The current retry count. */
    /** 当前重试次数 */
    private int mCurrentRetryCount;

    /** The maximum number of attempts. */
    /** 最大的重拾次数 */
    private final int mMaxNumRetries;

    /** The backoff multiplier for for the policy. */
    /*** 补偿系数 */
    private final float mBackoffMultiplier;

    /** The default socket timeout in milliseconds */
    /** 默认的超时时间 */
    public static final int DEFAULT_TIMEOUT_MS = 2500;

    /** The default number of retries */
    /** 默认重试次数 */
    public static final int DEFAULT_MAX_RETRIES = 1;

    /** The default backoff multiplier */
    /** 默认补偿系数 */
    public static final float DEFAULT_BACKOFF_MULT = 1f;

    /**
     * Constructs a new retry policy using the default timeouts.
     */
    public DefaultRetryPolicy() {
        this(DEFAULT_TIMEOUT_MS, DEFAULT_MAX_RETRIES, DEFAULT_BACKOFF_MULT);
    }

    /**
     * Constructs a new retry policy.
     * @param initialTimeoutMs The initial timeout for the policy.
     * @param maxNumRetries The maximum number of retries.
     * @param backoffMultiplier Backoff multiplier for the policy.
     */
    public DefaultRetryPolicy(int initialTimeoutMs, int maxNumRetries, float backoffMultiplier) {
        mCurrentTimeoutMs = initialTimeoutMs;
        mMaxNumRetries = maxNumRetries;
        mBackoffMultiplier = backoffMultiplier;
    }

    /**
     * Returns the current timeout.
     */
    @Override
    public int getCurrentTimeout() {
        return mCurrentTimeoutMs;
    }

    /**
     * Returns the current retry count.
     */
    @Override
    public int getCurrentRetryCount() {
        return mCurrentRetryCount;
    }

    /**
     * Prepares for the next retry by applying a backoff to the timeout.
     * @param error The error code of the last attempt.
     */
    @Override
    public void retry(VolleyError error) throws VolleyError {
        //重试次数+1
        mCurrentRetryCount++;
        //下次尝试的时间
        mCurrentTimeoutMs += (mCurrentTimeoutMs * mBackoffMultiplier);
        if (!hasAttemptRemaining()) {
            throw error;
        }
    }

    /**
     * Returns true if this policy has attempts remaining, false otherwise.
     */
    protected boolean hasAttemptRemaining() {
        //判断是否还有机会继续重试
        return mCurrentRetryCount <= mMaxNumRetries;
    }
}
