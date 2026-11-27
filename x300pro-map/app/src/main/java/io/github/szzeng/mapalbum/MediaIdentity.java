package io.github.szzeng.mapalbum;

/** In-memory key for a MediaStore row; photo and video IDs occupy separate namespaces. */
public final class MediaIdentity {
    private static final long VIDEO_BIT = 1L << 62;
    private MediaIdentity() { }

    public static long photo(long id) { return encode(id, false); }
    public static long video(long id) { return encode(id, true); }
    private static long encode(long id, boolean video) {
        if (id < 0 || id >= VIDEO_BIT) throw new IllegalArgumentException("invalid MediaStore ID");
        return video ? id | VIDEO_BIT : id;
    }
    public static boolean isVideo(long key) {
        if (key < 0) throw new IllegalArgumentException("invalid media key");
        return (key & VIDEO_BIT) != 0;
    }
    public static long id(long key) {
        if (key < 0) throw new IllegalArgumentException("invalid media key");
        return key & ~VIDEO_BIT;
    }
}
