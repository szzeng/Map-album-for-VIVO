package org.example.map002probe;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.provider.MediaStore;

/** One-shot diagnostic. It logs only booleans and times, and never emits photo IDs or contents. */
final class MediaStoreLookupProbe {
    private static void log(String s){ReadOnlyQuery.log("media_lookup_"+s);}
    static void run(Context context,CancellationSignal cancel,boolean hold) {
        try {
            Uri images=MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            long id;
            try(Cursor c=context.getContentResolver().query(images,new String[]{"_id"},null,null,"_id DESC",cancel)) {
                if(c==null||!c.moveToFirst()){log("stopped=no_visible_image");return;}id=c.getLong(0);
            }
            MediaStoreLookup lookup=new MediaStoreLookup(context);
            long start=SystemClock.elapsedRealtime();
            MediaStoreLookup.Metadata metadata=lookup.lookup(id,cancel);
            if(metadata==null){log("stopped=metadata_unavailable");return;}
            if(hold){log("waiting_after_metadata=true");SystemClock.sleep(5000);cancel.throwIfCanceled();}
            boolean uriExpected=metadata.uri.equals(android.content.ContentUris.withAppendedId(images,id));
            boolean uriRecordMatches=false;
            try(Cursor c=context.getContentResolver().query(metadata.uri,new String[]{"_id","datetaken"},null,null,null,cancel)) {
                if(c!=null && c.moveToFirst()){
                    Long time=c.isNull(1)||c.getLong(1)==0?null:Long.valueOf(c.getLong(1));
                    uriRecordMatches=c.getLong(0)==id && (time==null?metadata.dateTaken==null:time.equals(metadata.dateTaken)) && !c.moveToNext();
                }
            }
            boolean missing=lookup.lookup(Long.MAX_VALUE,cancel)==null;
            CancellationSignal preCancelled=new CancellationSignal();preCancelled.cancel();boolean cancelled=false;
            try {lookup.lookup(id,preCancelled);}catch(android.os.OperationCanceledException expected){cancelled=true;}
            Bitmap thumb=lookup.thumbnail(id,cancel);boolean available=thumb!=null;
            if(thumb!=null)thumb.recycle();
            log("uri_matches_selected="+uriExpected+" uri_record_matches="+uriRecordMatches+" taken_time_present="+(metadata.dateTaken!=null)+" missing_id_rejected="+missing+" pre_cancel_observed="+cancelled+" thumbnail_available="+available+" elapsed_ms="+(SystemClock.elapsedRealtime()-start));
        } catch(android.os.OperationCanceledException e){log("cancelled=true");}
        catch(Exception e){log("exception="+e.getClass().getSimpleName());}
    }
}
