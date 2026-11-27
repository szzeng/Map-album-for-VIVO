package io.github.szzeng.mapalbum;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import android.view.ViewGroup;
import android.widget.*;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Debug-only, transient probe. Same-ID provider rows are not verified media identities. */
public final class VideoCheckActivity extends Activity {
    private static final Uri VIVO=Uri.parse("content://com.android.gallery3d.dataprovider/mediaextra");
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private CancellationSignal signal;
    private ThumbnailLoader thumbnails;
    private LinearLayout rows;
    private TextView message;
    private int generation;
    private boolean cameraOnly=true;
    private boolean photoMode=false;
    private static final class Video {
        long id, date;
        String mime, path, gps="Provider 查询失败", providerType="未返回", identity="未核验";
        Double lat, lon;
        boolean seen;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        thumbnails=new ThumbnailLoader(getContentResolver());
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(12),dp(16),0);
        TextView title=new TextView(this);title.setText("视频 GPS 与缩略图验证（Debug）");title.setTextSize(20);root.addView(title);
        message=new TextView(this);root.addView(message);
        Button permission=new Button(this);permission.setText("授权 / 重新选择视频");permission.setOnClickListener(v->requestVideoPermission());root.addView(permission);
        Button type=new Button(this);type.setText("切换样本（当前：视频）");type.setOnClickListener(v->{photoMode=!photoMode;type.setText(photoMode?"切换样本（当前：照片）":"切换样本（当前：视频）");load();});root.addView(type);
        Button scope=new Button(this);scope.setText("切换范围（当前：相机）");scope.setOnClickListener(v->{cameraOnly=!cameraOnly;scope.setText(cameraOnly?"切换范围（当前：相机）":"切换范围（当前：所有视频）");load();});root.addView(scope);
        Button refresh=new Button(this);refresh.setText("刷新最近视频");refresh.setOnClickListener(v->load());root.addView(refresh);
        ScrollView scroll=new ScrollView(this);rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);scroll.addView(rows);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
    }
    private int dp(int value){return (int)(getResources().getDisplayMetrics().density*value+0.5f);}
    private boolean canRead(){
        if(photoMode){if(Build.VERSION.SDK_INT>=34)return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)==PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)==PackageManager.PERMISSION_GRANTED;if(Build.VERSION.SDK_INT>=33)return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)==PackageManager.PERMISSION_GRANTED;return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;}
        if(Build.VERSION.SDK_INT>=34)return checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)==PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)==PackageManager.PERMISSION_GRANTED;
        if(Build.VERSION.SDK_INT>=33)return checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)==PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;
    }
    private void requestVideoPermission(){
        if(Build.VERSION.SDK_INT>=34)requestPermissions(new String[]{photoMode?Manifest.permission.READ_MEDIA_IMAGES:Manifest.permission.READ_MEDIA_VIDEO,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED},41);
        else if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{photoMode?Manifest.permission.READ_MEDIA_IMAGES:Manifest.permission.READ_MEDIA_VIDEO},41);
        else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},41);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==41)load();}
    @Override protected void onResume(){super.onResume();load();}
    @Override protected void onStop(){super.onStop();invalidate();rows.removeAllViews();}
    @Override protected void onDestroy(){invalidate();thumbnails.close();worker.shutdownNow();super.onDestroy();}
    private void invalidate(){generation++;if(signal!=null)signal.cancel();thumbnails.clear();}
    private void load(){
        invalidate();rows.removeAllViews();
        if(!canRead()){message.setText("未获当前媒体访问权限；照片地图不受影响。请点击授权。");return;}
        message.setText("正在读取当前授权范围内最近最多 20 条视频…");
        final int token=generation;final boolean camera=cameraOnly, photo=photoMode;final CancellationSignal cancel=new CancellationSignal();signal=cancel;
        worker.execute(()->{
            ArrayList<Video> videos=new ArrayList<>();String error=null;
            try {readVideos(videos,camera,photo,cancel);readProvider(videos,cancel);} catch(Exception e){if(!cancel.isCanceled())error="媒体列表读取失败（"+e.getClass().getSimpleName()+"），请重新授权并刷新。";}
            final String result=error;
            runOnUiThread(()->{if(token!=generation||isFinishing())return;if(result!=null){message.setText(result);return;}showVideos(videos,token);});
        });
    }
    private void readVideos(ArrayList<Video> out,boolean camera,boolean photo,CancellationSignal cancel){
        Bundle args=new Bundle();args.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS,new String[]{MediaStore.Video.Media.DATE_TAKEN,MediaStore.Video.Media._ID});
        args.putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION,ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);args.putInt(ContentResolver.QUERY_ARG_LIMIT,20);
        if(camera){args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,MediaStore.Video.Media.RELATIVE_PATH+" = ?");args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,new String[]{"DCIM/Camera/"});}
        Uri uri=photo?MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY):MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        try(Cursor c=getContentResolver().query(uri,new String[]{MediaStore.Video.Media._ID,MediaStore.Video.Media.MIME_TYPE,MediaStore.Video.Media.DATE_TAKEN,MediaStore.MediaColumns.DATA},args,cancel)){
            if(c==null)throw new IllegalStateException();
            while(out.size()<20&&c.moveToNext()){cancel.throwIfCanceled();Video v=new Video();v.id=c.getLong(0);v.mime=c.getString(1);v.date=c.getLong(2);v.path=c.getString(3);out.add(v);}
        }
    }
    private void readProvider(ArrayList<Video> videos,CancellationSignal cancel){
        if(videos.isEmpty())return;
        StringBuilder where=new StringBuilder("_id IN (");String[] args=new String[videos.size()];
        for(int i=0;i<videos.size();i++){if(i>0)where.append(',');where.append('?');args[i]=Long.toString(videos.get(i).id);videos.get(i).gps="无对应 Provider 记录（不能判定无 GPS）";}
        where.append(')');
        try(Cursor c=getContentResolver().query(VIVO,new String[]{"_id","latitude","longitude","media_type","_data"},where.toString(),args,null,cancel)){
            if(c==null)throw new IllegalStateException();
            while(c.moveToNext()){
                cancel.throwIfCanceled();Video v=null;for(Video item:videos)if(item.id==c.getLong(0)){v=item;break;}
                if(v==null)throw new IllegalStateException();
                if(v.seen){v.gps="ID 对应关系无法确认：Provider 返回重复记录";v.identity="重复记录";v.lat=null;v.lon=null;continue;}
                v.seen=true;
                v.providerType=c.isNull(3)?"null":c.getString(3);
                String providerPath=c.getString(4);v.identity=v.path==null||v.path.isEmpty()||providerPath==null||providerPath.isEmpty()?"路径不可用":v.path.equals(providerPath)?"路径一致":"路径不一致";
                if("路径不一致".equals(v.identity)){String a=v.path.substring(v.path.lastIndexOf('/')+1),b=providerPath.substring(providerPath.lastIndexOf('/')+1);v.identity=a.equals(b)?"路径不一致（文件名一致）":"路径不一致（文件名不同）";}
                if(c.isNull(1)||c.isNull(2)){v.gps="候选记录：GPS 为空；身份未确认";continue;}
                double lat=c.getDouble(1),lon=c.getDouble(2);
                if(!Double.isFinite(lat)||!Double.isFinite(lon)||lat< -90||lat>90||lon< -180||lon>180||(lat==0&&lon==0)){v.gps="候选记录：GPS 为零或无效；身份未确认";continue;}
                v.lat=lat;v.lon=lon;v.gps="候选记录：有有效范围坐标；身份未确认";
            }
        }catch(Exception e){if(cancel.isCanceled())throw e;for(Video v:videos){v.gps="Provider 查询失败";v.lat=null;v.lon=null;}}
    }
    private void showVideos(ArrayList<Video> videos,int token){
        int type1=0,type3=0,matching=0,different=0,unknown=0;
        for(Video v:videos){if("1".equals(v.providerType))type1++;if("3".equals(v.providerType))type3++;if("路径一致".equals(v.identity))matching++;else if(v.identity.startsWith("路径不一致"))different++;else unknown++;}
        message.setText((cameraOnly?"相机目录":"所有目录")+"样本 "+videos.size()+"；类型1="+type1+"，类型3="+type3+"；路径一致="+matching+"，不一致="+different+"，未知="+unknown+"。相同 ID 仅为候选，坐标基准尚未确认。");
        for(Video v:videos){
            LinearLayout row=new LinearLayout(this);row.setPadding(0,dp(12),0,dp(12));
            ImageView image=new ImageView(this);image.setBackgroundColor(0xffdddddd);image.setScaleType(ImageView.ScaleType.CENTER_CROP);row.addView(image,new LinearLayout.LayoutParams(dp(100),dp(100)));
            LinearLayout details=new LinearLayout(this);details.setPadding(dp(12),0,0,0);details.setOrientation(LinearLayout.VERTICAL);row.addView(details,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
            TextView text=new TextView(this);String date=v.date>0?DateFormat.getDateTimeInstance().format(new Date(v.date)):"拍摄时间未知";
            String coords=v.lat==null?"":String.format(Locale.US,"\n纬度 %.6f，经度 %.6f",v.lat,v.lon);
            text.setText(date+"\nProvider media_type: "+v.providerType+" · "+v.identity+"\n"+v.gps+coords);details.addView(text);
            TextView status=new TextView(this);status.setText("缩略图读取中…");details.addView(status);rows.addView(row);
            Uri uri=ContentUris.withAppendedId(photoMode?MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY):MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),v.id);
            Button open=new Button(this);open.setText("在手机相册中核对");open.setOnClickListener(view->{
                try {Intent intent=new Intent(Intent.ACTION_VIEW);intent.setDataAndType(uri,v.mime==null?"video/*":v.mime);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(intent);}
                catch(RuntimeException unavailable){Toast.makeText(this,"无法打开手机中的媒体应用",Toast.LENGTH_SHORT).show();}
            });details.addView(open);
            thumbnails.load(uri,0,160,bitmap->{if(token!=generation)return;if(bitmap==null)status.setText("缩略图读取失败（占位）");else{image.setImageBitmap(bitmap);status.setText("缩略图读取成功；请人工核对内容");}});
        }
    }
}
