package io.github.szzeng.mapalbum;

import java.util.ArrayList;
import java.util.function.IntToLongFunction;

/** Validates an entire ordered queue before any native viewer is launched. */
final class NativeGalleryQueue {
    static final int MAX_ITEMS=128;
    final ArrayList<Integer> ids;
    final boolean hasPhotos,hasVideos;

    private NativeGalleryQueue(ArrayList<Integer> ids,boolean photos,boolean videos){
        this.ids=ids;hasPhotos=photos;hasVideos=videos;
    }

    static boolean isSupportedVersion(long code,String name){
        return code==110006008L&&"11.0.6.8".equals(name);
    }

    static NativeGalleryQueue create(int count,int index,IntToLongFunction keyAt){
        if(count<1||count>MAX_ITEMS||index<0||index>=count||keyAt==null)return null;
        ArrayList<Integer> ids=new ArrayList<>(count);
        boolean photos=false,videos=false;
        for(int i=0;i<count;i++){
            long key=keyAt.applyAsLong(i);
            if(key<0)return null;
            long id=MediaIdentity.id(key);
            if(id>Integer.MAX_VALUE)return null;
            boolean video=MediaIdentity.isVideo(key);
            videos|=video;photos|=!video;
            ids.add((int)id);
        }
        return new NativeGalleryQueue(ids,photos,videos);
    }

    String entryMimeType(){return hasVideos&&!hasPhotos?"video/mp4":"image/jpeg";}
}
