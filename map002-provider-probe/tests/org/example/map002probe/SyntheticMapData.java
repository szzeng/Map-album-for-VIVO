package org.example.map002probe;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.CancellationSignal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only generator: never accesses device media or vendor data. */
final class SyntheticMapData extends MapDataRepository.SyntheticBackend {
        final int count;final boolean fixtureEnabled,scopeOk;boolean unexpected,denied,invalidVolume,wrongMetadata,holdThumbnail;int pauseMs;
        volatile Bitmap lastThumbnail;
        final CountDownLatch thumbnailEntered=new CountDownLatch(1),thumbnailRelease=new CountDownLatch(1);final AtomicInteger reads=new AtomicInteger(),batches=new AtomicInteger();
        final CountDownLatch batchProcessed=new CountDownLatch(1);
        final CountDownLatch entered=new CountDownLatch(1),release;
        SyntheticMapData(int count,boolean verified,boolean scopeOk,boolean hold){this.count=count;this.fixtureEnabled=verified;this.scopeOk=scopeOk;release=new CountDownLatch(hold?1:0);}
        boolean fixtureEnabled(){return fixtureEnabled;}
        public int batchPauseMillis(){return pauseMs;}
        public long[] visibleIds(int capacity,CancellationSignal cancel){
            reads.incrementAndGet();entered.countDown();
            if(denied)throw new SecurityException();
            if(invalidVolume)throw new PrimaryPhotoSource.UnsupportedVolumeException();try {if(!release.await(8,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}cancel.throwIfCanceled();
            long[] ids=new long[count];for(int i=0;i<count;i++)ids[i]=i+1;return ids;
        }
        public void appendBatch(long[] ids,int from,int to,PrimitiveLocationSet target,CancellationSignal cancel){
            if(unexpected){target.add(Long.MAX_VALUE,1,1);return;}
            for(int i=from;i<to;i++){if((i&255)==0)cancel.throwIfCanceled();target.add(ids[i],(i%179)-89.5,(i%359)-179.5);}
            batches.incrementAndGet();batchProcessed.countDown();
        }
        public boolean scopeUnchanged(long[] ids,CancellationSignal cancel){cancel.throwIfCanceled();return scopeOk;}
        public MediaStoreLookup.Metadata metadata(long id,CancellationSignal cancel){cancel.throwIfCanceled();return new MediaStoreLookup.Metadata(wrongMetadata?id+1:id,Uri.parse("content://fixture/"+id),123L);}
        public Bitmap thumbnail(long id,CancellationSignal cancel){
            cancel.throwIfCanceled();Bitmap value=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888);lastThumbnail=value;
            if(holdThumbnail){thumbnailEntered.countDown();try{if(!thumbnailRelease.await(8,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();}}
            return value;
        }
    }
