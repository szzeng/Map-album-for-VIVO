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
    interface Request { void cancel(); }
    interface DurationCallback { void complete(long duration); }
    private static final Request COMPLETE = () -> {};
    private static final int LIMIT_BYTES=32*1024*1024;
    private final ContentResolver resolver;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(64));
    private final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(LIMIT_BYTES){
        @Override protected int sizeOf(String key,Bitmap value){return value.getAllocationByteCount();}
    };
    private final Map<String,Pending> pending=new HashMap<>();
    private final LruCache<String,Long> durationCache=new LruCache<>(512);
    private final Map<android.os.CancellationSignal,Runnable> durationJobs=new HashMap<>();
    private boolean closed;
    private int startedCount, canceledCount, hitCount;
    private static final class Pending {
        final CancellationSignal signal=new CancellationSignal();
        final ArrayList<Callback> callbacks=new ArrayList<>();
        Runnable task;
    }
    ThumbnailLoader(ContentResolver resolver){this.resolver=resolver;}
    synchronized Bitmap cachedThumbnail(Uri uri,long version,int size){
        return closed||uri==null?null:cache.get(uri.toString()+"|"+version+"|"+size+"|thumbnail");
    }
    void load(Uri uri,long version,int size,Callback callback){
        if(enqueue(uri,version,size,false,callback)==null)main.post(()->callback.complete(null));
    }
    /** A full queue is temporary. A null bitmap from an accepted request is a decode failure. */
    Request tryLoad(Uri uri,long version,int size,Callback callback){
        return enqueue(uri,version,size,false,callback);
    }
    /** Decode a bounded preview only when the provider supplies an undersized thumbnail. */
    void loadSampled(Uri uri,long version,int size,Callback callback){
        if(enqueue(uri,version,size,true,callback)==null)main.post(()->callback.complete(null));
    }
    private Request enqueue(Uri uri,long version,int size,boolean sampled,Callback callback){
        if(uri==null||size<=0||callback==null)throw new IllegalArgumentException();
        String key=uri.toString()+"|"+version+"|"+size+(sampled?"|sampled":"|thumbnail");
        synchronized(this){
            if(closed)return null;
            Bitmap cached=cache.get(key);if(cached!=null){hitCount++;callback.complete(cached);return COMPLETE;}
            Pending existing=pending.get(key);
            if(existing!=null){
                existing.callbacks.add(callback);
                return ()->cancel(key,existing,callback);
            }
            final Pending job=new Pending();
            job.callbacks.add(callback);
            job.task=()->decode(key,uri,size,job,sampled);
            pending.put(key,job);
            try {workers.execute(job.task);startedCount++;}
            catch(RejectedExecutionException e){pending.remove(key);return null;}
            return ()->cancel(key,job,callback);
        }
    }
    private synchronized void cancel(String key,Pending job,Callback callback){
        if(pending.get(key)!=job||!job.callbacks.remove(callback)||!job.callbacks.isEmpty())return;
        pending.remove(key);
        canceledCount++;
        if(!workers.remove(job.task))job.signal.cancel();
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
            if(BuildConfig.DEBUG)android.util.Log.d("MAP004Thumb","type="+(uri.getPathSegments().contains("video")?"video":"photo")+" loaded="+(bitmap!=null)+" requested_px="+size+" elapsed_ms="+(android.os.SystemClock.elapsedRealtime()-started));}
        catch(Exception error) {if(bitmap!=null){bitmap.recycle();bitmap=null;}if(BuildConfig.DEBUG&&!job.signal.isCanceled())android.util.Log.d("MAP004Thumb","failed="+error.getClass().getSimpleName());}
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
    Request tryLoadDuration(Uri uri,long version,DurationCallback callback){
        if(uri==null||callback==null)throw new IllegalArgumentException();
        String key=uri.toString()+"|"+version;
        synchronized(this){
            if(closed)return null;
            Long cached=durationCache.get(key);
            if(cached!=null){main.post(()->callback.complete(cached));return COMPLETE;}
            CancellationSignal signal=new CancellationSignal();
            Runnable task=()->{
                long duration=-1;
                try(android.database.Cursor cursor=resolver.query(uri,new String[]{android.provider.MediaStore.Video.VideoColumns.DURATION},null,null,null,signal)){
                    if(cursor!=null&&cursor.moveToFirst()&&!cursor.isNull(0))duration=cursor.getLong(0);
                }catch(RuntimeException ignored){}
                final long result=duration;
                main.post(()->{
                    synchronized(ThumbnailLoader.this){if(closed||durationJobs.remove(signal)==null||signal.isCanceled())return;if(result>=0)durationCache.put(key,result);}
                    callback.complete(result);
                });
            };
            durationJobs.put(signal,task);
            try{workers.execute(task);}catch(RejectedExecutionException busy){durationJobs.remove(signal);return null;}
            return ()->{synchronized(ThumbnailLoader.this){Runnable job=durationJobs.remove(signal);if(job!=null){workers.remove(job);signal.cancel();}}};
        }
    }
    synchronized void cancelPending(){
        canceledCount+=pending.size();for(Pending job:pending.values())job.signal.cancel();pending.clear();for(CancellationSignal signal:durationJobs.keySet())signal.cancel();durationJobs.clear();workers.getQueue().clear();
    }
    synchronized String diagnosticCounts(){return "缩略图请求 "+startedCount+" · 已取消 "+canceledCount+" · 进行中 "+pending.size()+" · 缓存命中 "+hitCount+" · 缓存字节 "+cache.size();}
    synchronized void clear(){cancelPending();cache.evictAll();durationCache.evictAll();}
    @Override public synchronized void close(){if(closed)return;clear();closed=true;workers.shutdownNow();}
}
