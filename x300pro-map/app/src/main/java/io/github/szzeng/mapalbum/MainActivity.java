package io.github.szzeng.mapalbum;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.BitmapShader;
import android.graphics.Shader;
import android.view.Gravity;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.example.map002probe.SamplePhotoRepository;
import com.baidu.mapapi.CoordType;
import com.baidu.mapapi.SDKInitializer;
import com.baidu.mapapi.map.*;
import com.baidu.mapapi.model.LatLng;
import com.baidu.mapapi.model.LatLngBounds;

public class MainActivity extends Activity {
    private MapView view;
    private BaiduMap map;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private CancellationSignal pending;
    private long generation;
    private boolean picking;
    private TextView count;
    private AlertDialog previewDialog;
    private final List<SamplePhotoRepository.Sample> samples=new ArrayList<>();
    private final List<BitmapDescriptor> descriptors=new ArrayList<>();
    private final List<Bitmap> markerBitmaps=new ArrayList<>();
    private final Map<SamplePhotoRepository.Sample,LatLng> positions=new IdentityHashMap<>();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text=new TextView(this);text.setText("百度地图样本验证：等待隐私选择");setContentView(text);
        new AlertDialog.Builder(this).setTitle("百度地图隐私说明")
            .setMessage("本验证使用北京百度网讯科技有限公司的百度地图 SDK。SDK 可能处理设备、网络和地图视野等信息。请先阅读百度地图隐私政策；不同意时不初始化 SDK。")
            .setNeutralButton("查看隐私政策",(d,w)->{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://map.baidu.com/zt/client/privacy/index.html")));finish();})
            .setNegativeButton("不同意",(d,w)->finish())
            .setPositiveButton("同意并打开",(d,w)->initializeMap())
            .setOnCancelListener(d->finish()).show();
    }
    private void initializeMap() {
        try {
            SDKInitializer.setAgreePrivacy(getApplicationContext(),true);
            SDKInitializer.initialize(getApplicationContext());
            SDKInitializer.setCoordType(CoordType.BD09LL);
            MapStatus initial=new MapStatus.Builder().target(new LatLng(23.1291,113.2644)).zoom(14).build();
            FrameLayout root=new FrameLayout(this);view=new MapView(this,new BaiduMapOptions().mapStatus(initial));root.addView(view);setContentView(root);view.onResume();
            map=view.getMap();map.setMapType(BaiduMap.MAP_TYPE_NORMAL);
            map.setOnMarkerClickListener(marker->{Bundle info=marker.getExtraInfo();int index=info==null?-1:info.getInt("sample",-1);if(index>=0&&index<samples.size())showNearbyPhotos(samples.get(index));return true;});
            LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);
            count=new TextView(this);count.setText("照片 0 · 坐标待核验");count.setBackgroundColor(0xDDFFFFFF);controls.addView(count);
            Button select=new Button(this);select.setText("选择照片");controls.addView(select);
            Button clear=new Button(this);clear.setText("清空");controls.addView(clear);
            root.addView(controls,new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT));
            select.setOnClickListener(v->choosePhotos());clear.setOnClickListener(v->clearSamples());
        } catch(RuntimeException ex){new AlertDialog.Builder(this).setMessage("百度地图初始化失败").setPositiveButton("关闭",(d,w)->finish()).show();}
    }
    private void choosePhotos() {
        clearSamples(); picking=true;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        try {startActivityForResult(intent,101);} catch(Exception e){picking=false;Toast.makeText(this,"无法打开照片选择器",Toast.LENGTH_LONG).show();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data); if(request!=101)return; picking=false;
        if(result!=RESULT_OK||data==null)return;
        ArrayList<Uri> uris=new ArrayList<>();
        if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());}
        else if(data.getData()!=null)uris.add(data.getData());
        if(uris.isEmpty()||uris.size()>20){Toast.makeText(this,"请选择 1 至 20 张照片",Toast.LENGTH_LONG).show();return;}
        final CancellationSignal signal=new CancellationSignal(); pending=signal;final long ticket=++generation;count.setText("正在读取所选照片…");
        worker.execute(() -> {
            try {
                SamplePhotoRepository.Result resultData=new SamplePhotoRepository(this).load(uris,signal);
                runOnUiThread(() -> {
                    if(ticket!=generation || signal.isCanceled()){for(SamplePhotoRepository.Sample s:resultData.samples)s.thumbnail.recycle();return;}
                    samples.addAll(resultData.samples);showMarkers();
                    count.setText("照片 "+samples.size()+" · 待核验 | 无法关联 "+resultData.unmapped+" | 无 GPS "+resultData.withoutGps+" | 暂不可用 "+resultData.unavailable+"\n不支持转换 "+resultData.unsupportedUri+" | 卷未确认 "+resultData.wrongVolume+" | 元数据不可读 "+resultData.metadataFailure);
                    if(resultData.unmapped>0)Toast.makeText(this,"部分照片无法可靠转换为主卷媒体 ID，请改用系统文件中的图片",Toast.LENGTH_LONG).show();
                });
            } catch(Exception e){runOnUiThread(() -> {if(ticket==generation&&!signal.isCanceled()){count.setText("读取失败，请重新选择");Toast.makeText(this,"样本读取失败或访问被拒绝",Toast.LENGTH_LONG).show();}});}
        });
    }
    private Bitmap markerBitmap(Bitmap original) {
        int n=128;Bitmap output=Bitmap.createBitmap(n,n,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(output);
        Paint border=new Paint(Paint.ANTI_ALIAS_FLAG);border.setColor(android.graphics.Color.WHITE);canvas.drawRoundRect(new RectF(0,0,n,n),20,20,border);
        Bitmap scaled=Bitmap.createScaledBitmap(original,n-12,n-12,false); Paint image=new Paint(Paint.ANTI_ALIAS_FLAG); image.setShader(new BitmapShader(scaled,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP));
        canvas.save();canvas.translate(6,6);canvas.drawRoundRect(new RectF(0,0,n-12,n-12),16,16,image);canvas.restore();if(scaled!=original)scaled.recycle();return output;
    }
    private void clearMarkers() {
        if(map!=null)map.clear();positions.clear();
        for(BitmapDescriptor d:descriptors)d.recycle();descriptors.clear();
        for(Bitmap b:markerBitmaps)if(!b.isRecycled())b.recycle();markerBitmaps.clear();
    }
    private void showMarkers() {
        if(map==null)return;clearMarkers();if(samples.isEmpty())return;
        LatLngBounds.Builder bounds=new LatLngBounds.Builder();LatLng first=null;int shown=0,excluded=0;
        for(int i=0;i<samples.size();i++){
            SamplePhotoRepository.Sample sample=samples.get(i);
            // This temporary probe is restricted to the confirmed Guangzhou-area samples.
            if(sample.latitude<22 || sample.latitude>24.5 || sample.longitude<112 || sample.longitude>115){excluded++;continue;}
            LatLng p=CoordinateAdapter.adapt(sample.latitude,sample.longitude);if(p==null){excluded++;continue;}
            positions.put(sample,p);bounds.include(p);if(first==null)first=p;shown++;
            Bitmap icon=markerBitmap(sample.thumbnail);markerBitmaps.add(icon);BitmapDescriptor descriptor=BitmapDescriptorFactory.fromBitmap(icon);descriptors.add(descriptor);
            Bundle info=new Bundle();info.putInt("sample",i);
            map.addOverlay(new MarkerOptions().position(p).icon(descriptor).anchor(0.5f,0.5f).extraInfo(info));
        }
        if(excluded>0)Toast.makeText(this,"本次仅验证广州附近样本，部分照片未显示",Toast.LENGTH_LONG).show();
        if(first==null)return;
        if(shown==1)map.animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(first,14));
        else try{map.animateMapStatus(MapStatusUpdateFactory.newLatLngBounds(bounds.build()));}catch(Exception ignored){map.animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(first,14));}
    }
    private void showPhoto(SamplePhotoRepository.Sample sample) {
        ImageView image=new ImageView(this);image.setImageBitmap(sample.thumbnail);image.setAdjustViewBounds(true);
        String date=sample.dateTaken==null?"拍摄时间未知":java.text.DateFormat.getDateTimeInstance().format(new Date(sample.dateTaken));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(date).setMessage("位置和照片关联待你核验").setView(image).setPositiveButton("关闭",null).create();
        previewDialog=dialog;
        CancellationSignal previewSignal=new CancellationSignal(); long ticket=generation;
        dialog.setOnDismissListener(d -> {previewSignal.cancel();image.setImageDrawable(null);if(previewDialog==dialog)previewDialog=null;});dialog.show();
        worker.execute(() -> {
            Bitmap larger=null;
            try { larger=getContentResolver().loadThumbnail(sample.uri,new android.util.Size(768,768),previewSignal);previewSignal.throwIfCanceled();
                Bitmap ready=larger;larger=null;runOnUiThread(() -> {if(dialog.isShowing() && !previewSignal.isCanceled() && ticket==generation) image.setImageBitmap(ready);else if(ready!=null)ready.recycle();});
            } catch(Exception ignored) {} finally {if(larger!=null)larger.recycle();}
        });
    }
    private void showNearbyPhotos(SamplePhotoRepository.Sample selected) {
        LatLng selectedPosition=positions.get(selected);if(selectedPosition==null)return;
        android.graphics.Point origin=map.getProjection().toScreenLocation(selectedPosition);
        float radius=48*getResources().getDisplayMetrics().density;
        List<SamplePhotoRepository.Sample> nearby=new ArrayList<>();
        for(SamplePhotoRepository.Sample sample:samples){
            LatLng position=positions.get(sample);if(position==null)continue;
            android.graphics.Point p=map.getProjection().toScreenLocation(position);
            if(Math.hypot(p.x-origin.x,p.y-origin.y)<=radius)nearby.add(sample);
        }
        if(nearby.size()<=1){showPhoto(selected);return;}
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("附近的照片（"+nearby.size()+"）").setView(list).setNegativeButton("关闭",null).create();
        previewDialog=dialog;dialog.setOnDismissListener(d -> {if(previewDialog==dialog)previewDialog=null;});
        for(SamplePhotoRepository.Sample sample:nearby){
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setPadding(12,8,12,8);
            ImageView thumb=new ImageView(this);thumb.setImageBitmap(sample.thumbnail);thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);row.addView(thumb,new LinearLayout.LayoutParams(100,100));
            TextView label=new TextView(this);label.setPadding(20,10,0,0);label.setText(sample.dateTaken==null?"拍摄时间未知":java.text.DateFormat.getDateTimeInstance().format(new Date(sample.dateTaken)));row.addView(label);
            row.setOnClickListener(v -> {dialog.dismiss();showPhoto(sample);});list.addView(row);
        }
        ScrollView scroll=new ScrollView(this);scroll.addView(list);dialog.setView(scroll);dialog.show();
    }
    private void clearSamples() {
        generation++;if(previewDialog!=null)previewDialog.dismiss();if(pending!=null){pending.cancel();pending=null;}clearMarkers();
        for(SamplePhotoRepository.Sample s:samples)s.thumbnail.recycle();samples.clear();if(count!=null)count.setText("照片 0 · 位置待核验");
    }
    @Override protected void onResume(){super.onResume();if(view!=null)view.onResume();}
    @Override protected void onPause(){if(view!=null)view.onPause();super.onPause();}
    @Override protected void onStop(){if(!picking)clearSamples();super.onStop();}
    @Override protected void onDestroy(){clearSamples();worker.shutdownNow();if(view!=null)view.onDestroy();super.onDestroy();}
}
