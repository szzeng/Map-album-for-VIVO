package org.example.map002probe;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Debug;
import android.os.SystemClock;
import android.provider.MediaStore;
import java.util.Arrays;
import java.util.Set;

/** One-shot, read-only measurement; arrays exist only until this method returns. */
final class LoadBenchmark {
    private static final Uri PROVIDER=Uri.parse("content://com.android.gallery3d.dataprovider/mediaextra");
    private static final int CAPACITY=100000, BATCH=20000;
    private static void log(String s){ReadOnlyQuery.log("load_bench_"+s);}
    private static int pss(){Debug.MemoryInfo m=new Debug.MemoryInfo();Debug.getMemoryInfo(m);return m.getTotalPss();}
    private static boolean within(double x,double y,double south,double north,double west,double east) {
        return x>=south && x<=north && (west<=east ? y>=west && y<=east : y>=west || y<=east);
    }
    private static int scan(double[] lat,double[] lon,int count,double south,double north,double west,double east) {
        int result=0;for(int i=0;i<count;i++)if(within(lat[i],lon[i],south,north,west,east))result++;return result;
    }
    private static void rangeSuite(double[] lat,double[] lon,int count) {
        double[] sx={0,0,0,0,0,0,10};
        double[] sy={180,-180,179.9,-179.9,0,179.0,180};
        boolean boundary=scan(sx,sy,7,-1,1,179,-179)==5 && scan(sx,sy,7,-1,1,-1,1)==1;
        if(count==0){log("range_suite=empty synthetic_boundary="+boundary);return;}
        double south=Math.max(-90,lat[0]-0.01),north=Math.min(90,lat[0]+0.01);
        double west=Math.max(-180,lon[0]-0.01),east=Math.min(180,lon[0]+0.01);
        long start=SystemClock.elapsedRealtimeNanos();int near=scan(lat,lon,count,south,north,west,east);
        long nearUs=(SystemClock.elapsedRealtimeNanos()-start)/1000;
        start=SystemClock.elapsedRealtimeNanos();int repeat=scan(lat,lon,count,south,north,west,east);
        long repeatUs=(SystemClock.elapsedRealtimeNanos()-start)/1000;
        int overlap=scan(lat,lon,count,Math.max(-90,south-0.01),Math.min(90,north+0.01),Math.max(-180,west-0.01),Math.min(180,east+0.01));
        int wrapped=scan(lat,lon,count,-90,90,179,-179);
        int world=scan(lat,lon,count,-90,90,-180,180);
        log("range_suite="+(boundary && near==repeat && overlap>=near && world==count ? "pass":"fail")+" synthetic_boundary="+boundary+" real_points="+count+" near_count="+near+" repeat_count="+repeat+" overlap_count="+overlap+" wrap_count="+wrapped+" world_count="+world+" near_us="+nearUs+" repeat_us="+repeatUs);
    }
    static void run(Context context,Intent intent,CancellationSignal cancel) {
        if(Build.VERSION.SDK_INT<30){log("stopped=unsupported_api");return;}
        boolean full=context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)==PackageManager.PERMISSION_GRANTED;
        boolean partial=Build.VERSION.SDK_INT>=34 && context.checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")==PackageManager.PERMISSION_GRANTED;
        if(!full && !partial){log("stopped=no_image_permission");return;}
        Set<String> volumes=MediaStore.getExternalVolumeNames(context);
        if(volumes.size()!=1 || !volumes.contains(MediaStore.VOLUME_EXTERNAL_PRIMARY)){log("stopped=volume_ambiguous");return;}
        final int request=Math.min(CAPACITY,Math.max(1,intent.getIntExtra("load_limit",200)));
        final int pssBefore=pss(); long wall=SystemClock.elapsedRealtime(), cpu=Debug.threadCpuTimeNanos();
        ContentResolver resolver=context.getContentResolver();
        long[] visible=new long[request], ids=new long[request];
        double[] lat=new double[request],lon=new double[request];
        int selected=0,stored=0,batches=0,rows=0,missing=0,empty=0,zero=0,invalid=0,peak=pssBefore;
        long providerMs=0;
        try {
            Uri images=MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            int available;
            try(Cursor c=resolver.query(images,new String[]{"_id"},null,null,"_id DESC",cancel)) {
                if(c==null){log("stopped=null_mediastore_cursor");return;}
                available=c.getCount();
                if(intent.getBooleanExtra("require_complete",false) && available>request){log("stopped=capacity");return;}
                long previous=Long.MAX_VALUE;
                while(selected<request && c.moveToNext()) {
                    cancel.throwIfCanceled();long id=c.getLong(0);
                    if(selected>0 && id>=previous){log("stopped=unexpected_media_order");return;}
                    previous=id;visible[selected++]=id;
                }
            }
            log("visible_total="+available+" selected="+selected+" array_capacity="+request);
            for(int base=0;base<selected;base+=BATCH) {
                cancel.throwIfCanceled();int n=Math.min(BATCH,selected-base);
                long[] allowed=Arrays.copyOfRange(visible,base,base+n);Arrays.sort(allowed);
                boolean[] seen=new boolean[n];String[] args=new String[n];StringBuilder where=new StringBuilder("_id IN (");
                for(int j=0;j<n;j++){if(j>0)where.append(',');where.append('?');args[j]=Long.toString(allowed[j]);}where.append(')');
                long start=SystemClock.elapsedRealtime();int batchRows=0;
                try(Cursor c=resolver.query(PROVIDER,new String[]{"_id","latitude","longitude"},where.toString(),args,null,cancel)) {
                    if(c==null){log("stopped=null_provider_cursor");return;}
                    while(c.moveToNext()) {
                        cancel.throwIfCanceled(); long id=c.getLong(0);int index=Arrays.binarySearch(allowed,id);
                        if(index<0 || seen[index]){log("stopped=unexpected_or_duplicate_id");return;}
                        seen[index]=true;batchRows++;
                        if(c.isNull(1)||c.isNull(2)){empty++;continue;}
                        double x=c.getDouble(1), y=c.getDouble(2);
                        if(!Double.isFinite(x)||!Double.isFinite(y)||x < -90||x > 90||y < -180||y > 180){invalid++;continue;}
                        if(x==0 && y==0){zero++;continue;}
                        ids[stored]=id;lat[stored]=x;lon[stored]=y;stored++;
                    }
                }
                long elapsed=SystemClock.elapsedRealtime()-start;providerMs+=elapsed;rows+=batchRows;missing+=n-batchRows;batches++;
                peak=Math.max(peak,pss());
                log("batch="+batches+" requested="+n+" returned="+batchRows+" provider_ms="+elapsed+" sampled_pss_kib="+peak);
                if(base+n<selected)SystemClock.sleep(5000);
            }
            long loadWall=SystemClock.elapsedRealtime()-wall, cpuMs=(Debug.threadCpuTimeNanos()-cpu)/1000000;
            log("result=complete total="+available+" selected="+selected+" rows="+rows+" stored="+stored+" missing="+missing+" null="+empty+" zero="+zero+" invalid="+invalid+" provider_calls="+batches+" load_ms="+loadWall+" provider_ms="+providerMs+" worker_cpu_ms="+cpuMs+" pss_before_kib="+pssBefore+" sampled_max_pss_kib="+peak+" array_payload_bytes="+(32L*request));
            if(intent.getBooleanExtra("range_suite",false)) {rangeSuite(lat,lon,stored);ViewportFilterProbe.run(lat,lon,stored);}
            // Compare one bounded Provider viewport with the in-memory result, only for a single batch.
            if(intent.getBooleanExtra("compare_viewport",false) && selected<=BATCH && stored>0) {
                double south=Math.max(-90,lat[0]-0.01),north=Math.min(90,lat[0]+0.01);
                double west=Math.max(-180,lon[0]-0.01),east=Math.min(180,lon[0]+0.01);
                long scanStart=SystemClock.elapsedRealtimeNanos();long[] expected=new long[stored];int count=0;
                for(int i=0;i<stored;i++)if(lat[i]>=south&&lat[i]<=north&&lon[i]>=west&&lon[i]<=east)expected[count++]=ids[i];
                long scanUs=(SystemClock.elapsedRealtimeNanos()-scanStart)/1000;Arrays.sort(expected,0,count);
                StringBuilder where=new StringBuilder("_id IN (");String[] args=new String[selected+4];
                for(int i=0;i<selected;i++){if(i>0)where.append(',');where.append('?');args[i]=Long.toString(visible[i]);}
                where.append(") AND latitude>=? AND latitude<=? AND longitude>=? AND longitude<=? AND (latitude!=0 OR longitude!=0)");
                args[selected]=Double.toString(south);args[selected+1]=Double.toString(north);args[selected+2]=Double.toString(west);args[selected+3]=Double.toString(east);
                int found=0;boolean match=true;boolean[] matched=new boolean[count];long start=SystemClock.elapsedRealtime();
                try(Cursor c=resolver.query(PROVIDER,new String[]{"_id"},where.toString(),args,null,cancel)) {
                    if(c==null){log("viewport=null_cursor");return;}
                    while(c.moveToNext()){cancel.throwIfCanceled();int at=Arrays.binarySearch(expected,0,count,c.getLong(0));if(at<0||matched[at]){match=false;break;}matched[at]=true;found++;}
                }
                log("viewport_match="+(match && count==found)+" results="+found+" memory_scan_us="+scanUs+" provider_query_ms="+(SystemClock.elapsedRealtime()-start)+" provider_calls=1");
            }
        } catch(Exception e){log("exception="+e.getClass().getSimpleName());}
        finally {log("released=method_exit");}
    }
}
