package io.github.szzeng.mapalbum;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Parcel;
import android.provider.MediaStore;
import java.util.ArrayList;
import java.io.ByteArrayOutputStream;
import java.util.function.IntToLongFunction;

/** Device-specific bridge; false means the caller must use its complete fallback. */
final class VivoNativeGalleryLauncher {
    private static final String PACKAGE="com.vivo.gallery";
    private static final String ACTIVITY="com.android.gallery3d.app.Gallery";
    private static final int MAX_INTENT_BYTES=64*1024;
    private static final int MAX_THUMBNAIL_BYTES=24*1024;
    private VivoNativeGalleryLauncher(){}

    static boolean launch(Activity activity,int count,int index,IntToLongFunction keyAt,Bitmap videoThumbnail){
        try{
            PackageInfo info=activity.getPackageManager().getPackageInfo(PACKAGE,0);
            if(!NativeGalleryQueue.isSupportedVersion(info.getLongVersionCode(),info.versionName))return false;
            NativeGalleryQueue queue=NativeGalleryQueue.create(count,index,keyAt);
            if(queue==null)return false;
            ArrayList<Uri> uris=new ArrayList<>(count);
            ClipData clip=null;
            for(int i=0;i<count;i++){
                long key=keyAt.applyAsLong(i);
                Uri collection=MediaIdentity.isVideo(key)
                    ?MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    :MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri uri=ContentUris.withAppendedId(collection,MediaIdentity.id(key));
                uris.add(uri);
                if(clip==null)clip=ClipData.newRawUri("media",uri);
                else clip.addItem(new ClipData.Item(uri));
            }
            Intent intent=new Intent(Intent.ACTION_VIEW);
            intent.setClassName(PACKAGE,ACTIVITY);
            // On the verified version a mixed queue needs image/jpeg even when the selected URI is a video.
            intent.setDataAndType(uris.get(index),queue.entryMimeType());
            intent.putExtra("local_file_custom_flag",true);
            intent.putIntegerArrayListExtra("local_file_custom_id_list",queue.ids);
            intent.putExtra("local_file_custom_index",index);
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris);
            if(MediaIdentity.isVideo(keyAt.applyAsLong(index))){
                byte[] thumbnail=encodeThumbnail(videoThumbnail);
                if(thumbnail!=null)intent.putExtra("thumbnail_data",thumbnail);
            }
            intent.setClipData(clip);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if(intentSize(intent)>MAX_INTENT_BYTES){
                intent.removeExtra("thumbnail_data");
                if(intentSize(intent)>MAX_INTENT_BYTES)return false;
            }
            activity.startActivity(intent);
            return true;
        }catch(PackageManager.NameNotFoundException|RuntimeException unavailable){
            return false;
        }
    }

    private static int intentSize(Intent intent){
        Parcel parcel=Parcel.obtain();
        try{intent.writeToParcel(parcel,0);return parcel.dataSize();}
        finally{parcel.recycle();}
    }

    private static byte[] encodeThumbnail(Bitmap bitmap){
        if(bitmap==null||bitmap.isRecycled())return null;
        try{
            for(int quality:new int[]{70,45,25}){
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                if(bitmap.compress(Bitmap.CompressFormat.JPEG,quality,out)&&out.size()<=MAX_THUMBNAIL_BYTES)return out.toByteArray();
            }
        }catch(RuntimeException ignored){}
        return null;
    }
}
