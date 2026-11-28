package io.github.szzeng.mapalbum;

import android.content.ContentUris;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.LruCache;
import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;
import org.example.map002probe.PhotoTimeline;

/** The whole timeline is addressable, with photo rows materialized in bounded pages. */
final class GalleryPhotoList extends AbstractList<PhotoItem> implements RandomAccess {
    private static final int PAGE_SIZE=96;
    private static final Uri IMAGES=MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    private static final Uri VIDEOS=MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    private final PhotoTimeline timeline;
    private final PhotoTimeline.Order order;
    private final LruCache<Integer,PhotoItem[]> pages=new LruCache<>(4);

    GalleryPhotoList(PhotoTimeline timeline,boolean oldest){
        this.timeline=timeline;
        order=timeline.order(oldest);
    }
    GalleryPhotoList ordered(boolean oldest){return new GalleryPhotoList(timeline,oldest);}
    List<PhotoTimeline.Day> days(){return order.days();}
    int videoCount(){int n=0;for(int i=0;i<size();i++)if(MediaIdentity.isVideo(order.idAt(i)))n++;return n;}
    boolean isVideo(int index){return MediaIdentity.isVideo(order.idAt(index));}
    long mediaKeyAt(int index){return order.idAt(index);}
    @Override public int size(){return order.size();}
    @Override public PhotoItem get(int index){
        if(index<0||index>=size())throw new IndexOutOfBoundsException(index+" / "+size());
        int pageNumber=index/PAGE_SIZE;
        PhotoItem[] page=pages.get(pageNumber);
        if(page==null){
            int start=pageNumber*PAGE_SIZE;
            page=new PhotoItem[Math.min(PAGE_SIZE,size()-start)];
            for(int i=0;i<page.length;i++){
                int at=start+i;
                long date=order.dateAt(at);
                long key=order.idAt(at);page[i]=new PhotoItem(ContentUris.withAppendedId(MediaIdentity.isVideo(key)?VIDEOS:IMAGES,MediaIdentity.id(key)),date==0?null:date,order.versionAt(at));
            }
            pages.put(pageNumber,page);
            if(BuildConfig.DEBUG)android.util.Log.d("MapAlbumPaging","materialized page "+pageNumber+" ("+page.length+" items)");
        }
        return page[index%PAGE_SIZE];
    }
}
