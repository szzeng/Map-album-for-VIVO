package org.example.map002probe;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.content.ContentUris;
import android.graphics.Bitmap;
import android.util.Size;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.TextView;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only bounded probe: never opens photos or persists photo data. */
public final class MainActivity extends Activity {
    private static final String TAG="MAP002ReadOnly";
    private static final String PROVIDER="com.android.gallery3d.dataprovider";
    private static final String VIVO_PERMISSION="com.android.gallery3d.permission.GalleryDataProvider";
    private static final int MAX_SAMPLE=2000;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final CancellationSignal cancel=new CancellationSignal();
    private TextView screen;
    private boolean started;
    private void log(String message) { Log.i(TAG,message); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        screen=new TextView(this);screen.setText("MAP-002 read-only sample. No originals are opened or modified; only aggregate results are logged.");setContentView(screen);
        if(getIntent().getBooleanExtra("no_request",false)) { startSample(); return; }
        if (Build.VERSION.SDK_INT>=34) requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES,"android.permission.READ_MEDIA_VISUAL_USER_SELECTED"},1);
        else if (Build.VERSION.SDK_INT>=33) requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES},1);
        else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},1);
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==1) startSample();
    }
    private void startSample() {
        if(started)return;
        started=true;
        int delay=Math.min(15000,Math.max(0,getIntent().getIntExtra("delay_ms",0)));
        log("sample_scheduled_delay_ms="+delay);
        main.postDelayed(new Runnable(){@Override public void run(){
            if(cancel.isCanceled())return;
            main.postDelayed(new Runnable(){@Override public void run(){cancel.cancel();}},10000);
            worker.execute(new Runnable(){@Override public void run(){ReadOnlyQuery.runSample(MainActivity.this,getIntent(),cancel);}});
        }},delay);
    }
    @Override protected void onDestroy(){ cancel.cancel(); main.removeCallbacksAndMessages(null);worker.shutdownNow();super.onDestroy(); }
}
