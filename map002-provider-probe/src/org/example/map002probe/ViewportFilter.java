package org.example.map002probe;

import android.os.CancellationSignal;
import java.util.Arrays;

/** Pure in-memory filter, not an authorization or media identity check. */
final class ViewportFilter {
    /** Returns total matches; writes up to out.length source indexes in source order. */
    static int query(double[] latitude,double[] longitude,int size,double south,double north,double west,double east,int[] out) {
        return query(latitude,longitude,size,south,north,west,east,out,null);
    }
    static int query(double[] latitude,double[] longitude,int size,double south,double north,double west,double east,int[] out,CancellationSignal cancel) {
        if(out==null)throw new IllegalArgumentException();
        validate(latitude,longitude,size,south,north,west,east);
        if(cancel!=null)cancel.throwIfCanceled();
        int found=0;
        for(int i=0;i<size;i++) {
            if(cancel!=null && (i&255)==0)cancel.throwIfCanceled();
            if(matches(latitude[i],longitude[i],south,north,west,east)){if(found<out.length)out[found]=i;found++;}
        }
        if(cancel!=null)cancel.throwIfCanceled();
        return found;
    }
    /** One scan and storage proportional to matches, never silently truncating results. */
    static int[] queryIndexes(double[] latitude,double[] longitude,int size,double south,double north,double west,double east,CancellationSignal cancel){
        validate(latitude,longitude,size,south,north,west,east);
        if(cancel==null)throw new IllegalArgumentException();cancel.throwIfCanceled();
        int[] indexes=new int[Math.min(size,64)];int found=0;
        for(int i=0;i<size;i++){
            if((i&255)==0)cancel.throwIfCanceled();
            if(matches(latitude[i],longitude[i],south,north,west,east)){
                if(found==indexes.length){int next=(int)Math.min((long)size,Math.max(1L,(long)indexes.length*2));indexes=Arrays.copyOf(indexes,next);}
                indexes[found++]=i;
            }
        }
        cancel.throwIfCanceled();return found==indexes.length?indexes:Arrays.copyOf(indexes,found);
    }
    private static void validate(double[] latitude,double[] longitude,int size,double south,double north,double west,double east){
        if(latitude==null||longitude==null||size<0||size>latitude.length||size>longitude.length ||
            !Double.isFinite(south)||!Double.isFinite(north)||!Double.isFinite(west)||!Double.isFinite(east)||
            south < -90 || north > 90 || south>north || west < -180 || west > 180 || east < -180 || east > 180)
            throw new IllegalArgumentException();
    }
    private static boolean matches(double x,double y,double south,double north,double west,double east){
        if(!Double.isFinite(x)||!Double.isFinite(y)||x<south||x>north||y< -180||y>180)return false;
        return west<=east?(y>=west&&y<=east)||(y==-180&&east==180)||(y==180&&west==-180):y>=west||y<=east;
    }
}
