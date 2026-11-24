package org.example.map002probe;

import android.os.SystemClock;
import java.util.Arrays;

/** Runs fixture and real-array comparisons; writes no identifiers or coordinates to logs. */
final class ViewportFilterProbe {
    private static void check(boolean v){if(!v)throw new AssertionError("viewport check failed");}
    private static void expect(int[] actual,int n,int... expected){check(n==expected.length);for(int i=0;i<n;i++)check(actual[i]==expected[i]);}
    private static int reference(double[] lat,double[] lon,int n,double s,double north,double w,double e,int[] out){
        int k=0;
        for(int i=0;i<n;i++) {
            double x=lat[i],y=lon[i];boolean longitude;
            if(w>e)longitude=y>=w||y<=e;
            else {longitude=y>=w&&y<=e;if(!longitude && Math.abs(y)==180)longitude=(y<0 && e==180)||(y>0 && w== -180);}
            if(Double.isFinite(x)&&Double.isFinite(y)&&x>=s&&x<=north&&y>=-180&&y<=180&&longitude)out[k++]=i;
        }
        return k;
    }
    static void run(double[] lat,double[] lon,int count) {
        double[] a={0,0,0,0,0,0,10,-10},b={180,-180,179.9,-179.9,0,179.0,180,-180};int[] out=new int[8];
        expect(out,ViewportFilter.query(a,b,8,-1,1,179,-179,out),0,1,2,3,5);
        expect(out,ViewportFilter.query(a,b,8,-1,1,170,180,out),0,1,2,5);
        expect(out,ViewportFilter.query(a,b,8,-1,1,-180,-170,out),0,1,3);
        expect(out,ViewportFilter.query(a,b,8,-1,1,0,0,out),4);
        int[] truncated=new int[2];check(ViewportFilter.query(a,b,8,-1,1,179,-179,truncated)==5 && truncated[0]==0 && truncated[1]==1);
        boolean invalid=false;try{ViewportFilter.query(a,b,8,1,-1,0,1,out);}catch(IllegalArgumentException e){invalid=true;}check(invalid);
        invalid=false;try{ViewportFilter.query(a,b,8,0,1,Double.NaN,1,out);}catch(IllegalArgumentException e){invalid=true;}check(invalid);
        if(count==0){ReadOnlyQuery.log("viewport_array_fixture=true real_points=0");return;}
        double south=Math.max(-90,lat[0]-0.01),north=Math.min(90,lat[0]+0.01),west=Math.max(-180,lon[0]-0.01),east=Math.min(180,lon[0]+0.01);
        int[] got=new int[count],expected=new int[count],repeat=new int[count],larger=new int[count];
        long start=SystemClock.elapsedRealtimeNanos();int n=ViewportFilter.query(lat,lon,count,south,north,west,east,got);long micros=(SystemClock.elapsedRealtimeNanos()-start)/1000;
        int ref=reference(lat,lon,count,south,north,west,east,expected);check(n==ref && Arrays.equals(Arrays.copyOf(got,n),Arrays.copyOf(expected,ref)));
        // Temporary quadrant counts demonstrate that only filtered points feed aggregation.
        int[] buckets=new int[4],referenceBuckets=new int[4];
        double midLat=(south+north)/2,midLon=(west+east)/2;
        for(int i=0;i<n;i++){int at=got[i];buckets[(lat[at]>=midLat?2:0)+(lon[at]>=midLon?1:0)]++;}
        for(int i=0;i<ref;i++){int at=expected[i];referenceBuckets[(lat[at]>=midLat?2:0)+(lon[at]>=midLon?1:0)]++;}
        check(Arrays.equals(buckets,referenceBuckets) && buckets[0]+buckets[1]+buckets[2]+buckets[3]==n);
        int again=ViewportFilter.query(lat,lon,count,south,north,west,east,repeat);check(again==n && Arrays.equals(Arrays.copyOf(got,n),Arrays.copyOf(repeat,again)));
        int wide=ViewportFilter.query(lat,lon,count,Math.max(-90,south-.01),Math.min(90,north+.01),Math.max(-180,west-.01),Math.min(180,east+.01),larger);
        int j=0;for(int i=0;i<n;i++){while(j<wide&&larger[j]<got[i])j++;check(j<wide&&larger[j]==got[i]);}
        int wrap=ViewportFilter.query(lat,lon,count,-90,90,179,-179,got);int wrapRef=reference(lat,lon,count,-90,90,179,-179,expected);
        check(wrap==wrapRef && Arrays.equals(Arrays.copyOf(got,wrap),Arrays.copyOf(expected,wrapRef)));
        int world=ViewportFilter.query(lat,lon,count,-90,90,-180,180,got);check(world==count);
        ReadOnlyQuery.log("viewport_array_fixture=true exact_reference=true repeated_exact=true overlap_contains=true aggregation_input_verified=true synthetic_seam=true real_points="+count+" matches="+n+" wider="+wide+" real_wrap="+wrap+" scan_us="+micros);
    }
}
