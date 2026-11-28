package org.example.map002probe;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import java.util.Arrays;
import java.util.Set;

/** Read-only primary-volume adapter. It does not establish vivo/MediaStore identity. */
final class PrimaryPhotoSource {
    static final class UnsupportedVolumeException extends IllegalStateException { }
    static final class ProviderException extends IllegalStateException { ProviderException(Throwable cause){super(cause);} }
    static final class CapacityException extends IllegalStateException { }
    private static final Uri VIVO=Uri.parse("content://com.android.gallery3d.dataprovider/mediaextra");
    int missingCount, noGpsCount, invalidCount;
    void resetCounts(){missingCount=noGpsCount=invalidCount=0;}
    private final Context context;
    private final ContentResolver resolver;
    PrimaryPhotoSource(Context context){this.context=context.getApplicationContext();this.resolver=context.getContentResolver();}
    static boolean acceptsVolumes(Set<String> volumes){
        return volumes!=null && volumes.size()==1 && volumes.contains(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    }
    boolean primaryOnly(){return Build.VERSION.SDK_INT>=29 && acceptsVolumes(MediaStore.getExternalVolumeNames(context));}
    long[] visibleIds(int capacity,CancellationSignal signal) {
        if(signal==null || capacity<0 || capacity>PrimitiveLocationSet.MAX_POINTS)throw new IllegalArgumentException();
        signal.throwIfCanceled();
        if(!primaryOnly())throw new UnsupportedVolumeException();
        Uri images=MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        long[] ids=new long[capacity];int count=0;
        try(Cursor c=resolver.query(images,new String[]{"_id"},null,null,"_id ASC",signal)) {
            if(c==null)throw new IllegalStateException("null media cursor");
            if(c.getCount()>capacity)throw new CapacityException();
            while(c.moveToNext()){
                signal.throwIfCanceled();if(count==capacity)throw new CapacityException();
                long id=c.getLong(0);if(id<0 || (count>0 && id<=ids[count-1]))throw new IllegalStateException("unexpected media identity");
                ids[count++]=id;
            }
        }
        signal.throwIfCanceled();return Arrays.copyOf(ids,count);
    }
    private Cursor queryLocations(String where,String[] args,CancellationSignal signal) {
        // Some gallery providers return null while starting. Retry only that case,
        // before processing any rows, and remain responsive to cancellation.
        for(int attempt=0;attempt<3;attempt++){
            signal.throwIfCanceled();
            Cursor cursor=resolver.query(VIVO,new String[]{"_id","latitude","longitude"},where,args,null,signal);
            if(cursor!=null)return cursor;
            if(attempt<2){
                long until=android.os.SystemClock.elapsedRealtime()+(attempt==0?500:1000);
                while(true){
                    signal.throwIfCanceled();
                    long remaining=until-android.os.SystemClock.elapsedRealtime();
                    if(remaining<=0)break;
                    android.os.SystemClock.sleep(Math.min(100,remaining));
                }
            }
        }
        return null;
    }
    void appendBatch(long[] visible,int from,int to,PrimitiveLocationSet output,CancellationSignal signal) {
        if(visible==null||output==null||signal==null||from<0||to<from||to>visible.length||to-from>20000)throw new IllegalArgumentException();
        signal.throwIfCanceled();if(!primaryOnly())throw new UnsupportedVolumeException();
        int n=to-from;if(n==0)return;
        StringBuilder where=new StringBuilder("_id IN (");String[] args=new String[n];
        for(int i=0;i<n;i++){
            long id=visible[from+i];if(id<0 || (i>0 && id<=visible[from+i-1]))throw new IllegalArgumentException("unsorted identities");
            if(i>0)where.append(',');where.append('?');args[i]=Long.toString(id);
        }
        where.append(')');boolean[] seen=new boolean[n],valid=new boolean[n];double[] lat=new double[n],lon=new double[n];
        try(Cursor c=queryLocations(where.toString(),args,signal)) {
            if(c==null)throw new IllegalStateException("null provider cursor");
            while(c.moveToNext()){
                signal.throwIfCanceled();long id=c.getLong(0);int pos=Arrays.binarySearch(visible,from,to,id);
                if(pos<0 || seen[pos-from])throw new IllegalStateException("unexpected or duplicate provider id");
                int i=pos-from;seen[i]=true;
                if(c.isNull(1)||c.isNull(2)){noGpsCount++;continue;}
                double x=c.getDouble(1),y=c.getDouble(2);
                if(!Double.isFinite(x)||!Double.isFinite(y)||x< -90||x>90||y< -180||y>180||(x==0 && y==0)){invalidCount++;continue;}
                valid[i]=true;lat[i]=x;lon[i]=y;
            }
        }
        catch(android.os.OperationCanceledException canceled){throw canceled;}
        catch(RuntimeException incompatible){throw new ProviderException(incompatible);}
        signal.throwIfCanceled();
        for(boolean present:seen)if(!present)missingCount++;
        for(int i=0;i<n;i++)if(valid[i]){signal.throwIfCanceled();output.add(visible[from+i],lat[i],lon[i]);}
    }
}
