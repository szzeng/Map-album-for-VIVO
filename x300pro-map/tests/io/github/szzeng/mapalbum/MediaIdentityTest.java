package io.github.szzeng.mapalbum;

public final class MediaIdentityTest {
    public static void main(String[] args) {
        long photo=MediaIdentity.photo(42), video=MediaIdentity.video(42);
        if (photo==video || MediaIdentity.isVideo(photo) || !MediaIdentity.isVideo(video)
                || MediaIdentity.id(photo)!=42 || MediaIdentity.id(video)!=42) throw new AssertionError("media identity");
        try { MediaIdentity.video(1L << 62); throw new AssertionError("overflow accepted"); }
        catch (IllegalArgumentException expected) { }
        if(!VideoIdentity.matches(3,"/test/video.mp4","/test/video.mp4")
                ||VideoIdentity.matches(1,"/test/video.mp4","/test/video.mp4")
                ||VideoIdentity.matches(3,"/test/video.mp4","/other/video.mp4")
                ||VideoIdentity.matches(3,null,null)||VideoIdentity.matches(null,"a","a"))throw new AssertionError("video identity");
        try { MediaIdentity.photo(-1); throw new AssertionError("negative ID accepted"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("MediaIdentityTest passed");
    }
}
