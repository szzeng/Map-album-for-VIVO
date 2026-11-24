package org.example.map002probe;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class ReadOnlyQuery {
    private static final String PROVIDER="com.android.gallery3d.dataprovider";
    private static final String VIVO_PERMISSION="com.android.gallery3d.permission.GalleryDataProvider";
    private static final int MAX_SAMPLE=20000;
    static void log(String message){Log.i("MAP002ReadOnly",message);}
    private static final java.util.concurrent.atomic.AtomicBoolean active=new java.util.concurrent.atomic.AtomicBoolean();
    static void runSample(Context context, Intent intent, CancellationSignal cancel) {
        if(!active.compareAndSet(false,true)){log("sample_stopped=already_running");return;}
        try { runExclusive(context,intent,cancel); } finally { active.set(false); }
    }
    private static void runExclusive(Context context, Intent intent, CancellationSignal cancel) {
        long start=SystemClock.elapsedRealtime();
        long cpuStart=android.os.Debug.threadCpuTimeNanos();
        ContentResolver resolver=context.getContentResolver();
        ProviderInfo provider=context.getPackageManager().resolveContentProvider(PROVIDER,0);
        log("vivo_permission_granted="+(context.checkSelfPermission(VIVO_PERMISSION)==PackageManager.PERMISSION_GRANTED));
        log("provider_exported="+(provider!=null && provider.exported));
        boolean access=Build.VERSION.SDK_INT>=33 ? context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)==PackageManager.PERMISSION_GRANTED : context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;
        boolean partial=Build.VERSION.SDK_INT>=34 && context.checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")==PackageManager.PERMISSION_GRANTED;
        log("image_permission_full="+access+" partial="+partial);
        if(!access && !partial){ log("sample_stopped=no_image_permission");return; }
        if (Build.VERSION.SDK_INT<30) { log("sample_stopped=volume_check_unavailable"); return; }
        {
            Set<String> volumes=MediaStore.getExternalVolumeNames(context);
            if(volumes.size()!=1 || !volumes.contains(MediaStore.VOLUME_EXTERNAL_PRIMARY)) {
                log("sample_stopped=multiple_or_unknown_volumes");return;
            }
        }
        int limit=Math.min(MAX_SAMPLE,Math.max(1,intent.getIntExtra("sample_limit",20)));
        Map<Long,Long> selected=new HashMap<>();
        Map<Long,String> mimes=new HashMap<>();
        Map<Long,double[]> positions=new HashMap<>();
        try {
            Uri images=MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            int offset=Math.max(0,intent.getIntExtra("sample_offset",0));
            try(Cursor c=resolver.query(images,new String[]{"_id","datetaken","mime_type"},null,null,"_id DESC",cancel)) {
                if(c==null){log("mediastore=null_cursor");return;}
                int total=c.getCount();log("authorized_primary_total="+total);
                if(offset>total){log("sample_stopped=offset_out_of_range");return;}
                if(offset>0 && !c.moveToPosition(offset-1)){log("sample_stopped=offset_unavailable");return;}
                log("sample_offset="+offset);
                while(selected.size()<limit && c.moveToNext()) { long key=c.getLong(0);selected.put(key,c.isNull(1)?0L:c.getLong(1));mimes.put(key,c.getString(2)); }
            }
            log("authorized_primary_sample="+selected.size());
            if(selected.isEmpty()){ log("sample_stopped=no_visible_images");return; }
            StringBuilder selection=new StringBuilder("_id IN (");String[] args=new String[selected.size()];int i=0;
            for(Long id:selected.keySet()){if(i>0)selection.append(',');selection.append('?');args[i++]=Long.toString(id);}selection.append(')');
            int rows=0, gps=0, empty=0, zero=0, invalid=0, duplicate=0, time=0, exifTime=0, dateMatch=0, mismatch=0, mimeMatch=0, mediaTime=0, exifTimeNonzero=0, exifTimeMatch=0, exifTimeSecondsMatch=0, type1=0, type3=0, typeOther=0;
            Set<Long> seen=new HashSet<>();
            long providerStart=SystemClock.elapsedRealtime();
            if(intent.getBooleanExtra("cancel_before_provider",false)) {cancel.cancel();log("cancel_test=before_provider_query");}
            String[] projection=intent.getBooleanExtra("invalid_projection",false)?new String[]{"_id","map002_nonexistent_column"}:new String[]{"_id","latitude","longitude","media_type","origin_date_taken","origin_date_taken_exif","mime_type"};
            try(Cursor c=resolver.query(Uri.parse("content://"+PROVIDER+"/mediaextra"),projection,selection.toString(),args,null,cancel)) {
                if(c==null){log("provider_sample=null_cursor");return;}
                while(c.moveToNext()) {
                    long id=c.getLong(0);
                    if(!selected.containsKey(id)){ mismatch++; break; }
                    if(!seen.add(id))duplicate++;
                    rows++;
                    if(c.isNull(1)||c.isNull(2))empty++;
                    else {double lat=c.getDouble(1),lon=c.getDouble(2);if(!Double.isFinite(lat)||!Double.isFinite(lon)||lat < -90||lat > 90||lon < -180||lon > 180)invalid++;else if(lat==0 && lon==0)zero++;else {gps++;positions.put(id,new double[]{lat,lon});}}
                    if(!c.isNull(4)&&c.getLong(4)!=0){time++;if(selected.get(id)!=0 && selected.get(id)==c.getLong(4))dateMatch++;}
                    if(selected.get(id)!=0)mediaTime++;
                    if(!c.isNull(5)) {exifTime++;long other=c.getLong(5);if(other!=0)exifTimeNonzero++;if(other!=0 && selected.get(id)!=0 && other==selected.get(id))exifTimeMatch++;if(other!=0 && selected.get(id)!=0 && other==selected.get(id)/1000)exifTimeSecondsMatch++;}
                    if(c.isNull(3))typeOther++;else if(c.getLong(3)==1)type1++;else if(c.getLong(3)==3)type3++;else typeOther++;
                    if(mimes.get(id)!=null && mimes.get(id).equalsIgnoreCase(c.getString(6)))mimeMatch++;
                    if(rows>=limit*2){log("sample_stopped=row_limit");break;}
                }
            }
            log("provider_query_ms="+(SystemClock.elapsedRealtime()-providerStart));
            log("provider_sample_rows="+rows+" matched_unique="+seen.size()+" missing="+(selected.size()-seen.size())+" duplicates="+duplicate+" selection_mismatch="+mismatch);
            log("sample_gps_nonzero_in_range="+gps+" null="+empty+" zero_pair="+zero+" invalid="+invalid);
            if(intent.getBooleanExtra("recheck_missing",false)) {
                Set<Long> missing=new HashSet<>(selected.keySet());missing.removeAll(seen);
                if(missing.size()>0 && missing.size()<=20) {
                    StringBuilder filter=new StringBuilder("_id IN (");String[] missingArgs=new String[missing.size()];int j=0;
                    for(Long id:missing){if(j>0)filter.append(',');filter.append('?');missingArgs[j++]=Long.toString(id);}filter.append(')');
                    int stillVisible=0,providerFound=0,unexpected=0;
                    try(Cursor c=resolver.query(images,new String[]{"_id"},filter.toString(),missingArgs,null,cancel)) {
                        if(c==null)log("missing_recheck_mediastore=null_cursor");
                        else while(c.moveToNext()) {if(missing.contains(c.getLong(0)))stillVisible++;else {unexpected++;break;}}
                    }
                    try(Cursor c=resolver.query(Uri.parse("content://"+PROVIDER+"/mediaextra"),new String[]{"_id"},filter.toString(),missingArgs,null,cancel)) {
                        if(c==null)log("missing_recheck_provider=null_cursor");
                        else while(c.moveToNext()) {if(missing.contains(c.getLong(0)))providerFound++;else {unexpected++;break;}}
                    }
                    log("missing_recheck_count="+missing.size()+" still_visible="+stillVisible+" provider_found="+providerFound+" unexpected="+unexpected);
                } else log("missing_recheck_skipped_count="+missing.size());
            }
            log("sample_mime_matches="+mimeMatch);
            log("sample_provider_taken_nonzero="+time+" provider_exif_time_nonnull="+exifTime+" date_taken_exact_match="+dateMatch);
            log("sample_mediastore_time_nonzero="+mediaTime+" provider_exif_time_nonzero="+exifTimeNonzero+" exact_ms_match="+exifTimeMatch+" seconds_match="+exifTimeSecondsMatch);
            log("sample_provider_media_type_1="+type1+" type_3="+type3+" type_other_or_null="+typeOther);
            if(intent.getBooleanExtra("range_test",false) && !positions.isEmpty()) {
                double[] center=positions.values().iterator().next();
                double south=Math.max(-90,center[0]-0.01),north=Math.min(90,center[0]+0.01),west=Math.max(-180,center[1]-0.01),east=Math.min(180,center[1]+0.01);
                Set<Long> expected=new HashSet<>();
                for(Map.Entry<Long,double[]> item:positions.entrySet()) {double[] p=item.getValue();if(p[0]>=south&&p[0]<=north&&p[1]>=west&&p[1]<=east)expected.add(item.getKey());}
                String[] rangeArgs=new String[args.length+4];System.arraycopy(args,0,rangeArgs,0,args.length);
                rangeArgs[args.length]=Double.toString(south);rangeArgs[args.length+1]=Double.toString(north);rangeArgs[args.length+2]=Double.toString(west);rangeArgs[args.length+3]=Double.toString(east);
                Set<Long> actual=new HashSet<>();boolean sorted=true;long previous=Long.MAX_VALUE;int unexpected=0;
                try(Cursor c=resolver.query(Uri.parse("content://"+PROVIDER+"/mediaextra"),new String[]{"_id"},selection.toString()+" AND latitude>=? AND latitude<=? AND longitude>=? AND longitude<=?",rangeArgs,"_id DESC",cancel)) {
                    if(c==null) log("viewport_query=null_cursor");
                    else {while(c.moveToNext()){long id=c.getLong(0);if(!selected.containsKey(id)){unexpected++;break;}if(id>previous)sorted=false;previous=id;actual.add(id);if(actual.size()>limit)break;}
                        log("viewport_filter_matches_sample="+actual.equals(expected)+" expected_count="+expected.size()+" returned_count="+actual.size()+" sorted_desc="+sorted+" unexpected="+unexpected);}
                }
            }
            if(intent.getBooleanExtra("wrap_test",false) && !positions.isEmpty()) {
                // A wide antimeridian-crossing range: almost the whole globe except a thin strip near zero.
                double west=0.0,east=-0.0001;
                Set<Long> expected=new HashSet<>();
                for(Map.Entry<Long,double[]> item:positions.entrySet()) {double[] p=item.getValue();if(p[1]>=west||p[1]<=east)expected.add(item.getKey());}
                String[] rangeArgs=new String[args.length+2];System.arraycopy(args,0,rangeArgs,0,args.length);
                rangeArgs[args.length]=Double.toString(west);rangeArgs[args.length+1]=Double.toString(east);
                Set<Long> actual=new HashSet<>();int unexpected=0;
                String where=selection.toString()+" AND latitude>=-90 AND latitude<=90 AND longitude>=-180 AND longitude<=180 AND (longitude>=? OR longitude<=?) AND (latitude!=0 OR longitude!=0)";
                try(Cursor c=resolver.query(Uri.parse("content://"+PROVIDER+"/mediaextra"),new String[]{"_id"},where,rangeArgs,null,cancel)) {
                    if(c==null) log("wrap_query=null_cursor");
                    else {while(c.moveToNext()){long id=c.getLong(0);if(!selected.containsKey(id)){unexpected++;break;}actual.add(id);if(actual.size()>limit)break;}
                        log("wrap_filter_matches_sample="+actual.equals(expected)+" expected_count="+expected.size()+" returned_count="+actual.size()+" unexpected="+unexpected);}
                }
            }
            if(intent.getBooleanExtra("thumb_test",false) && !seen.isEmpty()) {
                long id=seen.iterator().next(), t=SystemClock.elapsedRealtime();
                try {Bitmap thumb=resolver.loadThumbnail(ContentUris.withAppendedId(images,id),new Size(128,128),cancel);log("thumbnail_available="+(thumb!=null)+" thumbnail_ms="+(SystemClock.elapsedRealtime()-t));if(thumb!=null)thumb.recycle();}
                catch(Exception e){log("thumbnail_exception="+e.getClass().getSimpleName());}
            }
            log("volume_identity=unverified sample_scope=authorized_primary_only");
        } catch(Exception e) {log("sample_exception="+e.getClass().getSimpleName());}
        finally {log("sample_elapsed_ms="+(SystemClock.elapsedRealtime()-start)+" worker_cpu_ms="+((android.os.Debug.threadCpuTimeNanos()-cpuStart)/1000000));}
    }
}
