package org.example.map002probe;
import android.content.Context; import android.os.CancellationSignal;
final class PrimaryVideoSource {
    PrimaryVideoSource(Context context){}
    long[] visibleIds(int capacity,CancellationSignal signal){return new long[0];}
    void appendBatch(long[] ids,int from,int to,PrimitiveLocationSet output,CancellationSignal signal){}
}
