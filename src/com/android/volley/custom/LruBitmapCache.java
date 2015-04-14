package com.android.volley.custom;

import java.lang.ref.SoftReference;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build.VERSION_CODES;
import android.support.v4.util.LruCache;

import com.android.volley.toolbox.ImageLoader.ImageCache;

public class LruBitmapCache extends LruCache<String, BitmapDrawable> implements ImageCache {
    
    private Set<SoftReference<Bitmap>> mReusableBitmaps;

    public LruBitmapCache(Context context,float percent){
        super(getAllocatedMemorry(context, percent));
        if (VolleyUtils.hasHoneycomb()) {
            mReusableBitmaps = Collections.synchronizedSet(new HashSet<SoftReference<Bitmap>>());
        }
    }
    
    private static int getAllocatedMemorry(Context context, float percent) {
        int memorry = ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryClass();
        return (int) (memorry * percent * 1024 * 1024);
    }

    /**
     * Get the size in bytes of a bitmap in a BitmapDrawable. Note that from Android 4.4 (KitKat)
     * onward this returns the allocated memory size of the bitmap which can be larger than the
     * actual bitmap data byte count (in the case it was re-used).
     *
     * @param value
     * @return size in bytes
     */
    @TargetApi(VERSION_CODES.KITKAT)
    protected int sizeOf(String key, BitmapDrawable value) {
        Bitmap bitmap = value.getBitmap();
        int bitmapSize = 0;
        // From KitKat onward use getAllocationByteCount() as allocated bytes can potentially be
        // larger than bitmap byte count.
        if (VolleyUtils.hasKitKat()) {
            bitmapSize = bitmap.getAllocationByteCount();
        } else if (VolleyUtils.hasHoneycombMR1()) {
            bitmapSize = bitmap.getByteCount();
        } else {
            // Pre HC-MR1
            bitmapSize = bitmap.getRowBytes() * bitmap.getHeight();
        }
        return bitmapSize == 0 ? 1 : bitmapSize;
    }

    @Override
    public BitmapDrawable getBitmap(String url) {
        return get(url);
    }

    @Override
    public void putBitmap(String url, BitmapDrawable drawable) {
        if (RecyclingBitmapDrawable.class.isInstance(drawable)) {
            // The removed entry is a recycling drawable, so notify it
            // that it has been added into the memory cache
            ((RecyclingBitmapDrawable) drawable).setIsCached(true);
        }
        put(url, drawable);
    }

    @Override
    protected void entryRemoved(boolean evicted, String key, BitmapDrawable oldValue,
            BitmapDrawable newValue) {
        if (RecyclingBitmapDrawable.class.isInstance(oldValue)) {
            // The removed entry is a recycling drawable, so notify it
            // that it has been removed from the memory cache
            ((RecyclingBitmapDrawable) oldValue).setIsCached(false);
        } else {
            // The removed entry is a standard BitmapDrawable
            if (VolleyUtils.hasHoneycomb()) {
                // We're running on Honeycomb or later, so add the bitmap
                // to a SoftReference set for possible use with inBitmap later
                mReusableBitmaps.add(new SoftReference<Bitmap>(oldValue.getBitmap()));
            }
        }
    }

    public void clearAllCache() {
        evictAll();
        if (mReusableBitmaps != null) {
            mReusableBitmaps.clear();
        }
    }

    /**
     * @param options - BitmapFactory.Options with out* options populated
     * @return Bitmap that case be used for inBitmap
     */
    public Bitmap getBitmapFromReusableSet(BitmapFactory.Options options) {
        //BEGIN_INCLUDE(get_bitmap_from_reusable_set)
        Bitmap bitmap = null;

        if (mReusableBitmaps != null && !mReusableBitmaps.isEmpty()) {
            synchronized (mReusableBitmaps) {
                final Iterator<SoftReference<Bitmap>> iterator = mReusableBitmaps.iterator();
                Bitmap item;

                while (iterator.hasNext()) {
                    item = iterator.next().get();

                    if (null != item && item.isMutable() && !item.isRecycled()) {
                        // Check to see it the item can be used for inBitmap
                        if (VolleyUtils.canUseForInBitmap(item, options)) {
                            bitmap = item;
                            // Remove from reusable set so it can't be used again
                            iterator.remove();
                            break;
                        }
                    } else {
                        // Remove from the set if the reference has been cleared.
                        iterator.remove();
                    }
                }
            }
        }

        return bitmap;
        //END_INCLUDE(get_bitmap_from_reusable_set)
    }

}
