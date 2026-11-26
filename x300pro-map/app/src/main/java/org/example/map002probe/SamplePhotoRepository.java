package org.example.map002probe;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import java.util.*;

/** One-shot, explicitly selected primary-volume documents only. Association is unverified. */
public final class SamplePhotoRepository {
    public static final class Sample {
        public final Uri uri, documentUri; public final double latitude, longitude; public final Long dateTaken; public final boolean dateFallback; public final long mediaVersion; public final String relativePath;
        Sample(Uri u,Uri document,double lat,double lon,Long date,boolean fallback,long version,String path){uri=u;documentUri=document;latitude=lat;longitude=lon;dateTaken=date;dateFallback=fallback;mediaVersion=version;relativePath=path;}
    }
    public static final class Result {
        public final List<Sample> samples; public final int unmapped, withoutGps, unavailable, unsupportedUri, wrongVolume, metadataFailure;
        Result(List<Sample> s,int u,int n,int skipped,int a,int b,int c){samples=s;unmapped=u;withoutGps=n;unavailable=skipped;unsupportedUri=a;wrongVolume=b;metadataFailure=c;}
    }
    private static final class Selected {
        final Uri uri, documentUri; final long id; final Long date; final boolean dateFallback; final long mediaVersion; final String relativePath;
        Selected(Uri u,Uri document,long i,Long d,boolean fallback,long version,String path){uri=u;documentUri=document;id=i;date=d;dateFallback=fallback;mediaVersion=version;relativePath=path;}
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
            try(Cursor c=resolver.query(media,new String[]{"_id","datetaken","volume_name","date_modified","date_added","relative_path","_display_name"},null,null,null,signal)) {
                if(c==null||!c.moveToFirst()||c.getLong(0)!=id){unmapped++;metadataFailure++;continue;}
                if(c.isNull(2) || !MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(c.getString(2))){unmapped++;wrongVolume++;continue;}
                long dateValue=c.isNull(1)?0:c.getLong(1);
                long mediaVersion=c.isNull(3)?0:c.getLong(3);
                String folder=c.isNull(5)?null:c.getString(5),name=c.isNull(6)?null:c.getString(6);
                String relativePath=folder==null||name==null?null:folder+name;
                boolean fallback=dateValue<=0;
                if(fallback){long seconds=c.isNull(3)?0:c.getLong(3);if(seconds<=0)seconds=c.isNull(4)?0:c.getLong(4);if(seconds>0&&seconds<=Long.MAX_VALUE/1000)dateValue=seconds*1000;}
                Long date=dateValue>0?dateValue:null;
                if(c.moveToNext()){unmapped++;metadataFailure++;continue;}
                resolved.add(new Selected(media,document,id,date,fallback,mediaVersion,relativePath));
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
                result.add(new Sample(item.uri,item.documentUri,lat,lon,item.date,item.dateFallback,item.mediaVersion,item.relativePath));
            }
            signal.throwIfCanceled(); return new Result(result,unmapped,resolved.size()-data.value.size(),data.value.size()-result.size(),unsupported,wrongVolume,metadataFailure);
        }
    }
    private static final class PrimitiveClose implements AutoCloseable {
        final PrimitiveLocationSet value; PrimitiveClose(int n){value=new PrimitiveLocationSet(n);}
        public void close(){value.close();}
    }
}
