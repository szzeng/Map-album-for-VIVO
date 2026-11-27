package org.example.map002probe;
import android.content.Context; import android.graphics.Bitmap; import android.os.CancellationSignal;
final class MediaStoreLookup {
    static final class Metadata {
        final long id,mediaVersion;
        final Long dateTaken;
        Metadata(long id){this(id,null,0);}
        Metadata(long id,Long dateTaken,long mediaVersion){this.id=id;this.dateTaken=dateTaken;this.mediaVersion=mediaVersion;}
    }
    MediaStoreLookup(Context c){}
    Metadata lookup(long id,CancellationSignal c){return new Metadata(id);}
    Metadata[] lookupBatch(long[] ids,CancellationSignal c){Metadata[] result=new Metadata[ids.length];for(int i=0;i<ids.length;i++)result[i]=new Metadata(ids[i]);return result;}
    void lookupTimelineBatch(long[] ids,int from,int to,long[] dates,long[] versions,CancellationSignal c){
        java.util.Arrays.fill(dates,from,to,0);
    }
    Bitmap thumbnail(long id,CancellationSignal c){return null;}
}
