package org.example.map002probe;
import android.content.Context; import android.os.CancellationSignal;
final class PrimaryVideoSource {
    static RuntimeException listFailure, batchFailure;
    static long[] ids=new long[0];
    PrimaryVideoSource(Context context){}
    long[] visibleIds(int capacity,CancellationSignal signal){signal.throwIfCanceled();if(listFailure!=null)throw listFailure;return ids.clone();}
    void appendBatch(long[] ids,int from,int to,PrimitiveLocationSet output,CancellationSignal signal){signal.throwIfCanceled();if(batchFailure!=null)throw batchFailure;for(int i=from;i<to;i++)output.add(ids[i],23,113);}
}
