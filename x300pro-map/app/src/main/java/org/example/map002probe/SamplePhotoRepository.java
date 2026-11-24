package org.example.map002probe;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import android.util.Size;
import java.util.*;

/** One-shot, explicitly selected primary-volume documents only. Association is unverified. */
public final class SamplePhotoRepository {
    public static final class Sample {
        public final Uri uri; public final double latitude, longitude; public final Long dateTaken; public final Bitmap thumbnail;
        Sample(Uri u,double lat,double lon,Long date,Bitmap thumb){uri=u;latitude=lat;longitude=lon;dateTaken=date;thumbnail=thumb;}
    }
    public static final class Result {
        public final List<Sample> samples; public final int unmapped, withoutGps, unavailable, unsupportedUri, wrongVolume, metadataFailure;
        Result(List<Sample> s,int u,int n,int skipped,int a,int b,int c){samples=s;unmapped=u;withoutGps=n;unavailable=skipped;unsupportedUri=a;wrongVolume=b;metadataFailure=c;}
    }
    private static final class Selected {
        final Uri uri; final long id; final Long date;
        Selected(Uri u,long i,Long d){uri=u;id=i;date=d;}
    }
    private final Context context; private final ContentResolver resolver;
    public SamplePhotoRepository(Context c){context=c.getApplicationContext();resolver=context.getContentResolver();}
    public Result load(List<Uri> selected,CancellationSignal signal) throws Exception {
        if(selected.isEmpty() || selected.size()>20)throw new IllegalArgumentException();
        PrimaryPhotoSource source=new PrimaryPhotoSource(context);
        if(!source.primaryOnly())throw new IllegalStateException("unsupported volume");
        List<Selected> resolved=new ArrayList<>(); Set<Long> seen=new HashSet<>(); int unmapped=0,unsupported=0,wrongVolume=0,metadataFailure=0;
        for(Uri document:selected){
            signal.throwIfCanceled();
            Uri media;
            try { media=MediaStore.getMediaUri(context,document); } catch(Exception e){unmapped++;unsupported++;continue;}
            if(media==null || !"media".equals(media.getAuthority())) {unmapped++;unsupported++;continue;}
            String uriVolume=MediaStore.getVolumeName(media);
            if(!MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(uriVolume) && !MediaStore.VOLUME_EXTERNAL.equals(uriVolume)) {unmapped++;wrongVolume++;continue;}
            List<String> parts=media.getPathSegments();
            if(parts.size()!=4 || !"images".equals(parts.get(1)) || !"media".equals(parts.get(2))){unmapped++;unsupported++;continue;}
            long id;
            try {id=ContentUris.parseId(media);} catch(Exception e){unmapped++;unsupported++;continue;}
            if(id<0 || !seen.add(id)){unmapped++;unsupported++;continue;}
            try(Cursor c=resolver.query(media,new String[]{"_id","datetaken","volume_name"},null,null,null,signal)) {
                if(c==null||!c.moveToFirst()||c.getLong(0)!=id){unmapped++;metadataFailure++;continue;}
                if(c.isNull(2) || !MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(c.getString(2))){unmapped++;wrongVolume++;continue;}
                Long date=c.isNull(1)||c.getLong(1)<=0?null:c.getLong(1);
                if(c.moveToNext()){unmapped++;metadataFailure++;continue;}
                resolved.add(new Selected(media,id,date));
            } catch(SecurityException e){unmapped++;metadataFailure++;}
        }
        Collections.sort(resolved,Comparator.comparingLong(x->x.id));
        long[] ids=new long[resolved.size()]; for(int i=0;i<ids.length;i++)ids[i]=resolved.get(i).id;
        List<Sample> result=new ArrayList<>();
        try(PrimitiveClose data=new PrimitiveClose(ids.length)){
            source.appendBatch(ids,0,ids.length,data.value,signal); data.value.seal();
            int at=0;
            for(Selected item:resolved){
                signal.throwIfCanceled();
                if(at>=data.value.size()||data.value.idAt(at)!=item.id)continue;
                double lat=data.value.latitudeAt(at),lon=data.value.longitudeAt(at);at++;
                try(Cursor verify=resolver.query(item.uri,new String[]{"_id","volume_name"},null,null,null,signal)) {
                    if(verify==null || !verify.moveToFirst() || verify.getLong(0)!=item.id || verify.isNull(1) || !MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(verify.getString(1)) || verify.moveToNext())continue;
                } catch(SecurityException e){continue;}
                try {
                    Bitmap thumb=resolver.loadThumbnail(item.uri,new Size(128,128),signal);
                    if(signal.isCanceled()){if(thumb!=null)thumb.recycle();signal.throwIfCanceled();}
                    if(thumb!=null)result.add(new Sample(item.uri,lat,lon,item.date,thumb));
                } catch(android.os.OperationCanceledException e){throw e;} catch(Exception ignored){}
            }
            signal.throwIfCanceled(); return new Result(result,unmapped,resolved.size()-data.value.size(),data.value.size()-result.size(),unsupported,wrongVolume,metadataFailure);
        } catch(Exception e){for(Sample sample:result)sample.thumbnail.recycle();throw e;}
    }
    private static final class PrimitiveClose implements AutoCloseable {
        final PrimitiveLocationSet value; PrimitiveClose(int n){value=new PrimitiveLocationSet(n);}
        public void close(){value.close();}
    }
}
