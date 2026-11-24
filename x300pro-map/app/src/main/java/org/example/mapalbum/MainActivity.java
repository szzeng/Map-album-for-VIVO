package org.example.mapalbum;
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
import org.maplibre.android.annotations.*;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.MapLibre;
import org.maplibre.android.maps.*;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;

public class MainActivity extends Activity {
    private MapView view;
    private MapLibreMap map;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private CancellationSignal pending;
    private long generation;
    private boolean picking;
    private TextView count;
    private AlertDialog previewDialog;
    private final List<SamplePhotoRepository.Sample> samples=new ArrayList<>();
    private final Map<Long,SamplePhotoRepository.Sample> markers=new HashMap<>();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); org.maplibre.android.log.Logger.setVerbosity(org.maplibre.android.log.Logger.NONE); MapLibre.getInstance(this);
        FrameLayout root = new FrameLayout(this); view = new MapView(this); root.addView(view); setContentView(root);
        view.onCreate(state);
        TextView credit = new TextView(this); credit.setText("© 天地图 · GS（2025）1508号"); credit.setTextSize(10); credit.setBackgroundColor(0xCCFFFFFF);
        FrameLayout.LayoutParams pos = new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.RIGHT); root.addView(credit,pos);
        Button settings = new Button(this); settings.setText("地图配置");
        FrameLayout.LayoutParams settingsPos = new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.RIGHT); root.addView(settings,settingsPos);
        settings.setOnClickListener(v -> configureKey());
        LinearLayout controls=new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL);
        count=new TextView(this); count.setText("照片 0 · 位置待核验"); count.setBackgroundColor(0xDDFFFFFF); controls.addView(count);
        Button select=new Button(this); select.setText("选择照片"); controls.addView(select);
        Button clear=new Button(this); clear.setText("清空"); controls.addView(clear);
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);root.addView(controls,cp);
        select.setOnClickListener(v -> choosePhotos());clear.setOnClickListener(v -> clearSamples());
        view.getMapAsync(map -> {
            this.map = map;
            map.setOnMarkerClickListener(marker -> { SamplePhotoRepository.Sample sample=markers.get(marker.getId()); if(sample!=null)showNearbyPhotos(sample); return true; });
            map.setCameraPosition(new CameraPosition.Builder().target(new LatLng(23.13,113.27)).zoom(5).build());
            loadMap();
        });
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
    private void showMarkers() {
        if(map==null||samples.isEmpty())return;LatLngBounds.Builder bounds=new LatLngBounds.Builder();
        for(SamplePhotoRepository.Sample s:samples){LatLng p=new LatLng(s.latitude,s.longitude);bounds.include(p);Bitmap icon=markerBitmap(s.thumbnail);
            Marker marker=map.addMarker(new MarkerOptions().position(p).icon(IconFactory.getInstance(this).fromBitmap(icon)));markers.put(marker.getId(),s);}
        if(samples.size()==1)map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(samples.get(0).latitude,samples.get(0).longitude),14));
        else try{map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(),120));}catch(Exception ignored){map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(samples.get(0).latitude,samples.get(0).longitude),14));}
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
        android.graphics.PointF origin=map.getProjection().toScreenLocation(new LatLng(selected.latitude,selected.longitude));
        float radius=48*getResources().getDisplayMetrics().density;
        List<SamplePhotoRepository.Sample> nearby=new ArrayList<>();
        for(SamplePhotoRepository.Sample sample:samples){
            android.graphics.PointF p=map.getProjection().toScreenLocation(new LatLng(sample.latitude,sample.longitude));
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
        generation++;if(previewDialog!=null)previewDialog.dismiss();if(pending!=null){pending.cancel();pending=null;}if(map!=null)map.removeAnnotations();markers.clear();
        for(SamplePhotoRepository.Sample s:samples)s.thumbnail.recycle();samples.clear();if(count!=null)count.setText("照片 0 · 位置待核验");
    }
    private void configureKey() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("输入天地图 Key");
        new AlertDialog.Builder(this).setTitle("天地图配置").setView(input)
            .setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) -> {
                String key = input.getText().toString().trim();
                if (!key.matches("[A-Za-z0-9]+")) { Toast.makeText(this, "Key 格式不正确", Toast.LENGTH_LONG).show(); return; }
                try (java.io.FileOutputStream out = openFileOutput("tdt_key", MODE_PRIVATE)) {
                    out.write(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    loadMap();
                } catch (java.io.IOException e) { Toast.makeText(this, "保存失败", Toast.LENGTH_LONG).show(); }
            }).show();
    }
    private void loadMap() {
            if (map == null) return;
            String key; try (java.io.FileInputStream in = openFileInput("tdt_key")) { key = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim(); } catch (Exception e) { Toast.makeText(this, "请在地图配置中输入 Key", Toast.LENGTH_LONG).show(); return; }
            String common="?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk="+key;
            String vec="https://t0.tianditu.gov.cn/vec_w/wmts"+common+"&LAYER=vec";
            String labels="https://t0.tianditu.gov.cn/cva_w/wmts"+common+"&LAYER=cva";
            String json="{\"version\":8,\"sources\":{\"vec\":{\"type\":\"raster\",\"tiles\":[\""+vec+"\"],\"tileSize\":256,\"maxzoom\":18},\"labels\":{\"type\":\"raster\",\"tiles\":[\""+labels+"\"],\"tileSize\":256,\"maxzoom\":18}},\"layers\":[{\"id\":\"vec\",\"type\":\"raster\",\"source\":\"vec\"},{\"id\":\"labels\",\"type\":\"raster\",\"source\":\"labels\"}]}";
            map.setStyle(new Style.Builder().fromJson(json));
    }
    @Override protected void onStart(){super.onStart();view.onStart();}
    @Override protected void onResume(){super.onResume();view.onResume();}
    @Override protected void onPause(){view.onPause();super.onPause();}
    @Override protected void onStop(){if(!picking)clearSamples();view.onStop();super.onStop();}
    @Override public void onLowMemory(){super.onLowMemory();view.onLowMemory();}
    @Override protected void onDestroy(){clearSamples();worker.shutdownNow();view.onDestroy();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);view.onSaveInstanceState(b);}
}
