package org.example.map002probe;
import android.content.Context; import android.os.CancellationSignal;
final class PrimaryPhotoSource { static final class CapacityException extends IllegalStateException {} static final class UnsupportedVolumeException extends IllegalStateException {} int noGpsCount,invalidCount,missingCount; PrimaryPhotoSource(Context c){} void resetCounts(){} boolean primaryOnly(){return true;} long[] visibleIds(int n,CancellationSignal c){return new long[0];} void appendBatch(long[] ids,int from,int to,PrimitiveLocationSet p,CancellationSignal c){} }
