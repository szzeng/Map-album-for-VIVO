package org.example.map002probe;
import android.content.Context; import android.graphics.Bitmap; import android.os.CancellationSignal;
final class MediaStoreLookup { static final class Metadata { final long id; Metadata(long id){this.id=id;} } MediaStoreLookup(Context c){} Metadata lookup(long id,CancellationSignal c){return new Metadata(id);} Metadata[] lookupBatch(long[] ids,CancellationSignal c){Metadata[] result=new Metadata[ids.length];for(int i=0;i<ids.length;i++)result[i]=new Metadata(ids[i]);return result;} Bitmap thumbnail(long id,CancellationSignal c){return null;} }
