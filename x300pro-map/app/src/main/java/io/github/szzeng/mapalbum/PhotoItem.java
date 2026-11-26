package io.github.szzeng.mapalbum;

import android.net.Uri;

/** Transient metadata for one photo in the current viewport. */
final class PhotoItem {
    final Uri uri, documentUri;
    final double latitude, longitude;
    final Long dateTaken;
    final boolean dateFallback;
    final long mediaVersion;
    final String relativePath;
    PhotoItem(Uri uri, double latitude, double longitude, Long dateTaken, boolean dateFallback, long mediaVersion, String relativePath) {
        this.uri=uri; this.documentUri=uri; this.latitude=latitude; this.longitude=longitude;
        this.dateTaken=dateTaken; this.dateFallback=dateFallback; this.mediaVersion=mediaVersion; this.relativePath=relativePath;
    }
}
