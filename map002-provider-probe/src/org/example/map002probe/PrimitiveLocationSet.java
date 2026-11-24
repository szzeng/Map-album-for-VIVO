package org.example.map002probe;

import android.os.CancellationSignal;
import java.util.Arrays;

/** Process-local primitive storage; authorization and identity are enforced by its owner. */
final class PrimitiveLocationSet {
    static final int MAX_POINTS=100000;
    private long[] ids;
    private double[] lat,lon;
    private int size;
    private boolean sealed,closed;
    PrimitiveLocationSet(int capacity) {
        if(capacity<0 || capacity>MAX_POINTS)throw new IllegalArgumentException("capacity");
        ids=new long[capacity];lat=new double[capacity];lon=new double[capacity];
    }
    void add(long id,double latitude,double longitude) {
        if(closed||sealed)throw new IllegalStateException("not writable");
        if(size==ids.length)throw new IllegalStateException("capacity exceeded");
        if(id<0 || (size>0 && id<=ids[size-1]) || !Double.isFinite(latitude)||!Double.isFinite(longitude)||
           latitude< -90||latitude>90||longitude< -180||longitude>180||(latitude==0 && longitude==0))
            throw new IllegalArgumentException("invalid point");
        ids[size]=id;lat[size]=latitude;lon[size]=longitude;size++;
    }
    void seal(){if(closed)throw new IllegalStateException("closed");sealed=true;}
    int size(){return closed?0:size;}
    private void checkIndex(int index){if(closed||!sealed||index<0||index>=size)throw new IllegalStateException("unavailable");}
    long idAt(int index){checkIndex(index);return ids[index];}
    double latitudeAt(int index){checkIndex(index);return lat[index];}
    double longitudeAt(int index){checkIndex(index);return lon[index];}
    boolean contains(long id){return !closed && sealed && Arrays.binarySearch(ids,0,size,id)>=0;}
    int query(double south,double north,double west,double east,int[] result,CancellationSignal cancel) {
        if(closed||!sealed)throw new IllegalStateException("unavailable");
        if(cancel==null)throw new IllegalArgumentException("cancel");
        return ViewportFilter.query(lat,lon,size,south,north,west,east,result,cancel);
    }
    void close() {
        if(closed)return;
        Arrays.fill(ids,0);Arrays.fill(lat,0);Arrays.fill(lon,0);
        ids=null;lat=null;lon=null;size=0;closed=true;sealed=false;
    }
}
