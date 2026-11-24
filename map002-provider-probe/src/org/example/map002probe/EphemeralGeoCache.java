package org.example.map002probe;

import java.util.LinkedHashMap;
import java.util.Map;

/** Volatile prototype only: caller must validate both current visibility and media identity. */
final class EphemeralGeoCache {
    interface Validator { boolean currentlyAuthorizedAndIdentityVerified(String volume, long mediaId); }
    static final class Key {
        final String volume; final long id;
        Key(String volume,long id) { if(volume==null)throw new IllegalArgumentException();this.volume=volume;this.id=id; }
        @Override public boolean equals(Object o){if(!(o instanceof Key))return false;Key k=(Key)o;return id==k.id && volume.equals(k.volume);}
        @Override public int hashCode(){return 31*volume.hashCode()+(int)(id^(id>>>32));}
    }
    static final class Point { final double latitude, longitude; Point(double latitude,double longitude){this.latitude=latitude;this.longitude=longitude;} }
    private final int capacity;
    private final LinkedHashMap<Key,Point> values=new LinkedHashMap<>(16,0.75f,true);
    EphemeralGeoCache(int capacity){if(capacity<1)throw new IllegalArgumentException();this.capacity=capacity;}
    synchronized void put(Key key,double lat,double lon) {
        if(key==null||Double.isNaN(lat)||Double.isInfinite(lat)||Double.isNaN(lon)||Double.isInfinite(lon)||lat < -90 || lat > 90 || lon < -180 || lon > 180)throw new IllegalArgumentException();
        values.put(key,new Point(lat,lon));
        while(values.size()>capacity){Key eldest=values.keySet().iterator().next();values.remove(eldest);}
    }
    /** Fail closed on verification errors; clear must also run on every scope change. */
    synchronized Point getVerified(Key key,Validator validator){
        if(key==null||validator==null)return null;
        Point value=values.get(key);if(value==null)return null;
        try {if(validator.currentlyAuthorizedAndIdentityVerified(key.volume,key.id))return value;}
        catch(RuntimeException ignored) { /* Verification failure must never reveal a point. */ }
        values.remove(key);return null;
    }
    synchronized void clear(){values.clear();}
    synchronized int size(){return values.size();}
}
