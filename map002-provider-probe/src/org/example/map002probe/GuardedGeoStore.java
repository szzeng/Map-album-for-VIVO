package org.example.map002probe;

import android.os.CancellationSignal;
import java.util.Arrays;

/** Experimental fail-closed lifecycle guard. A caller must independently verify real media identity and visibility. */
final class GuardedGeoStore {
    interface Validator { boolean currentlyVisibleAndIdentityVerified(long id); }
    static final class Ticket {
        final long epoch; final CancellationSignal signal; boolean committed;
        Ticket(long epoch,CancellationSignal signal){this.epoch=epoch;this.signal=signal;}
    }
    private static final int MAX_POINTS=100000;
    private long epoch;
    private CancellationSignal current;
    private long[] ids;
    private double[] lat,lon;
    private boolean foreground;
    private void clearLocked(){ids=null;lat=null;lon=null;}
    void invalidate() {
        CancellationSignal previous;
        synchronized(this){epoch++;foreground=false;clearLocked();previous=current;current=null;}
        if(previous!=null)previous.cancel();
    }
    synchronized void enterForeground(){foreground=true;}
    Ticket beginRefresh() {
        CancellationSignal previous;Ticket ticket;
        synchronized(this) {
            if(!foreground)return null;
            epoch++;clearLocked();previous=current;current=new CancellationSignal();
            ticket=new Ticket(epoch,current);
        }
        if(previous!=null)previous.cancel();
        return ticket;
    }
    synchronized boolean publish(Ticket ticket,long[] id,double[] latitude,double[] longitude,boolean scopeChecked) {
        if(!foreground || !scopeChecked || ticket==null || current!=ticket.signal || ticket.epoch!=epoch || ticket.signal.isCanceled() || ticket.committed)return false;
        if(id==null||latitude==null||longitude==null||id.length!=latitude.length||id.length!=longitude.length||id.length>MAX_POINTS)return false;
        for(int i=0;i<id.length;i++) if((i>0 && id[i]<=id[i-1]) || !Double.isFinite(latitude[i]) || !Double.isFinite(longitude[i]) || latitude[i]<-90 || latitude[i]>90 || longitude[i]<-180 || longitude[i]>180)return false;
        ids=id.clone();lat=latitude.clone();lon=longitude.clone();ticket.committed=true;return true;
    }
    double[] get(long id,Validator validator) {
        long checkedEpoch;double latitude,longitude;
        synchronized(this) {
            if(!foreground || ids==null || validator==null)return null;
            int index=Arrays.binarySearch(ids,id);if(index<0)return null;
            checkedEpoch=epoch;latitude=lat[index];longitude=lon[index];
        }
        boolean valid=false;
        try {valid=validator.currentlyVisibleAndIdentityVerified(id);} catch(RuntimeException ignored) { }
        CancellationSignal rejected=null;
        synchronized(this) {
            // Validation may block; invalidate or a refresh must win before anything is returned.
            if(!foreground || ids==null || epoch!=checkedEpoch)return null;
            if(valid)return new double[]{latitude,longitude};
            clearLocked();epoch++;rejected=current;current=null;
        }
        if(rejected!=null)rejected.cancel();
        return null;
    }
    synchronized int count(){return ids==null?0:ids.length;}
}
