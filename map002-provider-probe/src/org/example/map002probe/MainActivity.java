package org.example.map002probe;

import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.InputStream;

public final class MainActivity extends Activity {
    private static final String AUTHORITY = "com.android.gallery3d.dataprovider";
    private static final String PERMISSION = "com.android.gallery3d.permission.GalleryDataProvider";
    private static final String TAG = "MAP002Probe";
    private void log(String value) { Log.i(TAG, value); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setText("MAP-002 controlled probe running. Results in filtered logcat; no private photo values are logged.");
        setContentView(view);
        if (getIntent().getBooleanExtra("no_request", false)) startTests();
        else requestPermissions(new String[] {"android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED", "android.permission.ACCESS_MEDIA_LOCATION"}, 1);
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 1) startTests();
    }
    private void startTests() { new Thread(new Runnable() { @Override public void run() { probe(); log(SqliteProbe.run(MainActivity.this)); synthetic(); } }).start(); }
    private void checkOriginal(Uri image) {
        log("location_permission_granted=" + (checkSelfPermission("android.permission.ACCESS_MEDIA_LOCATION") == PackageManager.PERMISSION_GRANTED));
        try (InputStream in = getContentResolver().openInputStream(MediaStore.setRequireOriginal(image))) {
            if (in == null) { log("androidx_original=null_stream"); return; }
            androidx.exifinterface.media.ExifInterface exif = new androidx.exifinterface.media.ExifInterface(in);
            double[] point = exif.getLatLong();
            log("androidx_original_gps_matches_known=" + (point != null && Math.abs(point[0]-12.3456)<0.0001 && Math.abs(point[1]-67.8912)<0.0001));
            log("androidx_original_datetime_matches=" + "2020:01:02 03:04:05".equals(exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL)));
        } catch (Exception e) { log("androidx_original_exception=" + e.getClass().getName()); }
    }
    private void checkMetadata(Uri image, long id) {
        String[] cols = {"_id", "volume_name", "mime_type", "datetaken", "date_modified", "_size", "generation_added", "generation_modified"};
        try (Cursor c = getContentResolver().query(image, cols, null, null, null)) {
            if (c == null || !c.moveToFirst()) { log("mediastore_single=unavailable"); return; }
            log("mediastore_id_matches=" + (c.getLong(0)==id));
            for (int i=1; i<cols.length; i++) log("mediastore_" + cols[i] + "_present=" + !c.isNull(i));
        } catch (Exception e) { log("mediastore_exception=" + e.getClass().getName()); }
    }
    private void probe() {
        log("permission_granted=" + (checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED));
        ProviderInfo info = getPackageManager().resolveContentProvider(AUTHORITY, 0);
        log("provider_exported=" + (info == null ? "unknown" : info.exported));
        if (info != null) log("read_permission=" + info.readPermission);
        try (Cursor cursor = getContentResolver().query(Uri.parse("content://" + AUTHORITY + "/mediaextra"), null, "1=0", null, null)) {
            if (cursor == null) log("empty_query=null_cursor");
            else {
                log("empty_query=success");
                log("columns=" + android.text.TextUtils.join(",", cursor.getColumnNames()));
                log("empty_selection_count=" + cursor.getCount());
            }
        } catch (Exception e) { log("empty_query_exception=" + e.getClass().getName()); }
    }
    private void synthetic() {
        Uri image = null;
        File temporary = new File(getCacheDir(), "map002-synthetic.jpg");
        try {
            Bitmap b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            b.eraseColor(Color.BLUE);
            try (FileOutputStream out = new FileOutputStream(temporary)) { b.compress(Bitmap.CompressFormat.JPEG, 95, out); }
            b.recycle();
            try (InputStream noGps = new FileInputStream(temporary)) {
                androidx.exifinterface.media.ExifInterface plain = new androidx.exifinterface.media.ExifInterface(noGps);
                log("synthetic_no_gps=" + (plain.getLatLong() == null));
            }
            ExifInterface exif = new ExifInterface(temporary.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "12/1,20/1,4416/100");
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N");
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "67/1,53/1,2832/100");
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E");
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2020:01:02 03:04:05");
            exif.saveAttributes();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "MAP002_SYNTHETIC_TEST.jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MAP002-test");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            image = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (image == null) throw new IllegalStateException("insert returned null");
            try (FileInputStream input = new FileInputStream(temporary); OutputStream output = getContentResolver().openOutputStream(image)) {
                if (output == null) throw new IllegalStateException("output returned null");
                byte[] buf = new byte[8192]; int n;
                while ((n = input.read(buf)) != -1) output.write(buf, 0, n);
            }
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(image, values, null, null);
            long id = android.content.ContentUris.parseId(image);
            log("synthetic_insert=success");
            checkMetadata(image, id);
            checkOriginal(image);
            for (int attempt = 0; attempt < 6; attempt++) {
                Thread.sleep(2000);
                try (Cursor c = getContentResolver().query(Uri.parse("content://" + AUTHORITY + "/mediaextra"),
                    new String[] {"_id", "latitude", "longitude", "origin_date_taken"}, "_id=?", new String[] {Long.toString(id)}, null)) {
                    if (c == null) { log("synthetic_provider=null_cursor"); continue; }
                    boolean found = false;
                    while (c.moveToNext()) {
                        if (c.getLong(0) != id) continue;
                        found = true;
                        boolean match = !c.isNull(1) && !c.isNull(2) && Math.abs(c.getDouble(1)-12.3456)<0.0001 && Math.abs(c.getDouble(2)-67.8912)<0.0001;
                        log("synthetic_same_id=true gps_matches_known=" + match + " timestamp_present=" + !c.isNull(3));
                        break;
                    }
                    if (found) break;
                    if (attempt == 5) log("synthetic_provider_same_id=not_found_after_12s");
                }
            }
        } catch (Exception e) { log("synthetic_exception=" + e.getClass().getName()); }
        finally {
            if (image != null) try {
                log("synthetic_cleanup_rows=" + getContentResolver().delete(image, null, null));
                try (InputStream gone = getContentResolver().openInputStream(image)) { log("deleted_uri_unavailable=" + (gone == null)); }
                catch (java.io.FileNotFoundException e) { log("deleted_uri_unavailable=true"); }
            } catch (Exception e) { log("synthetic_cleanup_exception=" + e.getClass().getName()); }
            log("temporary_cleanup=" + (!temporary.exists() || temporary.delete()));
        }
    }
}
