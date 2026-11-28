package io.github.szzeng.mapalbum;

public final class NativeGalleryQueueTest {
    private static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args){
        check(NativeGalleryQueue.isSupportedVersion(110006008L,"11.0.6.8"));
        check(!NativeGalleryQueue.isSupportedVersion(110006009L,"11.0.6.8"));
        check(!NativeGalleryQueue.isSupportedVersion(110006008L,"11.0.6.9"));
        check(!NativeGalleryQueue.isSupportedVersion(110006008L,null));
        long[] keys={MediaIdentity.photo(81),MediaIdentity.video(4144),MediaIdentity.photo(95)};
        NativeGalleryQueue q=NativeGalleryQueue.create(keys.length,1,i->keys[i]);
        check(q!=null&&q.ids.equals(java.util.Arrays.asList(81,4144,95)));
        check(q.hasPhotos&&q.hasVideos&&q.entryMimeType().equals("image/jpeg"));
        check(NativeGalleryQueue.create(1,0,i->MediaIdentity.video(1)).entryMimeType().equals("video/mp4"));
        check(NativeGalleryQueue.create(1,0,i->MediaIdentity.photo(Integer.MAX_VALUE))!=null);
        check(NativeGalleryQueue.create(1,0,i->MediaIdentity.video((long)Integer.MAX_VALUE+1))==null);
        check(NativeGalleryQueue.create(129,0,i->1)==null);
        check(NativeGalleryQueue.create(128,127,i->i)!=null);
        check(NativeGalleryQueue.create(2,2,i->1)==null);
        check(NativeGalleryQueue.create(1,0,i->-1)==null);
        System.out.println("NativeGalleryQueueTest passed");
    }
}
