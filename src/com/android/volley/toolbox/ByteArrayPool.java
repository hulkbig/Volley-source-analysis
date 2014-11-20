/*
 * Copyright (C) 2012 The Android Open Source Project
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

/**
 * ByteArrayPool is a source and repository of <code>byte[]</code> objects. Its purpose is to
 * supply those buffers to consumers who need to use them for a short period of time and then
 * dispose of them. Simply creating and disposing such buffers in the conventional manner can
 * considerable heap churn and garbage collection delays on Android, which lacks good management of
 * short-lived heap objects. It may be advantageous to trade off some memory in the form of a
 * permanently allocated pool of buffers in order to gain heap performance improvements; that is
 * what this class does.
 * 
 * ByteArrayPool是一个byte[]对象的源和仓库。其目的在于支持那些用户所需要的使用一段时间，然后丢弃的buffer。
 * 在一般的情况下，直接创建，然后丢弃这样的buffer堆空间的大量消耗以及在Android设备的垃圾回收延时，这些都是缺少
 * 一个良好的对于短生命周期的堆的管理。申请一块永久的空间来获取堆性能上的提升是有价值的，这也是这个类的作用。
 * 
 * <p>
 * A good candidate user for this class is something like an I/O system that uses large temporary
 * <code>byte[]</code> buffers to copy data around. In these use cases, often the consumer wants
 * the buffer to be a certain minimum size to ensure good performance (e.g. when copying data chunks
 * off of a stream), but doesn't mind if the buffer is larger than the minimum. Taking this into
 * account and also to maximize the odds of being able to reuse a recycled buffer, this class is
 * free to return buffers larger than the requested size. The caller needs to be able to gracefully
 * deal with getting buffers any size over the minimum.
 * 一个比较好的使用环境是比如像I/O系统中大量的临时的byte[]缓冲的数据拷贝。在这些使用情况中，大多数情况下，用户想要
 * 一个比较小的特定的空间来保证一个好的性能(比如从一个数据流中拷贝一个数据块)，但并不会关心这个buffer是否大于他们
 * 所需要的最小的块。考虑到这些，也是尽可能的重用这个可回收的buffer，这个类返回大于请求大小的buffer。调用者需要优雅
 * 的处理任何比最小的所需要的buffer大的空间。
 * <p>
 * If there is not a suitably-sized buffer in its recycling pool when a buffer is requested, this
 * class will allocate a new buffer and return it.
 * 如果一个请求提交的时候，在回收池当中没有合适的大小的buffer，那么这个类就会申请一块新的空间并且返回。
 * <p>
 * This class has no special ownership of buffers it creates; the caller is free to take a buffer
 * it receives from this pool, use it permanently, and never return it to the pool; additionally,
 * it is not harmful to return to this pool a buffer that was allocated elsewhere, provided there
 * are no other lingering references to it.
 * 该类对于其创建的buffer没有拥有者的概念；调用者可以自由的从pool中获取buffer，永久的使用这些buffer，不再归还。
 * 除此之外，如果从其他的地方申请了buffer，归还到这个位置，也是没有任何坏处的。
 * <p>
 * This class ensures that the total size of the buffers in its recycling pool never exceeds a
 * certain byte limit. When a buffer is returned that would cause the pool to exceed the limit,
 * least-recently-used buffers are disposed.
 * 这个类保证了回收池中总共的buffer的大小用于不会超过其限制。当返回一个buffer会导致buffer池接近其极限的时候，
 * least-recently-used的buffer就会被丢弃。
 */
/**
 * 我的理解：该buffer的作用就在于通过保存一些buffer来减少申请空间的次数，从而更加优化堆的使用。
 * 其方法是，当需要buffer的时候，从这里面取，如果没有合适的，那么就申请，如果申请到了的话，那么就将这块buffer从
 * 记录的List中删除。
 * 当buffer不用的时候，就将buffer归还，方法是，首先根据buffer的长度将buffer放入到mBuffersBySize的合适的位置上，
 * 然后判断新的buffer的大小是否超过了预设的大小限制，如果超过了的话，那么就从中删除那个占用空间最小的。
 *
 */
public class ByteArrayPool {
    /** The buffer pool, arranged both by last use and by buffer size */
    /** buffer池，通过LastUse和大小管理 */
    private List<byte[]> mBuffersByLastUse = new LinkedList<byte[]>();
    private List<byte[]> mBuffersBySize = new ArrayList<byte[]>(64);

    /** The total size of the buffers in the pool */
    /** 池中所有的buffer大小的总和 */
    private int mCurrentSize = 0;

    /**
     * The maximum aggregate size of the buffers in the pool. Old buffers are discarded to stay
     * under this limit.
     * 最大的buffers的大小的数量。当接近最大值的时候，就的buffer就会被丢弃。
     */
    private final int mSizeLimit;

    /** Compares buffers by size */
    /** 通过buffer的大小进行比较  */
    protected static final Comparator<byte[]> BUF_COMPARATOR = new Comparator<byte[]>() {
        @Override
        public int compare(byte[] lhs, byte[] rhs) {
            return lhs.length - rhs.length;
        }
    };

    /**
     * @param sizeLimit the maximum size of the pool, in bytes
     */
    public ByteArrayPool(int sizeLimit) {
        mSizeLimit = sizeLimit;
    }

    /**
     * Returns a buffer from the pool if one is available in the requested size, or allocates a new
     * one if a pooled one is not available.
     * 从池总获取一个buffer，如果大小合适，则直接读取，如果不合适，则申请一块空间。
     *
     * @param len the minimum size, in bytes, of the requested buffer. The returned buffer may be
     *        larger.
     * @return a byte[] buffer is always returned.
     */
    public synchronized byte[] getBuf(int len) {
        for (int i = 0; i < mBuffersBySize.size(); i++) {
            byte[] buf = mBuffersBySize.get(i);
            if (buf.length >= len) {
                //此块buffer大小满足长度要求
              
                //当前buffer池可用大小减去buf的长度
                mCurrentSize -= buf.length;
                //该buffer刚刚被使用，所以从mBuffersBySize中删掉
                mBuffersBySize.remove(i);
                //该buffer刚刚被使用，所以从mBuufersByLastUse中删掉
                mBuffersByLastUse.remove(buf);
                //返回该buffer
                return buf;
            }
        }
        return new byte[len];
    }

    /**
     * Returns a buffer to the pool, throwing away old buffers if the pool would exceed its allotted
     * size.
     * 将一块buffer归还给池，如果pool达到其分配的大小，则丢弃任何的旧的buffer。
     * @param buf the buffer to return to the pool.
     */
    public synchronized void returnBuf(byte[] buf) {
        if (buf == null || buf.length > mSizeLimit) {
            return;
        }
        //将该buffer放入到mBuufersByLastUse当中。
        mBuffersByLastUse.add(buf);
        //找到在mBuffersBySize中合适的位置。
        int pos = Collections.binarySearch(mBuffersBySize, buf, BUF_COMPARATOR);
        if (pos < 0) {
            pos = -pos - 1;
        }
        //将buffer添加到合适的位置上。
        mBuffersBySize.add(pos, buf);
        //将可用的buffer长度进行修改。
        mCurrentSize += buf.length;
        trim();
    }

    /**
     * Removes buffers from the pool until it is under its size limit.
     * 如果当前系统中的可用buffer过多的话，则删除其中最小的buffer。
     */
    private synchronized void trim() {
        while (mCurrentSize > mSizeLimit) {
            byte[] buf = mBuffersByLastUse.remove(0);
            mBuffersBySize.remove(buf);
            mCurrentSize -= buf.length;
        }
    }

}
