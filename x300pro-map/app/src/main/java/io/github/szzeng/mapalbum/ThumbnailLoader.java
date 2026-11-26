package io.github.szzeng.mapalbum;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Size;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded in-memory thumbnail loading. Cached bitmaps are never persisted. */
final class ThumbnailLoader implements AutoCloseable {
    interface Callback { void complete(Bitmap bitmap); }
    private static final int LIMIT_BYTES=32*1024*1024;
    private final ContentResolver resolver;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(64));
    private final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(LIMIT_BYTES){
        @Override protected int sizeOf(String key,Bitmap value){return value.getAllocationByteCount();}
    };
    private final Map<String,Pending> pending=new HashMap<>();
    private boolean closed;
    private int startedCount, canceledCount;
    private static final class Pending {
        final CancellationSignal signal=new CancellationSignal();
        final ArrayList<Callback> callbacks=new ArrayList<>();
    }
    ThumbnailLoader(ContentResolver resolver){this.resolver=resolver;}
    void load(Uri uri,long version,int size,Callback callback){
        enqueue(uri,version,size,false,callback);
    }
    /** Decode a bounded preview only when the provider supplies an undersized thumbnail. */
    void loadSampled(Uri uri,long version,int size,Callback callback){
        enqueue(uri,version,size,true,callback);
    }
    private void enqueue(Uri uri,long version,int size,boolean sampled,Callback callback){
        if(uri==null||size<=0||callback==null)throw new IllegalArgumentException();
        String key=uri.toString()+"|"+version+"|"+size+(sampled?"|sampled":"|thumbnail");
        final Pending job;
        synchronized(this){
            if(closed)return;
            Bitmap cached=cache.get(key);if(cached!=null){callback.complete(cached);return;}
            Pending existing=pending.get(key);if(existing!=null){existing.callbacks.add(callback);return;}
            job=new Pending();job.callbacks.add(callback);pending.put(key,job);startedCount++;
            try {workers.execute(()->decode(key,uri,size,job,sampled));}
            catch(RejectedExecutionException e){pending.remove(key);callback.complete(null);}
        }
    }
    private void decode(String key,Uri uri,int size,Pending job,boolean sampled){
        Bitmap bitmap=null;long started=android.os.SystemClock.elapsedRealtime();
        try {if(sampled){
                job.signal.throwIfCanceled();
                bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver,uri),(decoder,info,source)->{
                    job.signal.throwIfCanceled();decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int width=info.getSize().getWidth(),height=info.getSize().getHeight();int largest=Math.max(width,height);
                    if(largest>size){double scale=(double)size/largest;decoder.setTargetSize(Math.max(1,(int)Math.round(width*scale)),Math.max(1,(int)Math.round(height*scale)));}
                });
            }else bitmap=resolver.loadThumbnail(uri,new Size(size,size),job.signal);job.signal.throwIfCanceled();
            android.util.Log.i("MAP004Thumb","loaded="+(bitmap!=null)+" requested_px="+size+" elapsed_ms="+(android.os.SystemClock.elapsedRealtime()-started));}
        catch(Exception error) {if(bitmap!=null){bitmap.recycle();bitmap=null;}android.util.Log.i("MAP004Thumb","failed="+error.getClass().getSimpleName());}
        final Bitmap ready=bitmap;
        main.post(()->{
            ArrayList<Callback> callbacks;
            synchronized(ThumbnailLoader.this){
                if(closed||pending.get(key)!=job||job.signal.isCanceled()){if(ready!=null)ready.recycle();return;}
                pending.remove(key);if(ready!=null)cache.put(key,ready);callbacks=new ArrayList<>(job.callbacks);
            }
            for(Callback callback:callbacks)callback.complete(ready);
        });
    }
    synchronized void cancelPending(){
        canceledCount+=pending.size();for(Pending job:pending.values())job.signal.cancel();pending.clear();workers.getQueue().clear();
    }
    synchronized String diagnosticCounts(){return "缩略图请求 "+startedCount+" · 已取消 "+canceledCount+" · 进行中 "+pending.size();}
    synchronized void clear(){cancelPending();cache.evictAll();}
    @Override public synchronized void close(){if(closed)return;clear();closed=true;workers.shutdownNow();}
}
