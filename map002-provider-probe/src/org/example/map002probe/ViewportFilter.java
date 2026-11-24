package org.example.map002probe;

import android.os.CancellationSignal;

/** Pure in-memory filter, not an authorization or media identity check. */
final class ViewportFilter {
    /** Returns total matches; writes up to out.length source indexes in source order. */
    static int query(double[] latitude,double[] longitude,int size,double south,double north,double west,double east,int[] out) {
        return query(latitude,longitude,size,south,north,west,east,out,null);
    }
    static int query(double[] latitude,double[] longitude,int size,double south,double north,double west,double east,int[] out,CancellationSignal cancel) {
        if(latitude==null||longitude==null||out==null||size<0||size>latitude.length||size>longitude.length ||
            !Double.isFinite(south)||!Double.isFinite(north)||!Double.isFinite(west)||!Double.isFinite(east)||
            south < -90 || north > 90 || south>north || west < -180 || west > 180 || east < -180 || east > 180)
            throw new IllegalArgumentException();
        if(cancel!=null)cancel.throwIfCanceled();
        int found=0;
        for(int i=0;i<size;i++) {
            if(cancel!=null && (i&255)==0)cancel.throwIfCanceled();
            double x=latitude[i],y=longitude[i];
            if(!Double.isFinite(x)||!Double.isFinite(y)||x<south||x>north||y< -180||y>180)continue;
            boolean inLongitude;
            if(west<=east) inLongitude=(y>=west && y<=east) || (y==-180 && east==180) || (y==180 && west==-180);
            else inLongitude=y>=west || y<=east;
            if(inLongitude){if(found<out.length)out[found]=i;found++;}
        }
        if(cancel!=null)cancel.throwIfCanceled();
        return found;
    }
}
