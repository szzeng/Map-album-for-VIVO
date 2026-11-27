package io.github.szzeng.mapalbum;

import android.net.Uri;

/** Transient metadata for one photo in the selected group. */
final class PhotoItem {
    final Uri uri;
    final Long dateTaken;
    final long mediaVersion;
    final boolean video;
    PhotoItem(Uri uri, Long dateTaken, long mediaVersion) {
        this.uri=uri;
        this.dateTaken=dateTaken;
        this.mediaVersion=mediaVersion;
        this.video=uri!=null&&uri.getPathSegments().size()>1&&"video".equals(uri.getPathSegments().get(1));
    }
}
