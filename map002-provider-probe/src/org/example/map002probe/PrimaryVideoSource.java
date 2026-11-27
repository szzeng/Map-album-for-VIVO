package org.example.map002probe;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import io.github.szzeng.mapalbum.MediaIdentity;
import io.github.szzeng.mapalbum.VideoIdentity;
import java.util.Arrays;

/** Read-only main-volume video source. Unmatched identities never enter the location index. */
final class PrimaryVideoSource {
    private static final Uri VIVO=Uri.parse("content://com.android.gallery3d.dataprovider/mediaextra");
    private final ContentResolver resolver;
    private final PrimaryPhotoSource volume;
    PrimaryVideoSource(Context context) {
        resolver=context.getApplicationContext().getContentResolver();
        volume=new PrimaryPhotoSource(context);
    }
    long[] visibleIds(int capacity,CancellationSignal signal) {
        if(capacity<0 || signal==null)throw new IllegalArgumentException();
        if(!volume.primaryOnly())throw new PrimaryPhotoSource.UnsupportedVolumeException();
        long[] ids=new long[capacity];int n=0;
        Uri collection=MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        try(Cursor cursor=resolver.query(collection,new String[]{"_id"},null,null,"_id ASC",signal)){
            if(cursor==null)throw new IllegalStateException("null video cursor");
            if(cursor.getCount()>capacity)throw new PrimaryPhotoSource.CapacityException();
            while(cursor.moveToNext()){
                signal.throwIfCanceled();
                if(n==capacity)throw new PrimaryPhotoSource.CapacityException();
                long key=MediaIdentity.video(cursor.getLong(0));
                if(n>0&&key<=ids[n-1])throw new IllegalStateException("unexpected video identity");
                ids[n++]=key;
            }
        }
        return Arrays.copyOf(ids,n);
    }
    void appendBatch(long[] ids,int from,int to,PrimitiveLocationSet output,CancellationSignal signal){
        if(to-from>512 || from<0 || to>ids.length || to<from)throw new IllegalArgumentException();
        if(!volume.primaryOnly())throw new PrimaryPhotoSource.UnsupportedVolumeException();
        int n=to-from;if(n==0)return;
        StringBuilder selection=new StringBuilder("_id IN (");String[] args=new String[n];long[] raw=new long[n];
        for(int i=0;i<n;i++){
            if(!MediaIdentity.isVideo(ids[from+i]))throw new IllegalArgumentException();
            raw[i]=MediaIdentity.id(ids[from+i]);
            if(i>0){if(raw[i]<=raw[i-1])throw new IllegalArgumentException();selection.append(',');}
            selection.append('?');args[i]=Long.toString(raw[i]);
        }
        selection.append(')');String[] paths=new String[n];long[] versions=new long[n];boolean[] present=new boolean[n];
        Uri collection=MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        try(Cursor cursor=resolver.query(collection,new String[]{"_id","_data","date_modified"},selection.toString(),args,null,signal)){
            if(cursor==null)throw new IllegalStateException("null video cursor");
            while(cursor.moveToNext()){
                signal.throwIfCanceled();int at=Arrays.binarySearch(raw,cursor.getLong(0));
                if(at<0||present[at])throw new IllegalStateException("unexpected video row");
                present[at]=true;paths[at]=cursor.getString(1);versions[at]=cursor.isNull(2)?0:cursor.getLong(2);
            }
        }
        for(boolean found:present)if(!found)throw new MapDataRepository.MediaScopeChangedException();
        boolean[] seen=new boolean[n],valid=new boolean[n];double[] lat=new double[n],lon=new double[n];
        try(Cursor cursor=resolver.query(VIVO,new String[]{"_id","latitude","longitude","media_type","_data"},selection.toString(),args,null,signal)){
            if(cursor==null)throw new IllegalStateException("null provider cursor");
            while(cursor.moveToNext()){
                signal.throwIfCanceled();int at=Arrays.binarySearch(raw,cursor.getLong(0));
                if(at<0||seen[at])throw new IllegalStateException("unexpected provider row");
                seen[at]=true;
                String path=cursor.getString(4);
                if(!VideoIdentity.matches(cursor.isNull(3)?null:cursor.getInt(3),paths[at],path)||cursor.isNull(1)||cursor.isNull(2))continue;
                double x=cursor.getDouble(1),y=cursor.getDouble(2);
                if(!Double.isFinite(x)||!Double.isFinite(y)||x< -90||x>90||y< -180||y>180||(x==0&&y==0))continue;
                valid[at]=true;lat[at]=x;lon[at]=y;
            }
        }catch(android.os.OperationCanceledException canceled){throw canceled;}
         catch(RuntimeException incompatible){throw new PrimaryPhotoSource.ProviderException(incompatible);}
        for(int i=0;i<n;i++)if(valid[i]){signal.throwIfCanceled();output.add(ids[from+i],lat[i],lon[i],versions[i]);}
    }
}
