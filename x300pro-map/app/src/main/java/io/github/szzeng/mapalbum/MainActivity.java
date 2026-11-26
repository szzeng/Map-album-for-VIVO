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
    private ThumbnailLoader thumbnails;
    private CancellationSignal pending;
    private long generation;
    private boolean picking;
    private boolean viewingExternal;
    private boolean externalStopped;
    private TextView count;
    private LinearLayout areaPanel;
    private TextView areaTitle;
    private GridView areaGrid;
    private AreaAdapter areaAdapter;
    private int areaRestorePosition=-1;
    private AlertDialog previewDialog;
    private final List<SamplePhotoRepository.Sample> samples=new ArrayList<>();
    private final ArrayList<Uri> chosen=new ArrayList<>();
    private final Map<SamplePhotoRepository.Sample,LatLng> positions=new IdentityHashMap<>();
    private final Map<String,MarkerResource> clusterMarkers=new HashMap<>();
    private final android.os.Handler uiHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshClusters=this::refreshClusters;
    private static final class MarkerResource {
        final Marker marker; BitmapDescriptor descriptor; Bitmap bitmap; boolean requested,hasCover;
        MarkerResource(Marker m,BitmapDescriptor d,Bitmap b){marker=m;descriptor=d;bitmap=b;}
        void release(){marker.remove();descriptor.recycle();bitmap.recycle();}
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        thumbnails=new ThumbnailLoader(getContentResolver());
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
            android.content.SharedPreferences preferences=getPreferences(MODE_PRIVATE);
            double savedLat=Double.longBitsToDouble(preferences.getLong("map_lat",Double.doubleToRawLongBits(23.1291)));
            double savedLon=Double.longBitsToDouble(preferences.getLong("map_lon",Double.doubleToRawLongBits(113.2644)));
            float savedZoom=preferences.getFloat("map_zoom",14);
            if(!Double.isFinite(savedLat)||savedLat < -90||savedLat > 90||!Double.isFinite(savedLon)||savedLon < -180||savedLon > 180||!Float.isFinite(savedZoom)||savedZoom < 3||savedZoom > 22){savedLat=23.1291;savedLon=113.2644;savedZoom=14;}
            MapStatus initial=new MapStatus.Builder().target(new LatLng(savedLat,savedLon)).zoom(savedZoom).build();
            FrameLayout root=new FrameLayout(this);view=new MapView(this,new BaiduMapOptions().mapStatus(initial));root.addView(view);setContentView(root);view.onResume();
            map=view.getMap();map.setMapType(BaiduMap.MAP_TYPE_NORMAL);
            map.setOnMarkerClickListener(marker->{Bundle info=marker.getExtraInfo();int[] indexes=info==null?null:info.getIntArray("samples");if(indexes!=null)showClusterPhotos(indexes);return true;});
            map.setOnMapStatusChangeListener(new BaiduMap.OnMapStatusChangeListener(){
                public void onMapStatusChangeStart(MapStatus status) {cancelThumbnailRequests();}
                public void onMapStatusChangeStart(MapStatus status,int reason) {cancelThumbnailRequests();}
                public void onMapStatusChange(MapStatus status) { }
                public void onMapStatusChangeFinish(MapStatus status) {saveMapStatus(status);scheduleClusters();}
            });
            LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);
            count=new TextView(this);count.setText("照片 0 · 坐标待核验");count.setBackgroundColor(0xDDFFFFFF);controls.addView(count);
            Button select=new Button(this);select.setText("选择照片");controls.addView(select);
            Button clear=new Button(this);clear.setText("清空");controls.addView(clear);
            Button verify=new Button(this);verify.setText("身份核验");controls.addView(verify);
            Button area=new Button(this);area.setText("本区域照片");controls.addView(area);
            root.addView(controls,new FrameLayout.LayoutParams((int)(180*getResources().getDisplayMetrics().density),-2,Gravity.TOP|Gravity.LEFT));
            areaPanel=new LinearLayout(this);areaPanel.setOrientation(LinearLayout.VERTICAL);areaPanel.setBackgroundColor(0xF5FFFFFF);
            LinearLayout areaHeader=new LinearLayout(this);areaTitle=new TextView(this);areaTitle.setPadding(16,16,16,16);
            areaHeader.addView(areaTitle,new LinearLayout.LayoutParams(0,-2,1));Button closeArea=new Button(this);closeArea.setText("关闭");areaHeader.addView(closeArea);areaPanel.addView(areaHeader);
            areaGrid=new GridView(this);areaGrid.setNumColumns(3);areaGrid.setHorizontalSpacing(3);areaGrid.setVerticalSpacing(3);areaAdapter=new AreaAdapter();areaGrid.setAdapter(areaAdapter);
            areaPanel.addView(areaGrid,new LinearLayout.LayoutParams(-1,0,1));areaPanel.setVisibility(android.view.View.GONE);
            root.addView(areaPanel,new FrameLayout.LayoutParams(-1,(int)(260*getResources().getDisplayMetrics().density),Gravity.BOTTOM));
            area.setOnClickListener(v->{areaPanel.setVisibility(android.view.View.VISIBLE);scheduleClusters();});
            closeArea.setOnClickListener(v->areaPanel.setVisibility(android.view.View.GONE));
            areaGrid.setOnItemClickListener((parent,item,position,id)->showPhotoDetails(areaAdapter.items,position));
            select.setOnClickListener(v->choosePhotos());clear.setOnClickListener(v->{chosen.clear();clearSamples();});
            verify.setOnClickListener(v->startActivity(new Intent(this,IdentityCheckActivity.class)));
        } catch(RuntimeException ex){new AlertDialog.Builder(this).setMessage("百度地图初始化失败").setPositiveButton("关闭",(d,w)->finish()).show();}
    }
    private void saveMapStatus(MapStatus status){
        if(status==null||status.target==null||!Double.isFinite(status.target.latitude)||!Double.isFinite(status.target.longitude)||!Float.isFinite(status.zoom))return;
        getPreferences(MODE_PRIVATE).edit().putLong("map_lat",Double.doubleToRawLongBits(status.target.latitude)).putLong("map_lon",Double.doubleToRawLongBits(status.target.longitude)).putFloat("map_zoom",status.zoom).apply();
    }
    private void choosePhotos() {
        chosen.clear();clearSamples(); picking=true;
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
        chosen.clear();chosen.addAll(uris);loadSelected(uris,true);
    }
    private void loadSelected(List<Uri> uris,boolean moveMap) {
        final CancellationSignal signal=new CancellationSignal(); pending=signal;final long ticket=++generation;count.setText("正在读取所选照片…");
        worker.execute(() -> {
            try {
                SamplePhotoRepository.Result resultData=new SamplePhotoRepository(this).load(uris,signal);
                runOnUiThread(() -> {
                    if(ticket!=generation || signal.isCanceled())return;
                    samples.addAll(resultData.samples);showMarkers(moveMap);
                    count.setText("照片 "+samples.size()+" · 待核验 | 无法关联 "+resultData.unmapped+" | 无有效位置 "+resultData.withoutGps+" | 暂不可用 "+resultData.unavailable+"\n不支持转换 "+resultData.unsupportedUri+" | 卷未确认 "+resultData.wrongVolume+" | 元数据不可读 "+resultData.metadataFailure);
                    if(resultData.unmapped>0)Toast.makeText(this,"部分照片无法可靠转换为主卷媒体 ID，请改用系统文件中的图片",Toast.LENGTH_LONG).show();
                });
            } catch(Exception e){runOnUiThread(() -> {if(ticket==generation&&!signal.isCanceled()){count.setText("读取失败，请重新选择");Toast.makeText(this,"样本读取失败或访问被拒绝",Toast.LENGTH_LONG).show();}});}
        });
    }
    private Bitmap markerBitmap(Bitmap original,int count) {
        int n=128;Bitmap output=Bitmap.createBitmap(n,n,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(output);
        Paint border=new Paint(Paint.ANTI_ALIAS_FLAG);border.setColor(android.graphics.Color.WHITE);canvas.drawRoundRect(new RectF(0,0,n,n),20,20,border);
        if(original!=null){Bitmap scaled=Bitmap.createScaledBitmap(original,n-12,n-12,false); Paint image=new Paint(Paint.ANTI_ALIAS_FLAG); image.setShader(new BitmapShader(scaled,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP));
        canvas.save();canvas.translate(6,6);canvas.drawRoundRect(new RectF(0,0,n-12,n-12),16,16,image);canvas.restore();if(scaled!=original)scaled.recycle();}
        else {Paint placeholder=new Paint(Paint.ANTI_ALIAS_FLAG);placeholder.setColor(0xffc8c8c8);canvas.drawRoundRect(new RectF(6,6,n-6,n-6),16,16,placeholder);}
        if(count>1){Paint badge=new Paint(Paint.ANTI_ALIAS_FLAG);badge.setColor(0xE6000000);canvas.drawRoundRect(new RectF(73,89,125,125),15,15,badge);badge.setColor(-1);badge.setTextSize(24);badge.setTextAlign(Paint.Align.CENTER);canvas.drawText(Integer.toString(count),99,115,badge);}return output;
    }
    private void clearMarkers() {
        uiHandler.removeCallbacks(refreshClusters);for(MarkerResource resource:clusterMarkers.values())resource.release();clusterMarkers.clear();positions.clear();
    }
    private void showMarkers(boolean moveMap) {
        if(map==null)return;clearMarkers();if(samples.isEmpty())return;
        LatLngBounds.Builder bounds=new LatLngBounds.Builder();LatLng first=null;int shown=0,excluded=0;
        for(int i=0;i<samples.size();i++){
            SamplePhotoRepository.Sample sample=samples.get(i);
            // This temporary probe is restricted to the confirmed Guangzhou-area samples.
            if(sample.latitude<22 || sample.latitude>24.5 || sample.longitude<112 || sample.longitude>115){excluded++;continue;}
            LatLng p=CoordinateAdapter.adapt(sample.latitude,sample.longitude);if(p==null){excluded++;continue;}
            positions.put(sample,p);bounds.include(p);if(first==null)first=p;shown++;
        }
        if(excluded>0)Toast.makeText(this,"本次仅验证广州附近样本，部分照片未显示",Toast.LENGTH_LONG).show();
        scheduleClusters();if(first==null || !moveMap)return;
        if(shown==1)map.animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(first,14));
        else try{map.animateMapStatus(MapStatusUpdateFactory.newLatLngBounds(bounds.build()));}catch(Exception ignored){map.animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(first,14));}
    }
    private void scheduleClusters(){uiHandler.removeCallbacks(refreshClusters);uiHandler.postDelayed(refreshClusters,200);}
    private void cancelThumbnailRequests(){uiHandler.removeCallbacks(refreshClusters);thumbnails.cancelPending();for(MarkerResource resource:clusterMarkers.values())resource.requested=false;}
    private void refreshClusters(){
        if(map==null||view==null||view.getWidth()==0||view.getHeight()==0)return;
        ArrayList<Integer> visible=new ArrayList<>();ArrayList<android.graphics.Point> points=new ArrayList<>();
        for(int i=0;i<samples.size();i++){
            LatLng p=positions.get(samples.get(i));if(p==null)continue;
            android.graphics.Point point=map.getProjection().toScreenLocation(p);
            if(point!=null&&point.x>=0&&point.y>=0&&point.x<view.getWidth()&&point.y<view.getHeight()){visible.add(i);points.add(point);}
        }
        int[] xs=new int[points.size()],ys=new int[points.size()];for(int i=0;i<points.size();i++){xs[i]=points.get(i).x;ys[i]=points.get(i).y;}
        updateArea(visible);
        List<ScreenClusters.Cluster> groups=ScreenClusters.group(xs,ys,Math.max(1,(int)(72*getResources().getDisplayMetrics().density)),100);
        Set<String> retained=new HashSet<>();
        for(ScreenClusters.Cluster group:groups){
            int[] indexes=new int[group.members.length];for(int i=0;i<indexes.length;i++)indexes[i]=visible.get(group.members[i]);
            String key=Arrays.toString(indexes);retained.add(key);MarkerResource resource=clusterMarkers.get(key);
            SamplePhotoRepository.Sample sample=samples.get(indexes[0]);
            if(resource==null){Bitmap bitmap=markerBitmap(null,indexes.length);BitmapDescriptor descriptor=BitmapDescriptorFactory.fromBitmap(bitmap);Bundle info=new Bundle();info.putIntArray("samples",indexes);
                Marker marker=(Marker)map.addOverlay(new MarkerOptions().position(positions.get(sample)).icon(descriptor).anchor(.5f,.5f).extraInfo(info));
                resource=new MarkerResource(marker,descriptor,bitmap);clusterMarkers.put(key,resource);}
            if(!resource.hasCover&&!resource.requested){
                final MarkerResource target=resource;target.requested=true;final long ticket=generation;
                thumbnails.load(sample.uri,sample.mediaVersion,128,cover->{
                    if(ticket!=generation||clusterMarkers.get(key)!=target)return;target.requested=false;if(cover==null)return;
                    Bitmap updated=markerBitmap(cover,indexes.length);BitmapDescriptor descriptor=BitmapDescriptorFactory.fromBitmap(updated);
                    target.marker.setIcon(descriptor);target.descriptor.recycle();target.bitmap.recycle();target.descriptor=descriptor;target.bitmap=updated;target.hasCover=true;
                });
            }
        }
        Iterator<Map.Entry<String,MarkerResource>> iterator=clusterMarkers.entrySet().iterator();
        while(iterator.hasNext()){Map.Entry<String,MarkerResource> entry=iterator.next();if(!retained.contains(entry.getKey())){entry.getValue().release();iterator.remove();}}
    }
    private final class AreaAdapter extends android.widget.BaseAdapter {
        final ArrayList<SamplePhotoRepository.Sample> items=new ArrayList<>();
        public int getCount(){return items.size();}
        public SamplePhotoRepository.Sample getItem(int position){return items.get(position);}
        public long getItemId(int position){return position;}
        public android.view.View getView(int position,android.view.View recycled,android.view.ViewGroup parent){
            ImageView image=recycled instanceof ImageView?(ImageView)recycled:new ImageView(MainActivity.this);
            int available=parent.getWidth();if(available<=0)available=getResources().getDisplayMetrics().widthPixels;
            int width=Math.max(1,available/3);image.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1,width));
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setImageDrawable(null);image.setBackgroundColor(0xffdddddd);
            SamplePhotoRepository.Sample sample=getItem(position);String tag=sample.uri.toString()+"|"+sample.mediaVersion+"|"+width;image.setTag(tag);
            thumbnails.load(sample.uri,sample.mediaVersion,width,bitmap->{if(tag.equals(image.getTag()))image.setImageBitmap(bitmap);});return image;
        }
    }
    private void updateArea(List<Integer> visible){
        if(areaAdapter==null)return;
        ArrayList<SamplePhotoRepository.Sample> updated=new ArrayList<>();for(int index:visible)updated.add(samples.get(index));
        Collections.sort(updated,(a,b)->Long.compare(b.dateTaken==null?0:b.dateTaken,a.dateTaken==null?0:a.dateTaken));
        areaAdapter.items.clear();areaAdapter.items.addAll(updated);areaAdapter.notifyDataSetChanged();areaTitle.setText("本区域照片（"+updated.size()+"）");
        if(areaRestorePosition>=0){areaGrid.setSelection(Math.min(areaRestorePosition,Math.max(0,updated.size()-1)));areaRestorePosition=-1;}
    }
    private void showPhotoDetails(List<SamplePhotoRepository.Sample> source,int position){
        if(position<0||position>=source.size())return;new PreviewSession(new ArrayList<>(source),position).show();
    }
    private final class PreviewSession {
        final List<SamplePhotoRepository.Sample> items;
        final ZoomablePreview image=new ZoomablePreview(MainActivity.this);
        final TextView details=new TextView(MainActivity.this),imageStatus=new TextView(MainActivity.this),positionLabel=new TextView(MainActivity.this);
        final Button previous=new Button(MainActivity.this),next=new Button(MainActivity.this);
        final long ticket=generation;
        AlertDialog dialog;int index,revision;
        PreviewSession(List<SamplePhotoRepository.Sample> items,int index){this.items=items;this.index=index;}
        void show(){
            LinearLayout content=new LinearLayout(MainActivity.this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(16,8,16,8);
            image.setBackgroundColor(0xffeeeeee);content.addView(image,new LinearLayout.LayoutParams(-1,(int)(getResources().getDisplayMetrics().heightPixels*.55f)));
            LinearLayout navigation=new LinearLayout(MainActivity.this);previous.setText("上一张");next.setText("下一张");
            navigation.addView(previous);positionLabel.setGravity(Gravity.CENTER);navigation.addView(positionLabel,new LinearLayout.LayoutParams(0,-2,1));navigation.addView(next);content.addView(navigation);
            details.setPadding(8,16,8,8);details.setTextIsSelectable(true);content.addView(details);
            imageStatus.setPadding(8,0,8,8);content.addView(imageStatus);
            ScrollView scroll=new ScrollView(MainActivity.this);scroll.addView(content);
            dialog=new AlertDialog.Builder(MainActivity.this).setView(scroll)
                .setPositiveButton("在相册中查看原图",(d,w)->openPhoto(items.get(index))).setNegativeButton("关闭",null).create();
            previous.setOnClickListener(v->move(-1));next.setOnClickListener(v->move(1));image.setSwipeListener(forward->move(forward?1:-1));
            previewDialog=dialog;dialog.setOnDismissListener(d->{revision++;cancelPreview();image.setImageDrawable(null);if(previewDialog==dialog)previewDialog=null;if(ticket==generation&&!isFinishing())scheduleClusters();});
            dialog.show();render();
        }
        void move(int delta){int target=index+delta;if(target<0||target>=items.size())return;index=target;render();}
        boolean active(int version){return dialog.isShowing()&&ticket==generation&&revision==version;}
        void render(){
            final int version=++revision;cancelPreview();image.setImageDrawable(null);
            final SamplePhotoRepository.Sample sample=items.get(index);
            previous.setEnabled(index>0);next.setEnabled(index+1<items.size());positionLabel.setText((index+1)+" / "+items.size());
            String date=sample.dateTaken==null?"未知":java.text.DateFormat.getDateTimeInstance().format(new Date(sample.dateTaken));
            String timeLabel=sample.dateFallback?"系统时间（拍摄时间不可用）":"拍摄时间";
            String path=sample.relativePath==null?"系统未提供":("主存储空间 / "+sample.relativePath);
            details.setText("左右滑动切换，双指缩放；原始细节可在相册查看\n"+timeLabel+"："+date+"\n文件路径："+path);
            imageStatus.setText("正在读取系统缩略图…");
            int size=Math.min(1080,Math.max(256,getResources().getDisplayMetrics().widthPixels));
            thumbnails.load(sample.uri,sample.mediaVersion,size,bitmap->{
                if(!active(version))return;
                if(bitmap==null){imageStatus.setText("缩略图暂不可用，可在相册查看原图");return;}
                image.setImageBitmap(bitmap);image.resetPreview();imageStatus.setText("系统缩略图预览");
            });
        }
        void cancelPreview(){thumbnails.cancelPending();for(MarkerResource resource:clusterMarkers.values())resource.requested=false;}
    }
    private void openPhoto(SamplePhotoRepository.Sample sample) {
        Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(sample.documentUri,"image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {areaRestorePosition=areaPanel!=null&&areaPanel.getVisibility()==android.view.View.VISIBLE?areaGrid.getFirstVisiblePosition():-1;externalStopped=false;viewingExternal=true;startActivity(intent);}
        catch(Exception e){viewingExternal=false;Toast.makeText(this,"无法打开系统图片查看器",Toast.LENGTH_LONG).show();}
    }
    private void showClusterPhotos(int[] indexes) {
        List<SamplePhotoRepository.Sample> nearby=new ArrayList<>();
        for(int index:indexes)if(index>=0&&index<samples.size())nearby.add(samples.get(index));
        if(nearby.isEmpty())return;if(nearby.size()==1){showPhotoDetails(nearby,0);return;}
        Collections.sort(nearby,(a,b)->Long.compare(b.dateTaken==null?0:b.dateTaken,a.dateTaken==null?0:a.dateTaken));
        GridView list=new GridView(this);list.setNumColumns(3);list.setHorizontalSpacing(3);list.setVerticalSpacing(3);
        AreaAdapter adapter=new AreaAdapter();adapter.items.addAll(nearby);list.setAdapter(adapter);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("附近的照片（"+nearby.size()+"）").setView(list).setNegativeButton("关闭",null).create();
        previewDialog=dialog;dialog.setOnDismissListener(d -> {if(previewDialog==dialog)previewDialog=null;});
        list.setOnItemClickListener((parent,item,position,id)->{dialog.dismiss();showPhotoDetails(nearby,position);});
        dialog.show();
    }
    private void clearSamples() {
        generation++;if(previewDialog!=null)previewDialog.dismiss();if(pending!=null){pending.cancel();pending=null;}clearMarkers();
        if(areaAdapter!=null){for(int i=0;i<areaGrid.getChildCount();i++){android.view.View child=areaGrid.getChildAt(i);if(child instanceof ImageView)((ImageView)child).setImageDrawable(null);}areaAdapter.items.clear();areaAdapter.notifyDataSetChanged();areaTitle.setText("本区域照片（0）");}
        thumbnails.clear();samples.clear();if(count!=null)count.setText("照片 0 · 位置待核验");
    }
    @Override protected void onResume(){super.onResume();if(view!=null)view.onResume();
        if(viewingExternal){viewingExternal=false;if(externalStopped&&!chosen.isEmpty()&&view!=null)loadSelected(new ArrayList<>(chosen),false);externalStopped=false;}}
    @Override protected void onPause(){if(view!=null)view.onPause();super.onPause();}
    @Override protected void onStop(){if(!picking){externalStopped=viewingExternal;clearSamples();if(!viewingExternal){chosen.clear();areaRestorePosition=-1;if(areaPanel!=null)areaPanel.setVisibility(android.view.View.GONE);}}super.onStop();}
    @Override protected void onDestroy(){chosen.clear();clearSamples();thumbnails.close();worker.shutdownNow();if(view!=null)view.onDestroy();super.onDestroy();}
}
