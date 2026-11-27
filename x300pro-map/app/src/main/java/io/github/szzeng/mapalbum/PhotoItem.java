package io.github.szzeng.mapalbum;

import android.net.Uri;

/** Transient metadata for one photo in the selected group. */
final class PhotoItem {
    final Uri uri, documentUri;
    final Long dateTaken;
    final boolean dateFallback;
    final long mediaVersion;
    final String relativePath;
    PhotoItem(Uri uri, Long dateTaken, boolean dateFallback, long mediaVersion, String relativePath) {
        this.uri=uri; this.documentUri=uri;
        this.dateTaken=dateTaken; this.dateFallback=dateFallback; this.mediaVersion=mediaVersion; this.relativePath=relativePath;
    }
}
