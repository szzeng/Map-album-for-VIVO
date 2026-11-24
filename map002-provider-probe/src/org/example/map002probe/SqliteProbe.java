package org.example.map002probe;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.content.ContentValues;

/** Isolated synthetic test of SQLite capabilities. Not a production photo repository. */
final class SqliteProbe {
    static String run(android.content.Context context) {
        java.io.File file = new java.io.File(context.getCacheDir(), "map002-sqlite-probe.db");
        if (file.exists() && !file.delete()) return "sqlite_probe_old_file_could_not_be_removed";
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
        try {
            db.execSQL("CREATE TABLE media (row_id INTEGER PRIMARY KEY, volume TEXT NOT NULL, media_id INTEGER NOT NULL, lat REAL, lon REAL, UNIQUE(volume, media_id))");
            boolean rtree;
            try { db.execSQL("CREATE VIRTUAL TABLE spatial USING rtree(row_id, min_lat, max_lat, min_lon, max_lon)"); rtree = true; }
            catch (RuntimeException unsupported) {
                rtree = false;
                db.execSQL("CREATE TABLE spatial(row_id INTEGER PRIMARY KEY, min_lat REAL, max_lat REAL, min_lon REAL, max_lon REAL)");
                db.execSQL("CREATE INDEX spatial_lat ON spatial(min_lat)");
                db.execSQL("CREATE INDEX spatial_lon ON spatial(min_lon)");
            }
            db.execSQL("CREATE TABLE queue (volume TEXT NOT NULL, media_id INTEGER NOT NULL, PRIMARY KEY(volume, media_id))");
            db.beginTransaction();
            try {
                add(db, "primary", 1, 0, 179.5);
                add(db, "other", 1, 0, -179.5);
                add(db, "primary", 2, 0, 0);
                db.execSQL("INSERT INTO queue VALUES('primary',1)");
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            boolean wrap = count(db, "-1", "1", "170", "180") + count(db, "-1", "1", "-180", "-170") == 2;
            boolean nonwrap = count(db, "-1", "1", "-1", "1") == 1;
            boolean boundary = count(db, "0", "0", "179.5", "179.5") == 1;
            boolean key = scalar(db, "SELECT count(*) FROM media WHERE media_id=1") == 2;
            db.beginTransaction();
            try { db.execSQL("DELETE FROM queue"); } finally { db.endTransaction(); }
            boolean rollback = scalar(db, "SELECT count(*) FROM queue") == 1;
            db.close();
            db = SQLiteDatabase.openOrCreateDatabase(file, null);
            boolean reopen = scalar(db, "SELECT count(*) FROM queue") == 1;
            String plan;
            try (Cursor c = db.rawQuery("EXPLAIN QUERY PLAN SELECT m.row_id FROM spatial r JOIN media m ON m.row_id=r.row_id WHERE r.min_lat<=1 AND r.max_lat>=-1 AND r.min_lon<=1 AND r.max_lon>=-1", null)) {
                StringBuilder b = new StringBuilder(); while (c.moveToNext()) b.append(c.getString(3)).append(';'); plan = b.toString();
            }
            return "sqlite_rtree="+rtree+" fallback_btree="+(!rtree)+" wrap="+wrap+" nonwrap="+nonwrap+" boundary="+boundary+" volume_key="+key+" queue_rollback="+rollback+" queue_reopen="+reopen+" plan="+plan;
        } catch (RuntimeException ex) { return "sqlite_rtree_exception="+ex.getClass().getName(); }
        finally { if (db.isOpen()) db.close(); file.delete(); }
    }
    private static void add(SQLiteDatabase db, String volume, long id, double lat, double lon) {
        ContentValues v = new ContentValues(); v.put("volume", volume); v.put("media_id", id); v.put("lat", lat); v.put("lon", lon);
        long row = db.insertOrThrow("media", null, v);
        db.execSQL("INSERT INTO spatial VALUES(?,?,?,?,?)", new Object[]{row,lat,lat,lon,lon});
    }
    private static int count(SQLiteDatabase db, String south, String north, String west, String east) {
        String sql="SELECT count(*) FROM spatial r JOIN media m ON m.row_id=r.row_id WHERE r.min_lat<=? AND r.max_lat>=? AND r.min_lon<=? AND r.max_lon>=? AND m.lat>=? AND m.lat<=? AND m.lon>=? AND m.lon<=?";
        try (Cursor c=db.rawQuery(sql,new String[]{north,south,east,west,south,north,west,east})) { c.moveToFirst(); return c.getInt(0); }
    }
    private static int scalar(SQLiteDatabase db, String sql) { try (Cursor c=db.rawQuery(sql,null)) { c.moveToFirst(); return c.getInt(0); } }
}
