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

import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Logging helper class. */
/** 日志工具类 */
public class VolleyLog {
    public static String TAG = "Volley";

    //根据系统设置，判断Log.VERBOSE是否可以Log输出。
    public static boolean DEBUG = Log.isLoggable(TAG, Log.VERBOSE);

    /**
     * Customize the log tag for your application, so that other apps
     * using Volley don't mix their logs with yours.
     * 自定义log的tag，这样就不会和其他的使用Volley的程序的log混在一起了。
     * 
     * <br />
     * Enable the log property for your tag before starting your app:
     * <br />
     * {@code adb shell setprop log.tag.&lt;tag&gt;}
     */
    public static void setTag(String tag) {
        d("Changing log tag to %s", tag);
        TAG = tag;

        // Reinitialize the DEBUG "constant"
        DEBUG = Log.isLoggable(TAG, Log.VERBOSE);
    }

    public static void v(String format, Object... args) {
        if (DEBUG) {
            Log.v(TAG, buildMessage(format, args));
        }
    }

    public static void d(String format, Object... args) {
        Log.d(TAG, buildMessage(format, args));
    }

    public static void e(String format, Object... args) {
        Log.e(TAG, buildMessage(format, args));
    }

    public static void e(Throwable tr, String format, Object... args) {
        Log.e(TAG, buildMessage(format, args), tr);
    }

    public static void wtf(String format, Object... args) {
        Log.wtf(TAG, buildMessage(format, args));
    }

    public static void wtf(Throwable tr, String format, Object... args) {
        Log.wtf(TAG, buildMessage(format, args), tr);
    }

    /**
     * Formats the caller's provided message and prepends useful info like
     * calling thread ID and method name.
     */
    private static String buildMessage(String format, Object... args) {
        //判断args==null?如果为null，那么则直接输入format，否则则按照format格式化输出args
        String msg = (args == null) ? format : String.format(Locale.US, format, args);
        //获取调用该函数的任务栈
        StackTraceElement[] trace = new Throwable().fillInStackTrace().getStackTrace();

        //默认的调用者，如果无法找到调用的类，就打印为<unknow>
        String caller = "<unknown>";
        // Walk up the stack looking for the first caller outside of VolleyLog.
        // 跟踪stack，找到从VolleyLog之外调用的第一个调用者
        // It will be at least two frames up, so start there.
        // 至少包含两个帧，VolleyLog类，buildMessage方法，所以第3个开始，下标为2，参考：https://docs.oracle.com/javase/7/docs/api/java/lang/Throwable.html#printStackTrace()
        for (int i = 2; i < trace.length; i++) {
            Class<?> clazz = trace[i].getClass();
            if (!clazz.equals(VolleyLog.class)) {
                String callingClass = trace[i].getClassName();
                callingClass = callingClass.substring(callingClass.lastIndexOf('.') + 1);
                callingClass = callingClass.substring(callingClass.lastIndexOf('$') + 1);

                caller = callingClass + "." + trace[i].getMethodName();
                break;
            }
        }
        return String.format(Locale.US, "[%d] %s: %s",
                Thread.currentThread().getId(), caller, msg);
    }

    /**
     * A simple event log with records containing a name, thread ID, and timestamp.
     * 一个简单的时间记录类，包括名称，thread Id，时间戳
     */
    static class MarkerLog {
        public static final boolean ENABLED = VolleyLog.DEBUG;

        /** Minimum duration from first marker to last in an marker log to warrant logging. */
        private static final long MIN_DURATION_FOR_LOGGING_MS = 0;

        private static class Marker {
            public final String name;
            public final long thread;
            public final long time;

            public Marker(String name, long thread, long time) {
                this.name = name;
                this.thread = thread;
                this.time = time;
            }
        }

        private final List<Marker> mMarkers = new ArrayList<Marker>();
        private boolean mFinished = false;

        /** Adds a marker to this log with the specified name. */
        /** 由于多个线程可能同时记录日志，所以该方法是线程安全的。  */
        public synchronized void add(String name, long threadId) {
            if (mFinished) {
                throw new IllegalStateException("Marker added to finished log");
            }
            //将新的记录添加到列表中
            mMarkers.add(new Marker(name, threadId, SystemClock.elapsedRealtime()));
        }

        /**
         * Closes the log, dumping it to logcat if the time difference between
         * the first and last markers is greater than {@link #MIN_DURATION_FOR_LOGGING_MS}.
         * @param header Header string to print above the marker log.
         */
        public synchronized void finish(String header) {
            mFinished = true;

            long duration = getTotalDuration();
            //如果需要记录的时间太短，则直接不记录。
            if (duration <= MIN_DURATION_FOR_LOGGING_MS) {
                return;
            }
            //开始的时间
            long prevTime = mMarkers.get(0).time;
            d("(%-4d ms) %s", duration, header);
            for (Marker marker : mMarkers) {
                long thisTime = marker.time;
                d("(+%-4d) [%2d] %s", (thisTime - prevTime), marker.thread, marker.name);
                prevTime = thisTime;
            }
        }

        @Override
        protected void finalize() throws Throwable {
            // Catch requests that have been collected (and hence end-of-lifed)
            // but had no debugging output printed for them.
            if (!mFinished) {
                finish("Request on the loose");
                e("Marker log finalized without finish() - uncaught exit point for request");
            }
        }

        /** Returns the time difference between the first and last events in this log. */
        /** 获取从记录开始，到当前的时间 */
        private long getTotalDuration() {
            if (mMarkers.size() == 0) {
                return 0;
            }

            long first = mMarkers.get(0).time;
            long last = mMarkers.get(mMarkers.size() - 1).time;
            return last - first;
        }
    }
}
