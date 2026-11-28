package io.github.szzeng.mapalbum;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.ByteArrayOutputStream;
import java.util.function.IntToLongFunction;

/** Device-specific bridge; false means the caller must use its complete fallback. */
final class VivoNativeGalleryLauncher {
    private static final String PACKAGE="com.vivo.gallery";
    private static final String ACTIVITY="com.android.gallery3d.app.Gallery";
    private static final int MAX_THUMBNAIL_BYTES=24*1024;
    private VivoNativeGalleryLauncher(){}

    static boolean launch(Activity activity,int count,int index,IntToLongFunction keyAt,Bitmap thumbnailBitmap,int orientation){
        try{
            PackageInfo info=activity.getPackageManager().getPackageInfo(PACKAGE,0);
            if(!NativeGalleryQueue.isSupportedVersion(info.getLongVersionCode(),info.versionName))return false;
            NativeGalleryQueue queue=NativeGalleryQueue.create(count,index,keyAt);
            if(queue==null)return false;
            long selectedKey=keyAt.applyAsLong(index);
            Uri collection=MediaIdentity.isVideo(selectedKey)
                ?MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                :MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri selectedUri=ContentUris.withAppendedId(collection,MediaIdentity.id(selectedKey));
            Intent intent=new Intent(Intent.ACTION_VIEW);
            intent.setClassName(PACKAGE,ACTIVITY);
            // On the verified version a mixed queue needs image/jpeg even when the selected URI is a video.
            intent.setDataAndType(selectedUri,queue.entryMimeType());
            intent.putExtra("local_file_custom_flag",true);
            intent.putIntegerArrayListExtra("local_file_custom_id_list",queue.ids);
            intent.putExtra("local_file_custom_index",index);
            byte[] thumbnail=encodeThumbnail(thumbnailBitmap,orientation);
            if(thumbnail!=null)intent.putExtra("thumbnail_data",thumbnail);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ActivityOptions animation=ActivityOptions.makeCustomAnimation(activity,
                R.anim.native_gallery_fade_in,R.anim.native_gallery_hold);
            activity.startActivity(intent,animation.toBundle());
            return true;
        }catch(PackageManager.NameNotFoundException|RuntimeException unavailable){
            return false;
        }
    }

    private static byte[] encodeThumbnail(Bitmap bitmap,int orientation){
        if(bitmap==null||bitmap.isRecycled())return null;
        Bitmap original=bitmap,scaled=null,rotated=null;
        try{
            if(orientation!=0){
                Matrix matrix=new Matrix();matrix.postRotate(-orientation);
                rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);
                bitmap=rotated;
            }
            int largest=Math.max(bitmap.getWidth(),bitmap.getHeight());
            for(int limit:new int[]{256,160,96}){
                Bitmap candidate=bitmap;
                if(largest>limit){
                    float scale=(float)limit/largest;
                    scaled=Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(bitmap.getWidth()*scale)),Math.max(1,Math.round(bitmap.getHeight()*scale)),true);
                    candidate=scaled;
                }
                try{
                    for(int quality:new int[]{70,45,25}){
                        ByteArrayOutputStream out=new ByteArrayOutputStream();
                        if(candidate.compress(Bitmap.CompressFormat.JPEG,quality,out)&&out.size()<=MAX_THUMBNAIL_BYTES)return out.toByteArray();
                    }
                }finally{
                    if(scaled!=null&&scaled!=bitmap)scaled.recycle();
                    scaled=null;
                }
            }
        }catch(RuntimeException ignored){}
        finally{if(scaled!=null&&scaled!=bitmap&&!scaled.isRecycled())scaled.recycle();if(rotated!=null&&rotated!=original&&!rotated.isRecycled())rotated.recycle();}
        return null;
    }
}
