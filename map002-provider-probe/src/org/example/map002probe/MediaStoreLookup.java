package org.example.map002probe;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import android.util.Size;
import java.util.Set;
import java.util.Arrays;
import io.github.szzeng.mapalbum.MediaIdentity;

/** Read-only photo metadata helper. This does not establish any vendor GPS identity. */
public final class MediaStoreLookup {
    public static final class Metadata {
        public final long id;public final Uri uri;public final Long dateTaken;public final boolean dateFallback;public final long mediaVersion;public final String relativePath;
        Metadata(long id,Uri uri,Long dateTaken){this(id,uri,dateTaken,false,0,null);}
        Metadata(long id,Uri uri,Long dateTaken,boolean fallback,long version,String path){this.id=id;this.uri=uri;this.dateTaken=dateTaken;this.dateFallback=fallback;this.mediaVersion=version;this.relativePath=path;}
    }
    private final Context context;
    private final ContentResolver resolver;
    MediaStoreLookup(Context context){this.context=context.getApplicationContext();resolver=context.getContentResolver();}
    private boolean primaryOnly(){
        if(Build.VERSION.SDK_INT<29)return false;
        Set<String> volumes=MediaStore.getExternalVolumeNames(context);
        return volumes.size()==1 && volumes.contains(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    }
    Metadata lookup(long id,CancellationSignal cancel) {
        if(cancel==null)throw new IllegalArgumentException();
        cancel.throwIfCanceled();
        if(id<0 || !primaryOnly())return null;
        boolean video=MediaIdentity.isVideo(id);long rawId=MediaIdentity.id(id);
        Uri collection=video?MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY):MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        try(Cursor c=resolver.query(collection,new String[]{"_id","datetaken","date_modified","date_added","relative_path","_display_name"},"_id=?",new String[]{Long.toString(rawId)},null,cancel)) {
            if(c==null || !c.moveToFirst() || c.getLong(0)!=rawId)return null;
            long raw=c.isNull(1)?0:c.getLong(1); boolean fallback=raw<=0;
            long version=c.isNull(2)?0:c.getLong(2);
            if(fallback){long seconds=version>0?version:(c.isNull(3)?0:c.getLong(3));if(seconds>0&&seconds<=Long.MAX_VALUE/1000)raw=seconds*1000;}
            Long date=raw>0?Long.valueOf(raw):null;
            String path=c.isNull(4)||c.isNull(5)?null:c.getString(4)+c.getString(5);
            if(c.moveToNext())return null;
            cancel.throwIfCanceled();
            return new Metadata(id,ContentUris.withAppendedId(collection,rawId),date,fallback,version,path);
        } catch(SecurityException e){return null;}
    }
    /** One bounded MediaStore query; every returned identity must match the requested scope. */
    Metadata[] lookupBatch(long[] ids,CancellationSignal cancel) {
        if(ids==null||ids.length>512||cancel==null)throw new IllegalArgumentException();
        cancel.throwIfCanceled();if(!primaryOnly())throw new PrimaryPhotoSource.UnsupportedVolumeException();
        Metadata[] result=new Metadata[ids.length];if(ids.length==0)return result;
        if(MediaIdentity.isVideo(ids[ids.length-1])){
            for(int i=0;i<ids.length;i++){
                cancel.throwIfCanceled();
                if(!MediaIdentity.isVideo(ids[i])){result[i]=lookup(ids[i],cancel);if(result[i]==null)throw new MapDataRepository.MediaScopeChangedException();continue;}
                try{result[i]=lookup(ids[i],cancel);if(result[i]==null)throw new IllegalStateException("video unavailable");}
                catch(android.os.OperationCanceledException canceled){throw canceled;}
                catch(RuntimeException unavailable){cancel.throwIfCanceled();throw new MapDataRepository.VideoReadException(unavailable);}
            }
            return result;
        }
        StringBuilder where=new StringBuilder("_id IN (");String[] args=new String[ids.length];
        for(int i=0;i<ids.length;i++){if(ids[i]<0||(i>0&&ids[i]<=ids[i-1]))throw new IllegalArgumentException("invalid identities");if(i>0)where.append(',');where.append('?');args[i]=Long.toString(ids[i]);}
        where.append(')');Uri collection=MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        try(Cursor c=resolver.query(collection,new String[]{"_id","datetaken","date_modified","date_added","relative_path","_display_name"},where.toString(),args,null,cancel)) {
            if(c==null)throw new IllegalStateException("null media cursor");
            while(c.moveToNext()){
                cancel.throwIfCanceled();if(c.isNull(0))throw new IllegalStateException("missing identity");long id=c.getLong(0);int at=Arrays.binarySearch(ids,id);
                if(at<0||result[at]!=null)throw new IllegalStateException("unexpected media identity");
                long raw=c.isNull(1)?0:c.getLong(1);boolean fallback=raw<=0;long version=c.isNull(2)?0:c.getLong(2);
                if(fallback){long seconds=version>0?version:(c.isNull(3)?0:c.getLong(3));if(seconds>0&&seconds<=Long.MAX_VALUE/1000)raw=seconds*1000;}
                Long date=raw>0?Long.valueOf(raw):null;String path=c.isNull(4)||c.isNull(5)?null:c.getString(4)+c.getString(5);
                result[at]=new Metadata(id,ContentUris.withAppendedId(collection,id),date,fallback,version,path);
            }
        }
        for(Metadata item:result)if(item==null)throw new MapDataRepository.MediaScopeChangedException();
        cancel.throwIfCanceled();return result;
    }
    void lookupTimelineBatch(long[] ids,int from,int to,long[] dates,long[] versions,CancellationSignal cancel){
        if(to-from>512||from<0||to>ids.length||from>=to)throw new IllegalArgumentException();
        cancel.throwIfCanceled();
        if(!primaryOnly())throw new PrimaryPhotoSource.UnsupportedVolumeException();
        int split=from;while(split<to&&!MediaIdentity.isVideo(ids[split]))split++;
        if(split>from)lookupTimelineType(ids,from,split,dates,versions,cancel,false);
        if(split<to){
            try{lookupTimelineType(ids,split,to,dates,versions,cancel,true);}
            catch(android.os.OperationCanceledException canceled){throw canceled;}
            catch(RuntimeException unavailable){cancel.throwIfCanceled();throw new MapDataRepository.VideoReadException(unavailable);}
        }
    }
    private void lookupTimelineType(long[] ids,int from,int to,long[] dates,long[] versions,CancellationSignal cancel,boolean video){
        StringBuilder where=new StringBuilder("_id IN (");
        String[] args=new String[to-from];
        for(int i=from;i<to;i++){
            if(i>from)where.append(',');
            where.append('?');
            args[i-from]=Long.toString(MediaIdentity.id(ids[i]));
        }
        where.append(')');
        Arrays.fill(dates,from,to,-1);
        Uri collection=video?MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY):MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        try(Cursor cursor=resolver.query(collection,new String[]{"_id","datetaken","date_modified","date_added"},
            where.toString(),args,null,cancel)){
            if(cursor==null)throw new IllegalStateException("null media cursor");
            while(cursor.moveToNext()){
                cancel.throwIfCanceled();
                if(cursor.isNull(0))throw new IllegalStateException("missing identity");
                long raw=cursor.getLong(0);int index=Arrays.binarySearch(ids,from,to,video?MediaIdentity.video(raw):MediaIdentity.photo(raw));
                if(index<0||dates[index]!=-1)throw new IllegalStateException("unexpected media identity");
                long date=cursor.isNull(1)?0:cursor.getLong(1);
                long modified=cursor.isNull(2)?0:cursor.getLong(2);
                long seconds=modified>0?modified:cursor.isNull(3)?0:cursor.getLong(3);
                dates[index]=date>0?date:seconds>0&&seconds<=Long.MAX_VALUE/1000?seconds*1000:0;
                versions[index]=modified;
            }
        }
        for(int i=from;i<to;i++)if(dates[i]<0)throw new MapDataRepository.MediaScopeChangedException();
        cancel.throwIfCanceled();
    }
    Bitmap thumbnail(long id,CancellationSignal cancel) {
        if(cancel==null)throw new IllegalArgumentException();
        cancel.throwIfCanceled();
        Metadata first=lookup(id,cancel);if(first==null)return null;
        Bitmap bitmap=null;
        try {
            bitmap=resolver.loadThumbnail(first.uri,new Size(128,128),cancel);
            cancel.throwIfCanceled();
            if(bitmap==null)return null;
            Metadata after=lookup(id,cancel);
            if(after==null){bitmap.recycle();return null;}
            cancel.throwIfCanceled();
            return bitmap;
        } catch(android.os.OperationCanceledException e){if(bitmap!=null)bitmap.recycle();throw e;}
          catch(Exception e){if(bitmap!=null)bitmap.recycle();return null;}
    }
}
