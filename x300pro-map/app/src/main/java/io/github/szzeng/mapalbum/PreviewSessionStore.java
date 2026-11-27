package io.github.szzeng.mapalbum;

import java.util.List;
import java.util.UUID;
import org.example.map002probe.MapDataRepository;

/** One process-local preview snapshot; process death safely invalidates its token. */
final class PreviewSessionStore {
    static final class Session {
        final List<PhotoItem> photos;
        final MapDataRepository repository;
        final long scopeToken,singleId;
        Session(List<PhotoItem> photos,MapDataRepository repository,long scopeToken,long singleId){
            this.photos=photos;
            this.repository=repository;
            this.scopeToken=scopeToken;
            this.singleId=singleId;
        }
    }
    private static String token;
    private static Session session;
    static synchronized String put(Session value) {
        token=UUID.randomUUID().toString();
        session=value;
        return token;
    }
    static synchronized Session get(String key) {
        return key != null && key.equals(token) ? session : null;
    }
    static synchronized void clear(String key) { if (key != null && key.equals(token)) { token=null; session=null; } }
}
