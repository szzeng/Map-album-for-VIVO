package org.example.map002probe;

import android.content.Context;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.provider.MediaStore;
import java.util.Arrays;
import java.util.Set;

/** Diagnostic only: real authorization checks, not proof of vendor ID identity or cross-provider atomicity. */
final class GuardedLiveProbe {
    private static final Uri PROVIDER=Uri.parse("content://com.android.gallery3d.dataprovider/mediaextra");
    private static void log(String s){ReadOnlyQuery.log("guard_live_"+s);}
    static void load(Context context,GuardedGeoStore guard,GuardedGeoStore.Ticket ticket,int holdMs) {
        if(!ReadOnlyQuery.acquire()){log("stopped=already_running");return;}
        CancellationSignal cancel=ticket.signal;
        try {
            Set<String> volumes=MediaStore.getExternalVolumeNames(context);
            if(volumes.size()!=1 || !volumes.contains(MediaStore.VOLUME_EXTERNAL_PRIMARY)){log("stopped=volume_ambiguous");return;}
            ContentResolver resolver=context.getContentResolver();
            Uri images=MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            long[] selected=new long[500];int n=0,total;
            try(Cursor c=resolver.query(images,new String[]{"_id"},null,null,"_id DESC",cancel)) {
                if(c==null){log("stopped=null_mediastore_cursor");return;}
                total=c.getCount();while(n<selected.length && c.moveToNext()){cancel.throwIfCanceled();selected[n++]=c.getLong(0);}
            }
            selected=Arrays.copyOf(selected,n);Arrays.sort(selected);
            if(n==0){log("stopped=no_visible_images");return;}
            String[] args=new String[n];StringBuilder where=new StringBuilder("_id IN (");
            for(int i=0;i<n;i++){if(i>0)where.append(',');where.append('?');args[i]=Long.toString(selected[i]);}where.append(')');
            boolean[] seen=new boolean[n], valid=new boolean[n];double[] latitude=new double[n],longitude=new double[n];int rows=0,points=0;
            try(Cursor c=resolver.query(PROVIDER,new String[]{"_id","latitude","longitude"},where.toString(),args,null,cancel)) {
                if(c==null){log("stopped=null_provider_cursor");return;}
                while(c.moveToNext()) {
                    cancel.throwIfCanceled();int at=Arrays.binarySearch(selected,c.getLong(0));
                    if(at<0 || seen[at]){log("stopped=unexpected_or_duplicate_id");return;}
                    seen[at]=true;rows++;
                    if(c.isNull(1)||c.isNull(2))continue;
                    double x=c.getDouble(1),y=c.getDouble(2);
                    if(!Double.isFinite(x)||!Double.isFinite(y)||x< -90 || x>90 || y< -180 || y>180 || (x==0 && y==0))continue;
                    valid[at]=true;latitude[at]=x;longitude[at]=y;points++;
                }
            }
            if(holdMs>0) {log("waiting_before_recheck=true");SystemClock.sleep(Math.min(5000,holdMs));}
            cancel.throwIfCanceled();
            boolean[] visible=new boolean[n];int stillVisible=0;
            try(Cursor c=resolver.query(images,new String[]{"_id"},where.toString(),args,null,cancel)) {
                if(c==null){log("stopped=null_recheck_cursor");return;}
                while(c.moveToNext()){
                    cancel.throwIfCanceled();int at=Arrays.binarySearch(selected,c.getLong(0));
                    if(at<0 || visible[at]){log("stopped=unexpected_recheck_id");return;}
                    visible[at]=true;stillVisible++;
                }
            }
            if(stillVisible!=n){log("stopped=scope_changed selected="+n+" still_visible="+stillVisible);return;}
            long[] ids=new long[points];double[] lat=new double[points],lon=new double[points];int next=0;
            for(int i=0;i<n;i++)if(valid[i]){ids[next]=selected[i];lat[next]=latitude[i];lon[next++]=longitude[i];}
            boolean accepted=guard.publish(ticket,ids,lat,lon,true);
            log("publish="+accepted+" visible_total="+total+" selected="+n+" rechecked="+stillVisible+" provider_rows="+rows+" points="+points+" cache_count="+guard.count());
        } catch(android.os.OperationCanceledException e){log("cancelled=true cache_count="+guard.count());}
        catch(Exception e){log("exception="+e.getClass().getSimpleName());}
        finally {ReadOnlyQuery.release();}
    }
}
