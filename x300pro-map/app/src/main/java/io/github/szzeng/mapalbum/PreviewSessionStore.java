package io.github.szzeng.mapalbum;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

/** One process-local preview snapshot; process death safely invalidates its token. */
final class PreviewSessionStore {
    private static String token;
    private static ArrayList<PhotoItem> photos;
    static synchronized String put(List<PhotoItem> value) {
        token=UUID.randomUUID().toString();
        photos=new ArrayList<>(value);
        return token;
    }
    static synchronized ArrayList<PhotoItem> get(String key) {
        return key != null && key.equals(token) ? photos : null;
    }
    static synchronized void clear(String key) { if (key != null && key.equals(token)) { token=null; photos=null; } }
}
