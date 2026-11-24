package org.example.map002probe;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import android.util.Size;
import java.util.Set;

/** Read-only photo metadata helper. This does not establish any vendor GPS identity. */
final class MediaStoreLookup {
    static final class Metadata {
        final long id;final Uri uri;final Long dateTaken;
        Metadata(long id,Uri uri,Long dateTaken){this.id=id;this.uri=uri;this.dateTaken=dateTaken;}
    }
    private final Context context;
    private final ContentResolver resolver;
    MediaStoreLookup(Context context){this.context=context.getApplicationContext();resolver=context.getContentResolver();}
    private boolean primaryOnly(){
        if(Build.VERSION.SDK_INT<30)return false;
        Set<String> volumes=MediaStore.getExternalVolumeNames(context);
        return volumes.size()==1 && volumes.contains(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    }
    Metadata lookup(long id,CancellationSignal cancel) {
        if(cancel==null)throw new IllegalArgumentException();
        cancel.throwIfCanceled();
        if(id<0 || !primaryOnly())return null;
        Uri collection=MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        try(Cursor c=resolver.query(collection,new String[]{"_id","datetaken"},"_id=?",new String[]{Long.toString(id)},null,cancel)) {
            if(c==null || !c.moveToFirst() || c.getLong(0)!=id)return null;
            Long date=c.isNull(1)||c.getLong(1)==0?null:Long.valueOf(c.getLong(1));
            if(c.moveToNext())return null;
            cancel.throwIfCanceled();
            return new Metadata(id,ContentUris.withAppendedId(collection,id),date);
        } catch(SecurityException e){return null;}
    }
    Bitmap thumbnail(long id,CancellationSignal cancel) {
        if(cancel==null)throw new IllegalArgumentException();
        cancel.throwIfCanceled();
        Metadata first=lookup(id,cancel);if(first==null)return null;
        Bitmap bitmap=null;
        try {
            bitmap=resolver.loadThumbnail(first.uri,new Size(128,128),cancel);
            cancel.throwIfCanceled();
            if(bitmap==null)return null;
            Metadata after=lookup(id,cancel);
            if(after==null){bitmap.recycle();return null;}
            cancel.throwIfCanceled();
            return bitmap;
        } catch(android.os.OperationCanceledException e){if(bitmap!=null)bitmap.recycle();throw e;}
          catch(Exception e){if(bitmap!=null)bitmap.recycle();return null;}
    }
}
