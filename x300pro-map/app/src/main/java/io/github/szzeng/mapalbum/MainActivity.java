package io.github.szzeng.mapalbum;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Network;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import org.example.map002probe.SamplePhotoRepository;
import com.baidu.mapapi.CoordType;
import com.baidu.mapapi.SDKInitializer;
import com.baidu.mapapi.map.*;
import com.baidu.mapapi.model.LatLng;
import com.baidu.mapapi.model.LatLngBounds;
import com.baidu.mapapi.utils.CoordinateConverter;
import com.baidu.location.BDAbstractLocationListener;
import com.baidu.location.BDLocation;
import com.baidu.location.LocationClient;
import com.baidu.location.LocationClientOption;

public class MainActivity extends Activity {
    private static final int TEXT=0xff252b32, SECONDARY=0xff7b858f, ACCENT=0xff547c91, PLACEHOLDER=0xffe5e9e7;
    private MapView view; private BaiduMap map; private FrameLayout root; private LinearLayout panel, header, previews;
    private TextView count, title, quantity, empty, networkStatus; private GridView grid; private ImageView fit;
    private AreaAdapter adapter; private ThumbnailLoader thumbnails; private CancellationSignal pending;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refresh=this::refreshClusters;
    private final ArrayList<SamplePhotoRepository.Sample> samples=new ArrayList<>(), area=new ArrayList<>(), group=new ArrayList<>();
    private final ArrayList<Uri> chosen=new ArrayList<>();
    private final Map<SamplePhotoRepository.Sample,LatLng> positions=new IdentityHashMap<>();
    private final Map<String,MarkerResource> markers=new HashMap<>();
    private long generation; private boolean expanded, groupMode, loading, privacyAccepted;
    private int restorePosition=-1, restoreOffset, unmapped, withoutGps, unavailable, excluded;
    private String loadError; private int sampleLoads, bottomInset; private boolean networkCallbackRegistered; private Bundle saved; private ArrayList<Uri> restoreGroup;
    private LocationClient locationClient; private BDAbstractLocationListener locationListener;
    private static MapStatus transientMapStatus;
    private final Runnable locationTimeout=()->{stopLocation();Toast.makeText(this,"定位超时，请重试",Toast.LENGTH_SHORT).show();};
    private final ConnectivityManager.NetworkCallback networkCallback=new ConnectivityManager.NetworkCallback(){
        @Override public void onAvailable(Network network){handler.post(MainActivity.this::updateNetworkStatus);}
        @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities){handler.post(MainActivity.this::updateNetworkStatus);}
        @Override public void onLost(Network network){handler.postDelayed(MainActivity.this::updateNetworkStatus,500);}
    };
    private static final Comparator<SamplePhotoRepository.Sample> NEWEST=(a,b)->Long.compare(b.dateTaken==null?0:b.dateTaken,a.dateTaken==null?0:a.dateTaken);
    private static final class MarkerResource {
        final Marker marker; BitmapDescriptor descriptor; Bitmap bitmap; boolean requested,hasCover;
        MarkerResource(Marker m,BitmapDescriptor d,Bitmap b){marker=m;descriptor=d;bitmap=b;}
        void release(){marker.remove();descriptor.recycle();bitmap.recycle();}
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView label(String text,int size,int color){TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
    @Override public void onCreate(Bundle state){super.onCreate(state);saved=state;thumbnails=new ThumbnailLoader(getContentResolver());
        TextView waiting=label("等待隐私选择",14,TEXT);setContentView(waiting);
        if(state!=null && state.getBoolean("privacyAccepted")){privacyAccepted=true;initializeMap();return;}
        new AlertDialog.Builder(this).setTitle("百度地图与定位隐私说明").setMessage("图迹使用百度地图 SDK。点击当前位置后，图迹会请求前台位置权限，并使用百度定位 SDK 获取当前位置，SDK 可能读取 Wi-Fi 状态辅助定位；百度按其隐私政策处理定位请求。图迹不进行后台持续定位、不建立位置历史、不上传位置到图迹自有服务。拒绝定位权限不影响地图和照片浏览。请先阅读百度隐私政策；不同意时不初始化 SDK。")
            .setNeutralButton("查看隐私政策",(d,w)->{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://map.baidu.com/zt/client/privacy/index.html")));finish();})
            .setNegativeButton("不同意",(d,w)->finish()).setPositiveButton("同意并打开",(d,w)->{privacyAccepted=true;initializeMap();}).setOnCancelListener(d->finish()).show();
    }
    private void initializeMap(){try {
        SDKInitializer.setAgreePrivacy(getApplicationContext(),true);SDKInitializer.initialize(getApplicationContext());SDKInitializer.setCoordType(CoordType.BD09LL);
        BaiduMapOptions options=new BaiduMapOptions();
        if(saved!=null&&transientMapStatus!=null)options.mapStatus(transientMapStatus);
        root=new FrameLayout(this);root.setBackgroundColor(0xffeaf0e7);view=new MapView(this,options);view.showZoomControls(false);root.addView(view);setContentView(root);view.onResume();map=view.getMap();map.setMapType(BaiduMap.MAP_TYPE_NORMAL);
        map.setOnMarkerClickListener(m->{Bundle b=m.getExtraInfo();int[] ids=b==null?null:b.getIntArray("samples");if(ids!=null)showCluster(ids);return true;});
        map.setOnMapStatusChangeListener(new BaiduMap.OnMapStatusChangeListener(){public void onMapStatusChangeStart(MapStatus s){cancelRequests();}public void onMapStatusChangeStart(MapStatus s,int reason){cancelRequests();}public void onMapStatusChange(MapStatus s){}public void onMapStatusChangeFinish(MapStatus s){schedule();}});
        buildUi();
        if(saved!=null){expanded=saved.getBoolean("expanded");restorePosition=saved.getInt("scroll",0);restoreOffset=saved.getInt("scrollOffset",0);restoreGroup=saved.getParcelableArrayList("group");ArrayList<Uri> uris=saved.getParcelableArrayList("chosen");if(uris!=null&&!uris.isEmpty()){chosen.addAll(uris);loadSelected(uris,false);}updatePanel();}
        schedule();
    }catch(RuntimeException e){TextView failure=label("地图加载失败，请重新打开图迹",14,SECONDARY);failure.setGravity(Gravity.CENTER);setContentView(failure);}}
    private void buildUi(){
        LinearLayout brand=new LinearLayout(this);brand.setGravity(Gravity.CENTER_VERTICAL);brand.setPadding(dp(9),0,dp(4),0);brand.setBackground(shape(0xf5ffffff,15));brand.setElevation(dp(3));
        ImageView icon=new ImageView(this);icon.setImageDrawable(new PinIcon());icon.setPadding(dp(6),dp(6),dp(6),dp(6));icon.setBackground(shape(ACCENT,11));brand.addView(icon,new LinearLayout.LayoutParams(dp(36),dp(36)));
        LinearLayout names=new LinearLayout(this);names.setOrientation(LinearLayout.VERTICAL);names.setPadding(dp(8),0,0,0);TextView name=label("图迹",17,TEXT);name.setTypeface(null,1);names.addView(name);TextView sub=label("MAP ALBUM",8,SECONDARY);names.addView(sub);brand.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        count=label("样本模式 · 0 张",12,ACCENT);count.setGravity(Gravity.CENTER);count.setPadding(dp(8),0,dp(8),0);count.setBackground(shape(0xffeff3f5,14));brand.addView(count,new LinearLayout.LayoutParams(-2,dp(28)));
        TextView menu=label("•••",18,0xff53616a);menu.setGravity(Gravity.CENTER);menu.setContentDescription("更多菜单");brand.addView(menu,new LinearLayout.LayoutParams(dp(48),dp(48)));menu.setOnClickListener(v->showMenu(menu));
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-1,dp(56),Gravity.TOP);bp.setMargins(dp(16),dp(12),dp(16),0);root.addView(brand,bp);
        networkStatus=label("网络不可用，地图可能无法加载",12,SECONDARY);networkStatus.setPadding(dp(12),0,dp(12),0);networkStatus.setBackground(shape(0xf5ffffff,12));
        FrameLayout.LayoutParams np=new FrameLayout.LayoutParams(-2,dp(36),Gravity.TOP|Gravity.LEFT);np.setMargins(dp(16),dp(76),0,0);root.addView(networkStatus,np);updateNetworkStatus();
        fit=new ImageView(this);fit.setImageDrawable(new FitIcon());fit.setPadding(dp(12),dp(12),dp(12),dp(12));fit.setBackground(shape(0xffffffff,24));fit.setElevation(dp(3));fit.setContentDescription("定位当前位置");fit.setOnClickListener(v->requestCurrentLocation());root.addView(fit,new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.RIGHT|Gravity.BOTTOM));
        panel=new PhotoPanel(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setBackground(shape(0xfaffffff,22));panel.setElevation(dp(5));
        header=new LinearLayout(this);header.setOrientation(LinearLayout.VERTICAL);header.setPadding(dp(16),0,dp(16),0);
        TextView handle=label("",12,SECONDARY);handle.setBackground(shape(0xffd5dce0,2));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(32),dp(4));hp.gravity=Gravity.CENTER_HORIZONTAL;hp.topMargin=dp(10);hp.bottomMargin=dp(4);header.addView(handle,hp);
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);title=label("此处照片",16,TEXT);title.setTypeface(null,1);quantity=label("0 张照片",13,SECONDARY);row.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));row.addView(quantity);header.addView(row);panel.addView(header);
        header.setContentDescription("此处照片，下拉关闭");header.setFocusable(true);header.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS);}@Override public boolean performAccessibilityAction(View host,int action,Bundle args){if(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_DISMISS){closePhotoPanel();return true;}return super.performAccessibilityAction(host,action,args);}});
        header.setOnTouchListener(new View.OnTouchListener(){float y;public boolean onTouch(View v,MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN){y=e.getRawY();return true;}if(e.getActionMasked()==MotionEvent.ACTION_UP){float delta=e.getRawY()-y;if(delta>dp(48))closePhotoPanel();else if(delta< -dp(48)){expanded=true;updatePanel();}return true;}return true;}});
        previews=new LinearLayout(this);previews.setPadding(dp(16),0,dp(16),0);panel.addView(previews,new LinearLayout.LayoutParams(-1,dp(52)));
        empty=label("这个区域还没有带位置信息的照片",13,SECONDARY);empty.setPadding(dp(16),0,dp(16),0);panel.addView(empty,new LinearLayout.LayoutParams(-1,dp(48)));
        grid=new GridView(this);grid.setNumColumns(3);grid.setHorizontalSpacing(dp(4));grid.setVerticalSpacing(dp(4));grid.setPadding(dp(12),dp(6),dp(12),dp(8));grid.setClipToPadding(false);adapter=new AreaAdapter();grid.setAdapter(adapter);panel.addView(grid,new LinearLayout.LayoutParams(-1,0,1));grid.setOnItemClickListener((p,v,pos,id)->openPhoto(adapter.getItem(pos)));
        root.addView(panel,new FrameLayout.LayoutParams(-1,dp(112),Gravity.BOTTOM));root.setOnApplyWindowInsetsListener((v,insets)->{int safe=android.os.Build.VERSION.SDK_INT>=30?insets.getInsets(WindowInsets.Type.systemGestures()).bottom:insets.getSystemWindowInsetBottom();if(bottomInset!=safe){bottomInset=safe;panel.setPadding(0,0,0,safe);updatePanel();}return insets;});root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(b-t!=ob-ot)updatePanel();});renderList();updatePanel();
    }
    private void showMenu(View anchor){PopupMenu p=new PopupMenu(this,anchor);p.getMenu().add("刷新照片数据").setOnMenuItemClickListener(i->{if(!chosen.isEmpty()){ArrayList<Uri> copy=new ArrayList<>(chosen);clearData();loadSelected(copy,false);}else Toast.makeText(this,"请先选择测试照片",Toast.LENGTH_SHORT).show();return true;});
        p.getMenu().add("隐私说明").setOnMenuItemClickListener(i->{new AlertDialog.Builder(this).setMessage("仅处理您选择的测试照片；位置和缩略图仅保存在内存中。点击定位后会请求前台位置权限并使用百度定位 SDK，SDK 可能读取 Wi-Fi 状态辅助定位；百度按其隐私政策处理请求。图迹不后台持续定位、不保存位置历史，也不上传至图迹自有服务。拒绝定位不影响地图和照片浏览。身份尚未核验，未读取全图库。").setPositiveButton("知道了",null).show();return true;});p.getMenu().add("关于图迹").setOnMenuItemClickListener(i->{new AlertDialog.Builder(this).setMessage("图迹 · 地图相册\n当前为受限样本模式").setPositiveButton("知道了",null).show();return true;});
        if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0){p.getMenu().add("身份核验").setOnMenuItemClickListener(i->{startActivity(new Intent(this,IdentityCheckActivity.class));return true;});p.getMenu().add("选择测试照片").setOnMenuItemClickListener(i->{choosePhotos();return true;});p.getMenu().add("清空测试样本").setOnMenuItemClickListener(i->{chosen.clear();clearData();return true;});p.getMenu().add("数据诊断").setOnMenuItemClickListener(i->{showDiagnostics(anchor);return true;});}p.show();}
    private void showDiagnostics(View anchor){
        String message="身份尚未核验 · 仅限所选样本\n已选 "+chosen.size()+" 张 · 有效位置 "+samples.size()+" 张\n"
            +"无法关联 "+unmapped+" · 无有效位置或无记录 "+withoutGps+" · 暂不可用 "+unavailable+"\n"
            +"不在已验证范围 "+excluded+" · 地图可显示 "+positions.size()+"\n样本读取 "+sampleLoads+" 次 · "+thumbnails.diagnosticCounts()+"\n数据仅保存在内存中";
        TextView details=label(message,13,TEXT);details.setPadding(dp(16),dp(12),dp(16),dp(12));details.setBackground(shape(0xffffffff,12));
        PopupWindow popup=new PopupWindow(details,Math.min(dp(340),getResources().getDisplayMetrics().widthPixels-dp(32)),-2,true);
        popup.setBackgroundDrawable(shape(0xffffffff,12));popup.setElevation(dp(4));popup.setOutsideTouchable(true);popup.showAsDropDown(anchor,0,0,Gravity.RIGHT);
    }
    private void updatePanel(){if(panel==null)return;int available=Math.max(0,root.getHeight()-bottomInset);int h=(expanded?Math.round(available*.68f):dp(112))+bottomInset;if(h<=bottomInset)h=dp(112)+bottomInset;FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)panel.getLayoutParams();p.height=h;panel.setLayoutParams(p);panel.setVisibility(groupMode?View.VISIBLE:View.GONE);FrameLayout.LayoutParams f=(FrameLayout.LayoutParams)fit.getLayoutParams();f.rightMargin=dp(16);f.bottomMargin=(groupMode?h:bottomInset)+dp(16);fit.setLayoutParams(f);grid.setVisibility(expanded&&!adapter.items.isEmpty()?View.VISIBLE:View.GONE);previews.setVisibility(!expanded&&!adapter.items.isEmpty()?View.VISIBLE:View.GONE);empty.setVisibility(adapter.items.isEmpty()?View.VISIBLE:View.GONE);}
    private void closePhotoPanel(){groupMode=false;expanded=false;renderList();}
    @Override public void onBackPressed(){if(groupMode){closePhotoPanel();return;}super.onBackPressed();}
    /** A downward pull starting at the top of the grid closes the panel; normal grid scrolling stays with GridView. */
    private final class PhotoPanel extends LinearLayout{
        private float startX,startY;private boolean canPull,dragging;
        PhotoPanel(Context context){super(context);}
        @Override public boolean onInterceptTouchEvent(MotionEvent event){
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:startX=event.getX();startY=event.getY();dragging=false;canPull=grid!=null&&grid.getVisibility()==View.VISIBLE&&startY>=grid.getTop()&&!grid.canScrollVertically(-1);break;
                case MotionEvent.ACTION_MOVE:float dx=event.getX()-startX,dy=event.getY()-startY;if(canPull&&dy>dp(16)&&dy>Math.abs(dx)*1.3f){dragging=true;return true;}break;
                case MotionEvent.ACTION_CANCEL:canPull=false;dragging=false;break;
            }
            return false;
        }
        @Override public boolean onTouchEvent(MotionEvent event){if(!dragging)return super.onTouchEvent(event);if(event.getActionMasked()==MotionEvent.ACTION_UP){boolean close=event.getY()-startY>dp(48);dragging=false;canPull=false;if(close)closePhotoPanel();return true;}if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){dragging=false;canPull=false;}return true;}
    }
    private void renderList(){if(adapter==null)return;List<SamplePhotoRepository.Sample> list=groupMode?group:area;adapter.items.clear();adapter.items.addAll(list);adapter.notifyDataSetChanged();title.setText(groupMode?"此处照片":"当前地图范围");quantity.setText(list.size()+" 张照片");empty.setText(loading?"正在读取照片位置":loadError!=null?loadError:(chosen.isEmpty()?"选择测试照片以查看样本":samples.isEmpty()?"所选样本没有可用的位置数据":"这个区域还没有带位置信息的照片"));
        previews.removeAllViews();for(int i=0;i<Math.min(4,list.size());i++){final SamplePhotoRepository.Sample s=list.get(i);ImageView image=new ImageView(this);image.setBackgroundColor(PLACEHOLDER);image.setScaleType(ImageView.ScaleType.CENTER_CROP);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1);if(i>0)p.leftMargin=dp(4);previews.addView(image,p);String tag=s.uri.toString();image.setTag(tag);thumbnails.load(s.uri,s.mediaVersion,dp(64),b->{if(tag.equals(image.getTag()))image.setImageBitmap(b);});image.setOnClickListener(v->openPhoto(s));}updatePanel();if(restorePosition>=0&&expanded&&!adapter.items.isEmpty()){grid.setSelectionFromTop(restorePosition,restoreOffset);restorePosition=-1;restoreOffset=0;}}
    private void choosePhotos(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);try{startActivityForResult(i,101);}catch(Exception e){Toast.makeText(this,"无法打开照片选择器",Toast.LENGTH_LONG).show();}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=101||result!=RESULT_OK||data==null)return;ArrayList<Uri> uris=new ArrayList<>();if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());}else if(data.getData()!=null)uris.add(data.getData());if(uris.isEmpty()||uris.size()>20){Toast.makeText(this,"请选择 1 至 20 张照片",Toast.LENGTH_LONG).show();return;}chosen.clear();clearData();chosen.addAll(uris);loadSelected(uris,true);}
    private void loadSelected(List<Uri> uris,boolean move){sampleLoads++;CancellationSignal signal=new CancellationSignal();pending=signal;long ticket=++generation;loading=true;loadError=null;count.setText("正在读取照片位置");renderList();worker.execute(()->{try{SamplePhotoRepository.Result result=new SamplePhotoRepository(this).load(uris,signal);runOnUiThread(()->{if(ticket!=generation||signal.isCanceled())return;loading=false;unmapped=result.unmapped;withoutGps=result.withoutGps;unavailable=result.unavailable;samples.clear();samples.addAll(result.samples);if(restoreGroup!=null){group.clear();for(SamplePhotoRepository.Sample s:samples)if(restoreGroup.contains(s.documentUri))group.add(s);Collections.sort(group,NEWEST);groupMode=!group.isEmpty();restoreGroup=null;}count.setText("样本模式 · "+samples.size()+" 张");showMarkers(move);if(result.unmapped>0||result.unavailable>0)Toast.makeText(this,"部分样本无法读取或关联",Toast.LENGTH_LONG).show();});}catch(Exception e){runOnUiThread(()->{if(ticket==generation&&!signal.isCanceled()){loading=false;loadError=e instanceof SecurityException?"照片访问不足，请重新选择样本":"位置数据暂不可用，请检查图库服务后重试";count.setText("样本模式 · 读取失败");renderList();}});}});}
    private void showMarkers(boolean move){clearMarkers();excluded=0;for(SamplePhotoRepository.Sample s:samples){if(s.latitude<22||s.latitude>24.5||s.longitude<112||s.longitude>115){excluded++;continue;}LatLng p=CoordinateAdapter.adapt(s.latitude,s.longitude);if(p!=null)positions.put(s,p);else excluded++;}if(excluded>0)Toast.makeText(this,"仅显示广州附近已验证范围内的样本",Toast.LENGTH_LONG).show();schedule();if(move&&!positions.isEmpty())fitSamples();}
    private void fitSamples(){if(positions.isEmpty()){Toast.makeText(this,"没有可适配的样本位置",Toast.LENGTH_SHORT).show();return;}LatLngBounds.Builder b=new LatLngBounds.Builder();LatLng first=null;for(LatLng p:positions.values()){b.include(p);if(first==null)first=p;}if(positions.size()==1)map.animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(first,14));else try{map.animateMapStatus(MapStatusUpdateFactory.newLatLngBounds(b.build()));}catch(Exception e){map.animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(first,14));}}
    private void requestCurrentLocation(){
        if(checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED&&checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,android.Manifest.permission.ACCESS_COARSE_LOCATION},102);return;}
        android.location.LocationManager manager=(android.location.LocationManager)getSystemService(LOCATION_SERVICE);
        if(manager==null||!manager.isLocationEnabled()){Toast.makeText(this,"请先开启系统定位服务",Toast.LENGTH_SHORT).show();return;}
        stopLocation();
        try{
            LocationClient.setAgreePrivacy(true);
            LocationClient client=new LocationClient(getApplicationContext());locationClient=client;
            LocationClientOption option=new LocationClientOption();option.setCoorType("bd09ll");option.setScanSpan(0);option.setOnceLocation(true);option.setIsNeedAddress(false);option.setOpenGnss(true);client.setLocOption(option);
            locationListener=new BDAbstractLocationListener(){@Override public void onReceiveLocation(BDLocation result){handler.post(()->{if(locationClient!=client)return;stopLocation();if(result==null||(result.getLocType()!=61&&result.getLocType()!=161)){Toast.makeText(MainActivity.this,"定位失败，请检查权限和网络",Toast.LENGTH_SHORT).show();return;}
                double lat=result.getLatitude(),lon=result.getLongitude();String type=result.getCoorType();if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180||type==null){Toast.makeText(MainActivity.this,"定位结果不可用",Toast.LENGTH_SHORT).show();return;}
                LatLng point=new LatLng(lat,lon);if("gcj02".equalsIgnoreCase(type)){point=new CoordinateConverter().from(CoordinateConverter.CoordType.COMMON).coord(point).convert();}else if(!"bd09ll".equalsIgnoreCase(type)&&!"bd09".equalsIgnoreCase(type)&&!"wgs84".equalsIgnoreCase(type)){Toast.makeText(MainActivity.this,"定位坐标类型不可用",Toast.LENGTH_SHORT).show();return;}
                // 百度海外底图使用 WGS84；国内请求 bd09ll，不能把海外 WGS84 当成 BD09 再转换。
                if(point==null){Toast.makeText(MainActivity.this,"定位坐标转换失败",Toast.LENGTH_SHORT).show();return;}map.setMyLocationEnabled(true);map.setMyLocationData(new MyLocationData.Builder().latitude(point.latitude).longitude(point.longitude).accuracy(Math.max(0,result.getRadius())).build());map.animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(point,16));Toast.makeText(MainActivity.this,"定位完成",Toast.LENGTH_SHORT).show();});}};
            client.registerLocationListener(locationListener);client.start();handler.postDelayed(locationTimeout,20000);Toast.makeText(this,"正在定位",Toast.LENGTH_SHORT).show();
        }catch(Exception e){stopLocation();Toast.makeText(this,"定位暂不可用",Toast.LENGTH_SHORT).show();}
    }
    private void stopLocation(){handler.removeCallbacks(locationTimeout);LocationClient client=locationClient;locationClient=null;if(client!=null){if(locationListener!=null)client.unRegisterLocationListener(locationListener);client.stop();}locationListener=null;}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==102){if(checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED||checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED)requestCurrentLocation();else Toast.makeText(this,"未授予位置权限，仍可浏览照片地图",Toast.LENGTH_LONG).show();}}
    private void schedule(){handler.removeCallbacks(refresh);handler.postDelayed(refresh,200);}
    private void cancelRequests(){handler.removeCallbacks(refresh);thumbnails.cancelPending();for(MarkerResource r:markers.values())r.requested=false;}
    private void refreshClusters(){if(map==null||view.getWidth()==0||view.getHeight()==0)return;ArrayList<Integer> visible=new ArrayList<>();ArrayList<Point> pts=new ArrayList<>();for(int i=0;i<samples.size();i++){LatLng p=positions.get(samples.get(i));if(p==null)continue;Point pt=map.getProjection().toScreenLocation(p);if(pt!=null&&pt.x>=0&&pt.y>=0&&pt.x<view.getWidth()&&pt.y<view.getHeight()){visible.add(i);pts.add(pt);}}
        area.clear();for(int i:visible)area.add(samples.get(i));Collections.sort(area,NEWEST);if(expanded&&restorePosition<0){restorePosition=grid.getFirstVisiblePosition();View first=grid.getChildAt(0);restoreOffset=first==null?0:first.getTop()-grid.getPaddingTop();}renderList();int[] xs=new int[pts.size()],ys=new int[pts.size()];for(int i=0;i<pts.size();i++){xs[i]=pts.get(i).x;ys[i]=pts.get(i).y;}
        Set<String> retained=new HashSet<>();for(ScreenClusters.Cluster c:ScreenClusters.group(xs,ys,Math.max(1,dp(72)),100)){int[] ids=new int[c.members.length];for(int i=0;i<ids.length;i++)ids[i]=visible.get(c.members[i]);String key=Arrays.toString(ids);retained.add(key);LatLng center=map.getProjection().fromScreenLocation(new Point(c.x,c.y));if(center==null)continue;MarkerResource r=markers.get(key);if(r==null){Bitmap bm=markerBitmap(null,ids.length);BitmapDescriptor desc=BitmapDescriptorFactory.fromBitmap(bm);Bundle info=new Bundle();info.putIntArray("samples",ids);Marker m=(Marker)map.addOverlay(new MarkerOptions().position(center).icon(desc).anchor(.5f,.5f).extraInfo(info));r=new MarkerResource(m,desc,bm);markers.put(key,r);}else r.marker.setPosition(center);if(!r.hasCover&&!r.requested){ArrayList<SamplePhotoRepository.Sample> covers=new ArrayList<>();for(int id:ids)covers.add(samples.get(id));Collections.sort(covers,NEWEST);loadCover(key,r,covers,0,ids.length,generation);}}
        Iterator<Map.Entry<String,MarkerResource>> it=markers.entrySet().iterator();while(it.hasNext()){Map.Entry<String,MarkerResource> e=it.next();if(!retained.contains(e.getKey())){e.getValue().release();it.remove();}}}
    private void loadCover(String key,MarkerResource target,List<SamplePhotoRepository.Sample> covers,int index,int total,long ticket){if(index>=covers.size()){target.requested=false;return;}target.requested=true;SamplePhotoRepository.Sample s=covers.get(index);thumbnails.load(s.uri,s.mediaVersion,160,b->{if(ticket!=generation||markers.get(key)!=target)return;if(b==null){loadCover(key,target,covers,index+1,total,ticket);return;}Bitmap bm=markerBitmap(b,total);BitmapDescriptor desc=BitmapDescriptorFactory.fromBitmap(bm);target.marker.setIcon(desc);target.descriptor.recycle();target.bitmap.recycle();target.descriptor=desc;target.bitmap=bm;target.hasCover=true;target.requested=false;});}
    private Bitmap markerBitmap(Bitmap original,int count){int n=dp(48),border=dp(2);Bitmap out=Bitmap.createBitmap(n,n,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(out);Paint p=new Paint(3);p.setColor(-1);c.drawRoundRect(0,0,n,n,dp(9),dp(9),p);p.setColor(PLACEHOLDER);c.drawRoundRect(border,border,n-border,n-border,dp(7),dp(7),p);if(original!=null){float scale=Math.max((float)(n-2*border)/original.getWidth(),(float)(n-2*border)/original.getHeight());Matrix m=new Matrix();m.setScale(scale,scale);m.postTranslate((n-original.getWidth()*scale)/2,(n-original.getHeight()*scale)/2);BitmapShader shader=new BitmapShader(original,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);shader.setLocalMatrix(m);p.setShader(shader);c.drawRoundRect(border,border,n-border,n-border,dp(7),dp(7),p);p.setShader(null);}if(count>1){String text=count>999?"999+":Integer.toString(count);p.setTextSize(dp(10));p.setTypeface(Typeface.DEFAULT_BOLD);float w=Math.max(dp(19),p.measureText(text)+dp(8));p.setColor(0xe6263a49);c.drawRoundRect(n-w-dp(1),n-dp(17),n-dp(1),n-dp(1),dp(8),dp(8),p);p.setColor(-1);p.setTextAlign(Paint.Align.CENTER);c.drawText(text,n-w/2-dp(1),n-dp(5),p);}return out;}
    private void showCluster(int[] ids){if(ids.length==1){if(ids[0]>=0&&ids[0]<samples.size())openPreview(Collections.singletonList(samples.get(ids[0])),samples.get(ids[0]));return;}group.clear();for(int i:ids)if(i>=0&&i<samples.size())group.add(samples.get(i));Collections.sort(group,NEWEST);groupMode=true;expanded=true;grid.setSelection(0);renderList();}
    private void openPhoto(SamplePhotoRepository.Sample s){openPreview(groupMode?group:area,s);}
    private void openPreview(List<SamplePhotoRepository.Sample> source,SamplePhotoRepository.Sample selected){ArrayList<Bundle> snapshot=new ArrayList<>();int index=0;for(SamplePhotoRepository.Sample s:source){if(s==selected)index=snapshot.size();Bundle b=new Bundle();b.putParcelable("uri",s.uri);b.putParcelable("document",s.documentUri);b.putLong("version",s.mediaVersion);b.putLong("date",s.dateTaken==null?0:s.dateTaken);b.putBoolean("fallback",s.dateFallback);b.putString("path",s.relativePath);snapshot.add(b);}if(snapshot.isEmpty())return;startActivity(new Intent(this,PhotoPreviewActivity.class).putParcelableArrayListExtra("photos",snapshot).putExtra("index",index));}
    private final class AreaAdapter extends BaseAdapter{final ArrayList<SamplePhotoRepository.Sample> items=new ArrayList<>();public int getCount(){return items.size();}public SamplePhotoRepository.Sample getItem(int p){return items.get(p);}public long getItemId(int p){return p;}public View getView(int pos,View old,ViewGroup parent){LinearLayout cell=old instanceof LinearLayout?(LinearLayout)old:new LinearLayout(MainActivity.this);ImageView image;TextView date;if(cell.getChildCount()==0){cell.setOrientation(LinearLayout.VERTICAL);image=new ImageView(MainActivity.this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);cell.addView(image);date=label("",11,SECONDARY);cell.addView(date,new LinearLayout.LayoutParams(-1,dp(22)));}else{image=(ImageView)cell.getChildAt(0);date=(TextView)cell.getChildAt(1);}int width=Math.max(1,(parent.getWidth()-dp(32))/3);image.setLayoutParams(new LinearLayout.LayoutParams(-1,width));image.setBackgroundColor(PLACEHOLDER);image.setImageDrawable(null);SamplePhotoRepository.Sample s=getItem(pos);String tag=s.uri.toString()+"|"+s.mediaVersion+"|"+width;image.setTag(tag);date.setText(s.dateTaken==null?"日期未知":new SimpleDateFormat("M月d日",Locale.CHINA).format(new Date(s.dateTaken)));thumbnails.load(s.uri,s.mediaVersion,width,b->{if(tag.equals(image.getTag()))image.setImageBitmap(b);});return cell;}}
    private void clearMarkers(){handler.removeCallbacks(refresh);for(MarkerResource r:markers.values())r.release();markers.clear();positions.clear();}
    private void clearData(){generation++;if(pending!=null){pending.cancel();pending=null;}clearMarkers();thumbnails.clear();samples.clear();area.clear();group.clear();groupMode=false;loading=false;loadError=null;unmapped=withoutGps=unavailable=excluded=0;restorePosition=-1;restoreOffset=0;if(count!=null)count.setText("样本模式 · 0 张");renderList();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);if(map!=null)transientMapStatus=map.getMapStatus();out.putBoolean("privacyAccepted",privacyAccepted);out.putBoolean("expanded",expanded);out.putInt("scroll",grid==null?0:grid.getFirstVisiblePosition());View first=grid==null?null:grid.getChildAt(0);out.putInt("scrollOffset",first==null?0:first.getTop()-grid.getPaddingTop());out.putParcelableArrayList("chosen",new ArrayList<>(chosen));if(groupMode){ArrayList<Uri> members=new ArrayList<>();for(SamplePhotoRepository.Sample s:group)members.add(s.documentUri);out.putParcelableArrayList("group",members);}}
    private void updateNetworkStatus(){
        if(networkStatus==null)return;
        ConnectivityManager manager=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities capabilities=manager==null?null:manager.getNetworkCapabilities(manager.getActiveNetwork());
        boolean online=capabilities!=null&&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        networkStatus.setVisibility(online?View.GONE:View.VISIBLE);
    }
    @Override protected void onStart(){super.onStart();ConnectivityManager manager=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);if(manager!=null){manager.registerDefaultNetworkCallback(networkCallback);networkCallbackRegistered=true;}updateNetworkStatus();}
    @Override protected void onStop(){stopLocation();if(networkCallbackRegistered){ConnectivityManager manager=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);if(manager!=null)manager.unregisterNetworkCallback(networkCallback);networkCallbackRegistered=false;}super.onStop();}
    @Override protected void onResume(){super.onResume();if(view!=null)view.onResume();}
    @Override protected void onPause(){if(view!=null)view.onPause();super.onPause();}
    @Override protected void onDestroy(){clearData();chosen.clear();thumbnails.close();worker.shutdownNow();if(view!=null)view.onDestroy();super.onDestroy();}
    private final class PinIcon extends android.graphics.drawable.Drawable {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        public void draw(Canvas canvas){
            android.graphics.Rect bounds=getBounds();float x=bounds.exactCenterX(),top=bounds.top,w=bounds.width(),h=bounds.height();
            paint.setColor(0xffffffff);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.8f));
            Path pin=new Path();pin.moveTo(x,top+h*.94f);pin.cubicTo(x-w*.12f,top+h*.84f,x-w*.42f,top+h*.61f,x-w*.42f,top+h*.36f);
            pin.cubicTo(x-w*.42f,top-h*.10f,x+w*.42f,top-h*.10f,x+w*.42f,top+h*.36f);
            pin.cubicTo(x+w*.42f,top+h*.61f,x+w*.12f,top+h*.84f,x,top+h*.94f);pin.close();canvas.drawPath(pin,paint);
            paint.setStyle(Paint.Style.FILL);canvas.drawCircle(x,top+h*.36f,w*.11f,paint);
        }
        public void setAlpha(int alpha){} public void setColorFilter(android.graphics.ColorFilter filter){} public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }
    private final class FitIcon extends android.graphics.drawable.Drawable{private final Paint p=new Paint(3);public void draw(Canvas c){android.graphics.Rect b=getBounds();float x=b.exactCenterX(),y=b.exactCenterY(),r=Math.min(b.width(),b.height())*.32f;p.setColor(ACCENT);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setStrokeCap(Paint.Cap.ROUND);c.drawCircle(x,y,r,p);c.drawLine(x,b.top,x,y-r,p);c.drawLine(x,y+r,x,b.bottom,p);c.drawLine(b.left,y,x-r,y,p);c.drawLine(x+r,y,b.right,y,p);p.setStyle(Paint.Style.FILL);c.drawCircle(x,y,dp(2),p);}public void setAlpha(int a){}public void setColorFilter(android.graphics.ColorFilter f){}public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}}
}
