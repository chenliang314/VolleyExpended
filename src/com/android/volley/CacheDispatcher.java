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

import java.io.ByteArrayOutputStream;
/** modify by chenliang CY9681 20140728 begain. */
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
/** modify by chenliang CY9681 20140728 end. */
import java.util.concurrent.BlockingQueue;

import android.os.Process;


/** modify by chenliang CY9681 20140717 begain. */
import com.android.volley.toolbox.ImageRequest;
import com.android.volley.toolbox.JsonObjectRequest;
/** modify by chenliang CY9681 20140717 end. */
/**
 * Provides a thread for performing cache triage on a queue of requests.
 *
 * Requests added to the specified cache queue are resolved from cache.
 * Any deliverable response is posted back to the caller via a
 * {@link ResponseDelivery}.  Cache misses and responses that require
 * refresh are enqueued on the specified network queue for processing
 * by a {@link NetworkDispatcher}.
 */
public class CacheDispatcher extends Thread {

    private static final boolean DEBUG = VolleyLog.DEBUG;

    /** The queue of requests coming in for triage. */
    private final BlockingQueue<Request<?>> mCacheQueue;

    /** The queue of requests going out to the network. */
    private final BlockingQueue<Request<?>> mNetworkQueue;

    /** The cache to read from. */
    private final Cache mCache;

    /** For posting responses. */
    private final ResponseDelivery mDelivery;

    /** Used for telling us to die. */
    private volatile boolean mQuit = false;
    
    protected boolean mPauseWork = false;
    protected Object mPauseWorkLock = new Object();
    /**
     * Creates a new cache triage dispatcher thread.  You must call {@link #start()}
     * in order to begin processing.
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

    public void setPauseWork(boolean pauseWork) {
        synchronized (mPauseWorkLock) {
            mPauseWork = pauseWork;
            if (!mPauseWork) {
                mPauseWorkLock.notifyAll();
            }
        }
    }

    /**
     * Forces this dispatcher to quit immediately.  If any requests are still in
     * the queue, they are not guaranteed to be processed.
     */
    public void quit() {
        mQuit = true;
        interrupt();
    }

    /**
     * return Data and metadata for an entry returned by the disk cache.
     * @author chenliang
     */
    private Cache.Entry getLocalEntryByFile(Request<?> request){
        int bufferSize = 4096;
        FileInputStream in = null;
        ByteArrayOutputStream bos = null;
        File file = request.getDownloadFile();
        try {
            in = new FileInputStream(file);
            bos = new ByteArrayOutputStream(bufferSize);
            byte[] bytes = new byte[bufferSize];
            while (in.read(bytes) != -1) {  
                bos.write(bytes);
            } 
            Cache.Entry entry = new Cache.Entry();
            entry.data = bos.toByteArray();
            entry.etag = null;
            entry.softTtl = System.currentTimeMillis();
            entry.ttl = entry.softTtl;
            entry.serverDate = entry.softTtl;
            return entry;
        } catch (ArrayIndexOutOfBoundsException e) {
            e.printStackTrace();
            if (file != null) {
                file.delete();
             }
        }catch(OutOfMemoryError e){

        } catch (IOException e) {
            if (file != null) {
               file.delete();
            }
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
                if (bos != null) {
                    bos.flush();
                    bos.close();
                }
            } catch (IOException ignored) { }
        }
        return null;
    }

    @Override
    public void run() {
        if (DEBUG) VolleyLog.v("start new dispatcher");
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        // Make a blocking call to initialize the cache.
        mCache.initialize();

        while (true) {
            try {
                // Get a request from the cache triage queue, blocking until
                // at least one is available.
                final Request<?> request = mCacheQueue.take();
                request.addMarker("cache-queue-take");

                // If the request has been canceled, don't bother dispatching it.
                if (request.isCanceled()) {
                    request.finish("cache-discard-canceled");
                    continue;
                }
                // Wait here if work is paused and the task is not cancelled
                synchronized (mPauseWorkLock) {
                    while (mPauseWork && !request.isCanceled()) {
                        try {
                            mPauseWorkLock.wait();
                        } catch (InterruptedException e) {}
                    }
                }
                // Attempt to retrieve this item from cache.
                Cache.Entry entry = mCache.get(request.getCacheKey());
                if (entry == null && request.getDownloadFile() != null 
                        && request.getDownloadFile().exists()) {
                    entry = getLocalEntryByFile(request);
                } 
                if (entry == null) {
                    request.addMarker("cache-miss");
                    // Cache miss; send off to the network dispatcher.
                    mNetworkQueue.put(request);
                    continue;
                }
                // If it is completely expired, just send it to the network.
                if (request instanceof JsonObjectRequest 
                        && !request.useDiskCacheData() 
                        && entry.isExpired()) {
                    request.addMarker("cache-hit-expired");
                    request.setCacheEntry(entry);
                    mNetworkQueue.put(request);
                    continue;
                }
                // We have a cache hit; parse its data for delivery back to the request.
                request.addMarker("cache-hit");
                NetworkResponse networkResponse = new NetworkResponse(entry.data, entry.responseHeaders);
                networkResponse.isCacheDispatcher = true;
                Response<?> response = request.parseNetworkResponse(networkResponse);
                request.addMarker("cache-hit-parsed");
                if (request instanceof ImageRequest 
                        || request.useDiskCacheData() 
                        || !entry.refreshNeeded()) {
                    // Completely unexpired cache hit. Just deliver the response.
                    mDelivery.postResponse(request, response);
                } else {
                    // Soft-expired cache hit. We can deliver the cached response,
                    // but we need to also send the request to the network for
                    // refreshing.
                    request.addMarker("cache-hit-refresh-needed");
                    request.setCacheEntry(entry);

                    // Mark the response as intermediate.
                    response.intermediate = true;

                    // Post the intermediate response back to the user and have
                    // the delivery then forward the request along to the network.
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
