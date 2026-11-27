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
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.example.map002probe.MapDataRepository;
import org.example.map002probe.MediaStoreLookup;
import org.example.map002probe.PhotoTimeline;
import com.baidu.mapapi.CoordType;
import com.baidu.mapapi.SDKInitializer;
import com.baidu.mapapi.map.*;
import com.baidu.mapapi.model.LatLng;
import com.baidu.mapapi.utils.CoordinateConverter;
import com.baidu.location.BDAbstractLocationListener;
import com.baidu.location.BDLocation;
import com.baidu.location.LocationClient;
import com.baidu.location.LocationClientOption;

public class MainActivity extends Activity {
    private static final String MAP_SETTINGS="map_settings", CUSTOM_BAIDU_AK="baidu_ak", MAP_TYPE="map_type";
    private static final int MAX_COVER_ATTEMPTS=3;
    private static final long PROJECTION_BUDGET_NANOS=4_000_000;
    private int TEXT, SECONDARY, ACCENT, PLACEHOLDER, SURFACE, FLOATING, MAP_BACKGROUND, MENU_ICON;
    private boolean darkMode;
    private MapView view; private BaiduMap map; private FrameLayout root;
    private PhotoGalleryView panel;
    private PlaceSearch placeSearch; private Marker searchMarker; private BitmapDescriptor searchDescriptor; private Bitmap searchBitmap;
    private TextView count,networkStatus,mapTypeLabel; private LinearLayout mapTypeButton; private ImageView fit;
    private int mapType=BaiduMap.MAP_TYPE_NORMAL;
    private ThumbnailLoader thumbnails;
    private final ThreadPoolExecutor clusterWorker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1),new ThreadPoolExecutor.DiscardOldestPolicy());
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable viewportRefresh=this::queryViewport;
    private GalleryPhotoList group;
    private MapDataRepository.PhotoRequest photoRequest;
    private final Map<ClusterKey,MarkerResource> markers=new HashMap<>();
    private final Map<Long,MarkerResource> markerClicks=new HashMap<>();
    private long nextMarkerId, photoSelection;
    private boolean groupMode, oldestFirst, rebuiltAfterMediaChange, openingCluster;
    private boolean galleryBars;
    private int mapBarFlags,mapStatusColor,mapNavigationColor;
    private int restorePosition=-1, restoreOffset, bottomInset;
    private boolean networkCallbackRegistered;
    private Bundle saved;
    private static boolean coldStartLocationAttempted;
    private LocationClient locationClient; private BDAbstractLocationListener locationListener;
    private static MapStatus transientMapStatus;
    private static MapDataRepository experiment;
    private static int repositoryMediaMode=-1;
    private int mediaMode=0, pendingMediaMode=0;
    private TextView mediaFilter;
    private static PendingPhotoReturn pendingPhotoReturn;
    private PendingPhotoReturn retainedGallery;
    private static final class PendingPhotoReturn {
        final MapDataRepository repository;final long scopeToken;final GalleryPhotoList photos;
        final boolean oldestFirst;final int scroll,offset;
        PendingPhotoReturn(MapDataRepository repository,long scopeToken,GalleryPhotoList photos,boolean oldestFirst,int scroll,int offset){
            this.repository=repository;this.scopeToken=scopeToken;this.photos=photos;this.oldestFirst=oldestFirst;this.scroll=scroll;this.offset=offset;
        }
    }
    private boolean active, mapResumed, viewportDirty=true;
    private long displayedScope=-1;
    private TextView scopeCover;
    private final Runnable showScopeMessage=()->{
        if(active&&scopeCover!=null)scopeCover.setText("正在检查照片授权");
    };
    private volatile long viewportGeneration;
    private int projectionRetries;
    private Runnable projectionTask;
    private final Runnable checkLoad=this::checkRepository;
    private String scopeLabel="未授权";
    private String viewportPhase="尚未筛选";
    private String viewportTimings="尚无刷新耗时";
    private int viewportMatches, viewportLoaded, viewportCacheHits;
    private ViewportData viewport;
    private volatile ClusterProjection clusterProjection;
    private PanState panState;
    private boolean allowPanReuse;
    private static final class PanState {
        final ViewportData data; final LatLng anchor; final int width,height,marginX,marginY,clearance;
        final float zoom,rotate,tilt;
        PanState(ViewportData data,LatLng anchor,int width,int height,int marginX,int marginY,int clearance,MapStatus status){
            this.data=data;this.anchor=anchor;this.width=width;this.height=height;this.marginX=marginX;this.marginY=marginY;this.clearance=clearance;
            zoom=status.zoom;rotate=status.rotate;tilt=status.overlook;
        }
    }
    private static final class ViewportData {
        final ViewportBounds bounds;
        final long[] ids,versions;
        final LatLng[] positions;
        ViewportData(ViewportBounds bounds,int size){
            this.bounds=bounds;
            ids=new long[size];
            versions=new long[size];
            positions=new LatLng[size];
        }
    }
    private final Runnable locationTimeout=()->{stopLocation();Toast.makeText(this,"定位超时，请重试",Toast.LENGTH_SHORT).show();};
    private final ConnectivityManager.NetworkCallback networkCallback=new ConnectivityManager.NetworkCallback(){
        @Override public void onAvailable(Network network){handler.post(MainActivity.this::updateNetworkStatus);}
        @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities){handler.post(MainActivity.this::updateNetworkStatus);}
        @Override public void onLost(Network network){handler.postDelayed(MainActivity.this::updateNetworkStatus,500);}
    };
    private static final class ClusterKey {
        final long[] ids;
        final int hash;
        ClusterKey(long[] ids){this.ids=ids;hash=Arrays.hashCode(ids);}
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object other){
            return other instanceof ClusterKey && Arrays.equals(ids,((ClusterKey)other).ids);
        }
    }
    /** Identical coordinates have the same screen projection within one map frame. */
    private static final class ProjectionKey {
        final long latitude,longitude;
        ProjectionKey(LatLng point){latitude=Double.doubleToLongBits(point.latitude);longitude=Double.doubleToLongBits(point.longitude);}
        @Override public int hashCode(){return 31*Long.hashCode(latitude)+Long.hashCode(longitude);}
        @Override public boolean equals(Object other){return other instanceof ProjectionKey&&latitude==((ProjectionKey)other).latitude&&longitude==((ProjectionKey)other).longitude;}
    }
    private static final class RenderCluster {
        final ClusterKey key;
        final Point center;
        final LatLng singleton;
        RenderCluster(ClusterKey key,Point center,LatLng singleton){
            this.key=key;
            this.center=center;
            this.singleton=singleton;
        }
    }
    /** Last published projection; reuse only for an exact, uniform screen translation. */
    private static final class ClusterProjection {
        final ViewportData data; final int markerPixels,marginX,marginY; final int[] gaps,xs,ys,sources;
        final List<RenderCluster> clusters;
        ClusterProjection(ViewportData data,int markerPixels,int marginX,int marginY,int[] gaps,int[] xs,int[] ys,int[] sources,List<RenderCluster> clusters){
            this.data=data;this.markerPixels=markerPixels;this.marginX=marginX;this.marginY=marginY;this.gaps=gaps.clone();this.xs=xs;this.ys=ys;this.sources=sources;this.clusters=clusters;
        }
        List<RenderCluster> translated(ViewportData current,int marker,int mx,int my,int[] currentGaps,int[] x,int[] y,int[] source,int size){
            if(data!=current||markerPixels!=marker||marginX!=mx||marginY!=my||!Arrays.equals(gaps,currentGaps))return null;
            long[] offset=ScreenClusters.translation(xs,ys,sources,x,y,source,size);if(offset==null)return null;
            long dx=offset[0],dy=offset[1];
            List<RenderCluster> result=new ArrayList<>(clusters.size());
            for(RenderCluster cluster:clusters){long cx=cluster.center.x+dx,cy=cluster.center.y+dy;
                if(cx<Integer.MIN_VALUE||cx>Integer.MAX_VALUE||cy<Integer.MIN_VALUE||cy>Integer.MAX_VALUE)return null;
                result.add(new RenderCluster(cluster.key,new Point((int)cx,(int)cy),cluster.singleton));}
            return result;
        }
    }
    private static final class MarkerResource {
        final long clickId;
        final ClusterKey key;
        final Marker marker;
        BitmapDescriptor descriptor;
        Bitmap bitmap;
        ThumbnailLoader.Request coverRequest;
        Runnable coverRetry;
        int coverAttempt;
        boolean hasCover;
        MarkerResource(long clickId,ClusterKey key,Marker marker,BitmapDescriptor descriptor,Bitmap bitmap){
            this.clickId=clickId;
            this.key=key;
            this.marker=marker;
            this.descriptor=descriptor;
            this.bitmap=bitmap;
        }
        void release(){marker.remove();descriptor.recycle();bitmap.recycle();}
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private android.graphics.drawable.Drawable floatingBackground(float radius){return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(getColor(R.color.gallery_ripple)),shape(FLOATING,radius),shape(Color.WHITE,radius));}
    private void addMapButtonFeedback(View button){
        button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf((ACCENT&0x00ffffff)|0x55000000),shape(FLOATING,24),shape(Color.WHITE,24)));
        button.setOnTouchListener((v,event)->{
            if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN)v.animate().scaleX(.92f).scaleY(.92f).setDuration(90).start();
            else if(event.getActionMasked()==android.view.MotionEvent.ACTION_UP||event.getActionMasked()==android.view.MotionEvent.ACTION_CANCEL)v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            return false;
        });
    }
    private TextView label(String text,int size,int color){TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
    @Override protected void attachBaseContext(Context base){super.attachBaseContext(base);Appearance.applyOverride(this,base);}
    @Override public void onCreate(Bundle state){super.onCreate(state);saved=state;darkMode=(getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
        TEXT=getColor(R.color.app_text);SECONDARY=getColor(R.color.app_secondary);ACCENT=getColor(R.color.app_accent);PLACEHOLDER=getColor(R.color.app_placeholder);SURFACE=getColor(R.color.app_surface);FLOATING=getColor(R.color.app_floating);MAP_BACKGROUND=getColor(R.color.app_map_background);MENU_ICON=getColor(R.color.app_menu_icon);
        retainedGallery=(PendingPhotoReturn)getLastNonConfigurationInstance();
        mediaMode=getPreferences(MODE_PRIVATE).getInt("media_mode",0);if(mediaMode<0||mediaMode>2)mediaMode=0;
        thumbnails=new ThumbnailLoader(getContentResolver());
        TextView waiting=label("等待隐私选择",14,TEXT);setContentView(waiting);
        if(PrivacyConsent.isCurrent(getPreferences(MODE_PRIVATE).getInt(PrivacyConsent.KEY,0))){initializeMap();return;}
        AlertDialog privacy=new AlertDialog.Builder(this).setTitle("百度地图与定位隐私说明").setMessage("图迹使用百度地图 SDK。每次冷启动时及点击当前位置后，图迹会请求前台位置权限，并使用百度定位 SDK 获取当前位置，SDK 可能读取 Wi-Fi 状态辅助定位；百度按其隐私政策处理定位请求。图迹不进行后台持续定位、不建立位置历史、不上传位置到图迹自有服务。拒绝定位权限不影响地图和照片浏览。请先阅读百度隐私政策；不同意时不初始化 SDK。")
            .setNeutralButton("查看隐私政策",null)
            .setNegativeButton("不同意",(d,w)->finish()).setPositiveButton("同意并打开",(d,w)->{if(!getPreferences(MODE_PRIVATE).edit().putInt(PrivacyConsent.KEY,PrivacyConsent.VERSION).commit()){Toast.makeText(this,"无法保存隐私选择，请重试",Toast.LENGTH_LONG).show();finish();return;}initializeMap();}).setOnCancelListener(d->finish()).create();
        privacy.setOnShowListener(d->privacy.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{
            try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://map.baidu.com/zt/client/privacy/index.html")));}
            catch(android.content.ActivityNotFoundException e){Toast.makeText(this,"找不到可以打开隐私政策的浏览器",Toast.LENGTH_SHORT).show();}
        }));
        privacy.show();
    }
    private void initializeMap(){try {
        String ak=effectiveBaiduAk();if(ak!=null){SDKInitializer.setApiKey(ak);LocationClient.setKey(ak);}
        SDKInitializer.setAgreePrivacy(getApplicationContext(),true);SDKInitializer.initialize(getApplicationContext());SDKInitializer.setCoordType(CoordType.BD09LL);
        BaiduMapOptions options=new BaiduMapOptions();
        if(saved!=null&&transientMapStatus!=null)options.mapStatus(transientMapStatus);
        root=new FrameLayout(this);root.setBackgroundColor(MAP_BACKGROUND);view=new MapView(this,options);view.showZoomControls(false);root.addView(view);setContentView(root);resumeMapView();map=view.getMap();mapType=readMapType();applyMapType();view.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol||b-t!=ob-ot)schedule();});
        map.setOnMarkerClickListener(m->{
            Bundle info=m.getExtraInfo();
            MarkerResource resource=info==null?null:markerClicks.get(info.getLong("markerId"));
            if(resource!=null)openCluster(resource);
            return true;
        });
        map.setOnMapStatusChangeListener(new BaiduMap.OnMapStatusChangeListener(){public void onMapStatusChangeStart(MapStatus s){cancelRequests();}public void onMapStatusChangeStart(MapStatus s,int reason){cancelRequests();}public void onMapStatusChange(MapStatus s){}public void onMapStatusChangeFinish(MapStatus s){schedule(true);}});
        buildUi();
        if(mediaMode!=0&&!hasVideoAccess()){mediaMode=0;getPreferences(MODE_PRIVATE).edit().putInt("media_mode",0).apply();}
        updateMediaFilter();
        if(experiment==null && getPreferences(MODE_PRIVATE).getBoolean(confirmationKey(),false) && supportedGallery()){experiment=createGallery();experiment.setMediaMode(mediaMode);repositoryMediaMode=mediaMode;}
        else if(experiment!=null&&repositoryMediaMode!=mediaMode){experiment.stopAndClear();experiment.setMediaMode(mediaMode);repositoryMediaMode=mediaMode;}
        if(saved!=null)oldestFirst=saved.getBoolean("oldestFirst");
        if(active)resumeExperiment();
        schedule();
        locateOnColdStart();
    }catch(RuntimeException e){LinearLayout failure=new LinearLayout(this);failure.setOrientation(LinearLayout.VERTICAL);failure.setGravity(Gravity.CENTER);TextView message=label("地图加载失败，请检查百度地图 AK",14,SECONDARY);message.setGravity(Gravity.CENTER);failure.addView(message);Button settings=new Button(this);settings.setText("设置百度地图 AK");settings.setOnClickListener(v->showBaiduAkSettings());failure.addView(settings);setContentView(failure);}}
    private void buildUi(){
        placeSearch=new PlaceSearch(this,root,new PlaceSearch.Host(){
            public LatLng center(){return map.getMapStatus().target;}
            public void select(LatLng point,float zoom){showSearchMarker(point);map.animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(point,zoom));}
        },TEXT,SECONDARY,SURFACE,ACCENT);
        TextView search=label("⌕  搜索地点，如：杭州西湖",15,SECONDARY);search.setPadding(dp(16),0,dp(12),0);search.setBackground(floatingBackground(16));search.setElevation(dp(3));search.setContentDescription("搜索地点");search.setOnClickListener(v->placeSearch.open());
        FrameLayout.LayoutParams searchParams=new FrameLayout.LayoutParams(-1,dp(48),Gravity.TOP|Gravity.START);searchParams.setMargins(dp(16),dp(12),dp(76),0);root.addView(search,searchParams);
        count=label("",13,ACCENT);
        count.setMinHeight(dp(48));
        count.setPadding(dp(12),dp(6),dp(12),dp(6));
        count.setBackground(shape(FLOATING,16));
        count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams statusParams=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.START);
        statusParams.setMarginStart(dp(16));statusParams.setMarginEnd(dp(76));statusParams.topMargin=dp(72);
        root.addView(count,statusParams);
        mediaFilter=label("",13,ACCENT);mediaFilter.setGravity(Gravity.CENTER);mediaFilter.setPadding(dp(12),0,dp(12),0);mediaFilter.setBackground(floatingBackground(16));mediaFilter.setElevation(dp(3));
        FrameLayout.LayoutParams filterParams=new FrameLayout.LayoutParams(-2,dp(40),Gravity.TOP|Gravity.RIGHT);filterParams.setMargins(0,dp(72),dp(16),0);root.addView(mediaFilter,filterParams);updateMediaFilter();mediaFilter.setOnClickListener(v->chooseMediaMode());
        showInitialGalleryStatus();
        TextView menu=label("•••",18,MENU_ICON);menu.setGravity(Gravity.CENTER);menu.setContentDescription("更多菜单");menu.setBackground(floatingBackground(24));menu.setElevation(dp(3));menu.setOnClickListener(v->showMenu(menu));
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP|Gravity.RIGHT);bp.setMargins(0,dp(12),dp(16),0);root.addView(menu,bp);
        networkStatus=label("网络不可用，地图可能无法加载",12,SECONDARY);networkStatus.setPadding(dp(12),0,dp(12),0);networkStatus.setBackground(shape(FLOATING,12));
        FrameLayout.LayoutParams np=new FrameLayout.LayoutParams(-2,dp(36),Gravity.TOP|Gravity.LEFT);np.setMargins(dp(16),dp(130),0,0);root.addView(networkStatus,np);updateNetworkStatus();
        fit=new ImageView(this);fit.setImageDrawable(new FitIcon());fit.setPadding(dp(12),dp(12),dp(12),dp(12));addMapButtonFeedback(fit);fit.setElevation(dp(3));fit.setContentDescription("定位当前位置");fit.setOnClickListener(v->{map.setMapStatus(MapStatusUpdateFactory.newMapStatus(new MapStatus.Builder(map.getMapStatus()).rotate(0).build()));requestCurrentLocation();});root.addView(fit,new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.RIGHT|Gravity.BOTTOM));
        mapTypeButton=new LinearLayout(this);mapTypeButton.setOrientation(LinearLayout.VERTICAL);mapTypeButton.setGravity(Gravity.CENTER);
        ImageView layerIcon=new ImageView(this);layerIcon.setImageDrawable(new LayerIcon());mapTypeButton.addView(layerIcon,new LinearLayout.LayoutParams(dp(18),dp(18)));
        mapTypeLabel=label("",11,MENU_ICON);mapTypeLabel.setGravity(Gravity.CENTER);mapTypeLabel.setIncludeFontPadding(false);mapTypeButton.addView(mapTypeLabel,new LinearLayout.LayoutParams(-2,-2));
        addMapButtonFeedback(mapTypeButton);mapTypeButton.setElevation(dp(3));mapTypeButton.setOnClickListener(v->toggleMapType());
        root.addView(mapTypeButton,new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.RIGHT|Gravity.BOTTOM));updateMapTypeButton();
        panel=new PhotoGalleryView(this,thumbnails,this::canLoadGalleryImages,
            this::closePhotoPanel,this::setPhotoOrder,this::openPhoto);
        root.addView(panel,new FrameLayout.LayoutParams(-1,-1));
        root.setOnApplyWindowInsetsListener((v,insets)->{
            int safe=android.os.Build.VERSION.SDK_INT>=30?insets.getInsets(WindowInsets.Type.systemGestures()).bottom:insets.getSystemWindowInsetBottom();
            if(bottomInset!=safe){
                bottomInset=safe;
                panel.setPadding(0,0,0,safe);
                updatePanel();
            }
            return insets;
        });
        renderList();
    }
    private int readMapType(){
        try{int value=getSharedPreferences(MAP_SETTINGS,MODE_PRIVATE).getInt(MAP_TYPE,BaiduMap.MAP_TYPE_NORMAL);return value==BaiduMap.MAP_TYPE_SATELLITE?value:BaiduMap.MAP_TYPE_NORMAL;}
        catch(ClassCastException invalid){return BaiduMap.MAP_TYPE_NORMAL;}
    }
    private void applyMapType(){
        map.setMapType(mapType);
        map.setMapThemeMode(mapType==BaiduMap.MAP_TYPE_NORMAL&&darkMode?BMThemeMode.MapThemeModeDark:BMThemeMode.MapThemeModeDefault);
    }
    private void toggleMapType(){
        mapType=mapType==BaiduMap.MAP_TYPE_NORMAL?BaiduMap.MAP_TYPE_SATELLITE:BaiduMap.MAP_TYPE_NORMAL;
        applyMapType();updateMapTypeButton();getSharedPreferences(MAP_SETTINGS,MODE_PRIVATE).edit().putInt(MAP_TYPE,mapType).apply();
    }
    private void updateMapTypeButton(){
        if(mapTypeButton==null)return;
        String target=mapType==BaiduMap.MAP_TYPE_NORMAL?"卫星":"标准";
        mapTypeLabel.setText(target);mapTypeButton.setContentDescription("切换到"+target+"地图");
    }
    private void setPhotoOrder(boolean oldest){
        if(oldestFirst==oldest||group==null)return;
        oldestFirst=oldest;
        group=group.ordered(oldest);
        restorePosition=-1;
        renderList();
        panel.scrollToPhoto(0,0);
    }
    private void showMenu(View anchor){
        PopupMenu p=new PopupMenu(this,anchor);
        android.view.MenuItem night=p.getMenu().add("暗夜模式");night.setCheckable(true);night.setChecked(darkMode);night.setOnMenuItemClickListener(i->{Appearance.setManual(this,!darkMode);recreate();return true;});
        p.getMenu().add("刷新照片数据").setOnMenuItemClickListener(i->{if(experiment==null)enableExperiment();else reloadGallery();return true;});
        p.getMenu().add("照片 GPS 情况").setOnMenuItemClickListener(i->{showPhotoLocationSummary();return true;});
        p.getMenu().add("百度地图 AK").setOnMenuItemClickListener(i->{showBaiduAkSettings();return true;});
        p.getMenu().add("隐私说明").setOnMenuItemClickListener(i->{new AlertDialog.Builder(this).setMessage("照片、视频及其已有位置仅在本机只读处理，媒体索引仅保存在内存中；图迹不上传照片、视频、GPS 或媒体索引。图库仅访问当前授权的主卷媒体。冷启动时及点击定位后使用百度定位 SDK，可能读取 Wi-Fi 状态；不后台持续定位、不保存位置历史。跨境地区及海外坐标尚待验证。").setPositiveButton("知道了",null).show();return true;});
        p.getMenu().add("关于图迹 for VIVO").setOnMenuItemClickListener(i->{showAbout();return true;});
        if(BuildConfig.PERSONAL_DEVICE_RELEASE){
            p.getMenu().add("启用图库地图").setOnMenuItemClickListener(i->{enableExperiment();return true;});
            p.getMenu().add("修改照片授权").setOnMenuItemClickListener(i->{openPhotoPermissionSettings();return true;});
        }
        if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0){
            p.getMenu().add("启用图库地图").setOnMenuItemClickListener(i->{enableExperiment();return true;});
            p.getMenu().add("关闭全图库实验模式").setOnMenuItemClickListener(i->{getPreferences(MODE_PRIVATE).edit().remove("experiment_confirmed").apply();if(experiment!=null){experiment.close();experiment=null;}pendingPhotoReturn=null;clearData();return true;});
            p.getMenu().add("修改照片授权").setOnMenuItemClickListener(i->{openPhotoPermissionSettings();return true;});
            p.getMenu().add("数据诊断").setOnMenuItemClickListener(i->{showDiagnostics();return true;});
            p.getMenu().add("视频 GPS 验证").setOnMenuItemClickListener(i->{startActivity(new android.content.Intent().setClassName(this, "io.github.szzeng.mapalbum.VideoCheckActivity"));return true;});
        }
        p.show();
    }
    private void showAbout(){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24),0,dp(24),0);
        TextView details=label("个人使用版  " + BuildConfig.VERSION_NAME + "\n编译日期  " + BuildConfig.BUILD_DATE + "\n作者  sz_zeng(wx)",14,SECONDARY);
        details.setLineSpacing(dp(6),1f);
        LinearLayout.LayoutParams detailsLayout=new LinearLayout.LayoutParams(-1,-2);
        box.addView(details,detailsLayout);
        TextView github=label("本项目GitHub",14,ACCENT);
        github.setMinHeight(dp(48));
        github.setOnClickListener(v->{
            try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/szzeng/x300pro-map")));}
            catch(android.content.ActivityNotFoundException unavailable){
                Toast.makeText(this,"找不到可打开 GitHub 的应用",Toast.LENGTH_SHORT).show();
            }
        });
        box.addView(github);
        new AlertDialog.Builder(this).setTitle("关于图迹 for VIVO").setView(box)
            .setPositiveButton("知道了",null).show();
    }
    private String effectiveBaiduAk(){
        String custom=getSharedPreferences(MAP_SETTINGS,MODE_PRIVATE).getString(CUSTOM_BAIDU_AK,null);
        if(custom!=null&&!custom.isEmpty())return custom;
        try {android.content.pm.ApplicationInfo info=getPackageManager().getApplicationInfo(getPackageName(),android.content.pm.PackageManager.GET_META_DATA);return info.metaData==null?null:info.metaData.getString("com.baidu.lbsapi.API_KEY");}
        catch(android.content.pm.PackageManager.NameNotFoundException e){return null;}
    }
    private void showBaiduAkSettings(){
        EditText input=new EditText(this);input.setSingleLine(true);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);input.setHint("输入百度地图 AK");
        String custom=getSharedPreferences(MAP_SETTINGS,MODE_PRIVATE).getString(CUSTOM_BAIDU_AK,"");input.setText(custom);input.setSelection(input.length());
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(24),0,dp(24),0);TextView note=label("AK 用于地图和定位，需在百度控制台绑定图迹的包名和签名。",14,SECONDARY);box.addView(note);box.addView(input);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("百度地图 AK").setView(box).setNegativeButton("取消",null).setNeutralButton("恢复内置 AK",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(ignored->{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                String value=input.getText().toString().trim();
                if(value.isEmpty()||value.length()>256||value.matches(".*\\s+.*")){input.setError("请输入有效的 AK，不能包含空格");return;}
                if(!getSharedPreferences(MAP_SETTINGS,MODE_PRIVATE).edit().putString(CUSTOM_BAIDU_AK,value).commit()){input.setError("保存失败，请重试");return;}
                dialog.dismiss();reloadBaiduAk();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{
                if(!getSharedPreferences(MAP_SETTINGS,MODE_PRIVATE).edit().remove(CUSTOM_BAIDU_AK).commit()){input.setError("保存失败，请重试");return;}
                dialog.dismiss();reloadBaiduAk();
            });
        });dialog.show();
    }
    private void reloadBaiduAk(){
        stopLocation();String ak=effectiveBaiduAk();if(ak!=null){SDKInitializer.setApiKey(ak);LocationClient.setKey(ak);}
        Toast.makeText(this,"已保存，正在重新加载地图；AK 能否使用取决于百度鉴权",Toast.LENGTH_LONG).show();recreate();
    }
    private void showPhotoLocationSummary(){
        String summary=experiment==null||mediaMode!=0?null:experiment.photoLocationSummary();
        if(mediaMode!=0)summary="照片 GPS 统计请切换到照片模式后查看。";
        if(summary==null)summary=count==null?"请先启用图库地图并授权照片访问。":count.getText().toString()+"\n请在照片数据读取完成后查看。";
        new AlertDialog.Builder(this).setTitle("照片 GPS 情况").setMessage(summary).setPositiveButton("知道了",null).show();
    }
    private void openPhotoPermissionSettings(){
        new AlertDialog.Builder(this).setTitle("修改照片授权").setMessage("在系统设置的权限页面中，可以调整图迹可访问的照片范围。返回后图迹会重新检查授权。")
            .setNegativeButton("取消",null).setPositiveButton("打开设置",(d,w)->startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))).show();
    }
    private String confirmationKey(){return BuildConfig.PERSONAL_DEVICE_RELEASE?"personal_gallery_confirmed":"experiment_confirmed";}
    private boolean supportedGallery(){return BuildConfig.PERSONAL_DEVICE_RELEASE?MapDataRepository.isSupportedPersonalRelease(this):MapDataRepository.isSupportedDevice(this);}
    private MapDataRepository createGallery(){return BuildConfig.PERSONAL_DEVICE_RELEASE?MapDataRepository.forConfirmedPersonalRelease(getApplicationContext(),true):MapDataRepository.forConfirmedExperiment(getApplicationContext(),true);}
    private boolean hasVideoAccess(){
        if(android.os.Build.VERSION.SDK_INT>=34)return checkSelfPermission("android.permission.READ_MEDIA_VIDEO")==android.content.pm.PackageManager.PERMISSION_GRANTED||checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")==android.content.pm.PackageManager.PERMISSION_GRANTED;
        if(android.os.Build.VERSION.SDK_INT>=33)return checkSelfPermission("android.permission.READ_MEDIA_VIDEO")==android.content.pm.PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE")==android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
    private void updateMediaFilter(){if(mediaFilter!=null)mediaFilter.setText(mediaMode==0?"照片 ▾":mediaMode==1?"全部 ▾":"视频 ▾");}
    private void chooseMediaMode(){
        new AlertDialog.Builder(this).setTitle("地图显示").setSingleChoiceItems(new String[]{"照片","全部","视频"},mediaMode,(dialog,which)->{
            dialog.dismiss();if(which==mediaMode)return;
            if(which!=0&&android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.READ_MEDIA_VIDEO")!=android.content.pm.PackageManager.PERMISSION_GRANTED){
                pendingMediaMode=which;
                if(android.os.Build.VERSION.SDK_INT>=34)requestPermissions(new String[]{"android.permission.READ_MEDIA_VIDEO","android.permission.READ_MEDIA_VISUAL_USER_SELECTED"},104);
                else requestPermissions(new String[]{"android.permission.READ_MEDIA_VIDEO"},104);
            }else applyMediaMode(which);
        }).setNegativeButton("取消",null).show();
    }
    private void applyMediaMode(int mode){
        mediaMode=mode;getPreferences(MODE_PRIVATE).edit().putInt("media_mode",mode).apply();updateMediaFilter();
        if(experiment!=null){experiment.stopAndClear();clearData();experiment.setMediaMode(mode);repositoryMediaMode=mode;resumeExperiment();}
    }
    private void showGalleryStatus(String message,Runnable action){
        if(count==null)return;
        if(message!=null && !message.contentEquals(count.getText()))count.setText(message);
        count.setVisibility(message==null?View.GONE:View.VISIBLE);
        count.setOnClickListener(action==null?null:v->action.run());
        count.setClickable(action!=null);
        count.setFocusable(action!=null);
    }
    private void showInitialGalleryStatus(){
        if(supportedGallery())showGalleryStatus("启用图库地图",this::enableExperiment);
        else showGalleryStatus(null,null);
    }
    private void enableExperiment(){
        if(!supportedGallery()){new AlertDialog.Builder(this).setMessage("图库地图目前仅向 vivo 手机开放，且需要符合当前构建的安全限制。").setPositiveButton("知道了",null).show();return;}
        new AlertDialog.Builder(this).setTitle("启用图库地图")
            .setMessage("图迹 for VIVO 专为 vivo 手机设计。\n\n读取手机已授权的照片及其已有位置信息，在地图上展示。仅访问主卷，读取结果保存在内存中；授权范围变化时会重新检查。\n\n已在 vivo X300 Pro 上完成实机验证，其他 vivo 机型可以尝试使用，实际兼容性取决于手机系统及图库接口。")
            .setNegativeButton("取消",null).setPositiveButton("确认启用",(d,w)->{
                getPreferences(MODE_PRIVATE).edit().putBoolean(confirmationKey(),true).apply();
                if(experiment==null){experiment=createGallery();experiment.setMediaMode(mediaMode);repositoryMediaMode=mediaMode;}
                requestPhotoAccess();
            }).show();
    }
    static boolean hasPhotoAccess(Context context){
        if(android.os.Build.VERSION.SDK_INT>=34)return context.checkSelfPermission("android.permission.READ_MEDIA_IMAGES")==android.content.pm.PackageManager.PERMISSION_GRANTED || context.checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")==android.content.pm.PackageManager.PERMISSION_GRANTED;
        if(android.os.Build.VERSION.SDK_INT>=33)return context.checkSelfPermission("android.permission.READ_MEDIA_IMAGES")==android.content.pm.PackageManager.PERMISSION_GRANTED;
        return context.checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE")==android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
    private void requestPhotoAccess(){
        if(hasPhotoAccess(this)){resumeExperiment();return;}
        if(android.os.Build.VERSION.SDK_INT>=34)requestPermissions(new String[]{"android.permission.READ_MEDIA_IMAGES","android.permission.READ_MEDIA_VISUAL_USER_SELECTED"},103);
        else if(android.os.Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{"android.permission.READ_MEDIA_IMAGES"},103);
        else requestPermissions(new String[]{"android.permission.READ_EXTERNAL_STORAGE"},103);
    }
    private void resumeExperiment(){
        if(!active||map==null||experiment==null)return;
        if(mediaMode!=0&&!hasVideoAccess()){applyMediaMode(0);return;}
        if(!hasPhotoAccess(this)){
            pendingPhotoReturn=null;
            retainedGallery=null;
            experiment.stopAndClear();
            clearData();
            hideScopeCover();
            scopeLabel="未授权";
            showGalleryStatus("照片未授权 · 调整权限",this::openPhotoPermissionSettings);
            return;
        }
        scopeLabel=android.os.Build.VERSION.SDK_INT>=34 && checkSelfPermission("android.permission.READ_MEDIA_IMAGES")!=android.content.pm.PackageManager.PERMISSION_GRANTED?"部分照片":"完整照片";
        if(displayedScope>=0)showScopeCover();
        showGalleryStatus("正在检查照片授权",null);
        MapDataRepository repository=experiment;
        repository.resumeChecked(state->handler.post(()->{if(active&&experiment==repository)checkRepository();}));
        handler.removeCallbacks(checkLoad);handler.postDelayed(checkLoad,200);
    }
    private void reloadGallery(){
        rebuiltAfterMediaChange=false;
        experiment.stopAndClear();
        resumeExperiment();
    }
    private void checkRepository(){
        if(!active||experiment==null||count==null)return;
        MapDataRepository.State state=experiment.state();
        handler.removeCallbacks(checkLoad);
        if(state==MapDataRepository.State.ACCESS_DENIED&&mediaMode!=0&&hasPhotoAccess(this)){
            Toast.makeText(this,"视频访问不可用，已切回照片",Toast.LENGTH_SHORT).show();applyMediaMode(0);return;
        }
        if(state==MapDataRepository.State.READY){
            if(mediaMode!=0&&android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.READ_MEDIA_VIDEO")!=android.content.pm.PackageManager.PERMISSION_GRANTED&&experiment.visibleVideoCount()==0){
                Toast.makeText(this,"没有可访问的视频，已切回照片",Toast.LENGTH_SHORT).show();applyMediaMode(0);return;
            }
            long token=experiment.scopeToken();
            if(token<0)return;
            if(displayedScope!=token){clearData();displayedScope=token;}
            restorePreviewReturn();
            hideScopeCover();
            if(viewportDirty||viewport==null){
                showGalleryStatus("正在查找此范围的照片",null);
                schedule();
            }else{
                showViewportStatus(markers.isEmpty());
                if(groupMode)restartVisibleImages();else loadMarkerCovers();
            }
            return;
        }
        if(state==MapDataRepository.State.LOADING||state==MapDataRepository.State.IDLE){
            showGalleryStatus("正在读取照片位置",null);
            if(scopeCover!=null)scopeCover.setText("正在读取照片位置");
            handler.postDelayed(checkLoad,300);
            return;
        }
        pendingPhotoReturn=null;
        retainedGallery=null;
        clearData();
        hideScopeCover();
        if(state==MapDataRepository.State.SCOPE_CHANGED && !rebuiltAfterMediaChange){
            rebuiltAfterMediaChange=true;
            resumeExperiment();
            return;
        }
        switch(state){
            case CAPACITY_EXCEEDED: showGalleryStatus("授权媒体超过 10 万条 · 调整授权",this::openPhotoPermissionSettings);break;
            case PROVIDER_INCOMPATIBLE: showGalleryStatus("图库接口不可用或不兼容 · 查看详情",()->new AlertDialog.Builder(this).setTitle("图库接口不兼容").setMessage("无法读取 vivo 图库的位置接口，可能是接口不存在、访问被拒绝、字段或照片关联不兼容。请检查系统和图库权限后重试；当前设备可能暂不支持图库地图。") .setNegativeButton("关闭",null).setPositiveButton("重试",(d,w)->reloadGallery()).show());break;
            case ACCESS_DENIED: showGalleryStatus("照片访问被拒绝 · 调整权限",this::openPhotoPermissionSettings);break;
            case VOLUME_UNSUPPORTED: showGalleryStatus("当前存储卷不符合主卷限制",null);break;
            case IDENTITY_UNVERIFIED: showGalleryStatus("设备未通过照片地图校验",null);break;
            default: showGalleryStatus("照片位置读取失败 · 重试",this::reloadGallery);
        }
    }
    private void showViewportStatus(boolean emptyViewport){
        if(!emptyViewport)showGalleryStatus(null,null);
        else if(experiment.validCount()==0){
            showGalleryStatus(mediaMode==0?"授权照片中没有可用的位置 · 查看详情":"授权媒体中没有可用的位置",mediaMode==0?this::showPhotoLocationSummary:null);
        }else showGalleryStatus(mediaMode==0?"此范围没有照片":"此范围没有媒体",null);
    }
    private void restorePreviewReturn(){
        PendingPhotoReturn pending=pendingPhotoReturn!=null?pendingPhotoReturn:retainedGallery;
        pendingPhotoReturn=null;
        retainedGallery=null;
        if(pending==null)return;
        if(pending.repository!=experiment||pending.scopeToken<0||pending.scopeToken!=experiment.scopeToken()){
            Toast.makeText(this,"照片授权范围已变化，请重新选择照片",Toast.LENGTH_LONG).show();return;
        }
        if(groupMode)return;
        group=pending.photos;oldestFirst=pending.oldestFirst;groupMode=true;
        restorePosition=pending.scroll;restoreOffset=pending.offset;renderList();
    }
    private void showDiagnostics(){
        String message="数据模式："+(experiment==null?"未启用":"主卷实验")+"\n授权范围："+scopeLabel+"\n"+(experiment==null?"身份：未验证":experiment.diagnostics())+"\n视野状态："+viewportPhase+" · 匹配 "+viewportMatches+" · 已读取 "+viewportLoaded+" · 地图标记 "+markers.size()+" · 视野复用 "+viewportCacheHits+"\n"+viewportTimings+"\n"+thumbnails.diagnosticCounts();
        new AlertDialog.Builder(this).setTitle("数据诊断").setMessage(message).setNegativeButton("关闭",null).setPositiveButton("复制",(d,w)->{android.content.ClipboardManager clip=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(clip!=null)clip.setPrimaryClip(android.content.ClipData.newPlainText("图迹诊断",message));}).show();
    }
    private void updatePanel(){
        if(panel==null)return;
        panel.setVisibility(groupMode?View.VISIBLE:View.GONE);
        FrameLayout.LayoutParams position=(FrameLayout.LayoutParams)fit.getLayoutParams();
        int margin=bottomInset+dp(32);
        if(position.rightMargin!=dp(16)||position.bottomMargin!=margin){
            position.rightMargin=dp(16);
            position.bottomMargin=margin;
            fit.setLayoutParams(position);
        }
        fit.setVisibility(groupMode?View.GONE:View.VISIBLE);
        FrameLayout.LayoutParams typePosition=(FrameLayout.LayoutParams)mapTypeButton.getLayoutParams();
        if(typePosition.rightMargin!=dp(16)||typePosition.bottomMargin!=margin+dp(58)){
            typePosition.rightMargin=dp(16);typePosition.bottomMargin=margin+dp(58);mapTypeButton.setLayoutParams(typePosition);
        }
        mapTypeButton.setVisibility(groupMode?View.GONE:View.VISIBLE);
        updateGalleryBars();
    }
    private void updateGalleryBars(){
        if(galleryBars==groupMode)return;
        Window window=getWindow();
        View decor=window.getDecorView();
        if(groupMode){
            mapBarFlags=decor.getSystemUiVisibility();
            mapStatusColor=window.getStatusBarColor();
            mapNavigationColor=window.getNavigationBarColor();
            int lightFlags=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility(darkMode?mapBarFlags&~lightFlags:mapBarFlags|lightFlags);
            window.setStatusBarColor(getColor(R.color.gallery_header));
            window.setNavigationBarColor(getColor(R.color.gallery_background));
        }else{
            decor.setSystemUiVisibility(mapBarFlags);
            window.setStatusBarColor(mapStatusColor);
            window.setNavigationBarColor(mapNavigationColor);
        }
        galleryBars=groupMode;
    }
    private void closePhotoPanel(){cancelPhotoLoad();clearVisibleImages();groupMode=false;group=null;renderList();loadMarkerCovers();}
    private long lastExitBack=-1;
    private boolean exitRequested;
    @Override public void onBackPressed(){
        if(placeSearch!=null&&placeSearch.isOpen()){placeSearch.close();return;}
        if(groupMode){closePhotoPanel();return;}
        long now=SystemClock.elapsedRealtime();
        if(lastExitBack>=0&&now-lastExitBack<=2000){lastExitBack=-1;exitRequested=true;finishAndRemoveTask();return;}
        lastExitBack=now;
        Toast.makeText(this,"再按一次退出应用",Toast.LENGTH_SHORT).show();
    }
    private void renderList(){
        if(panel==null)return;
        panel.setPhotos(group,oldestFirst);
        updatePanel();
        if(restorePosition>=0&&groupMode&&group!=null&&!group.isEmpty()){
            panel.scrollToPhoto(restorePosition,restoreOffset);
            restorePosition=-1;
            restoreOffset=0;
        }
    }
    private void clearSearchMarker(){if(searchMarker!=null){searchMarker.remove();searchMarker=null;}if(searchDescriptor!=null){searchDescriptor.recycle();searchDescriptor=null;}if(searchBitmap!=null){searchBitmap.recycle();searchBitmap=null;}}
    private void showSearchMarker(LatLng point){
        clearSearchMarker();int size=dp(40);searchBitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(searchBitmap);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(ACCENT);Path pin=new Path();pin.moveTo(size*.5f,size*.95f);pin.cubicTo(size*.38f,size*.75f,size*.12f,size*.55f,size*.12f,size*.38f);pin.cubicTo(size*.12f,-size*.08f,size*.88f,-size*.08f,size*.88f,size*.38f);pin.cubicTo(size*.88f,size*.55f,size*.62f,size*.75f,size*.5f,size*.95f);canvas.drawPath(pin,paint);paint.setColor(Color.WHITE);canvas.drawCircle(size*.5f,size*.37f,size*.13f,paint);searchDescriptor=BitmapDescriptorFactory.fromBitmap(searchBitmap);searchMarker=(Marker)map.addOverlay(new MarkerOptions().position(point).icon(searchDescriptor).anchor(.5f,.95f).zIndex(10000));
    }
    private void locateOnColdStart(){
        if(!active||map==null||coldStartLocationAttempted)return;
        coldStartLocationAttempted=true;
        requestCurrentLocation();
    }
    private void requestCurrentLocation(){
        clearSearchMarker();
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
                LatLng point=new LatLng(lat,lon);if("gcj02".equalsIgnoreCase(type)){point=new CoordinateConverter().from(CoordinateConverter.CoordType.COMMON).coord(point).convert();}else if("wgs84".equalsIgnoreCase(type)&&CoordinateAdapter.likelyChinaRegion(lat,lon)){point=CoordinateAdapter.adapt(lat,lon);}else if(!"bd09ll".equalsIgnoreCase(type)&&!"bd09".equalsIgnoreCase(type)&&!"wgs84".equalsIgnoreCase(type)){Toast.makeText(MainActivity.this,"定位坐标类型不可用",Toast.LENGTH_SHORT).show();return;}
                // 百度海外底图使用 WGS84；国内请求 bd09ll，不能把海外 WGS84 当成 BD09 再转换。
                if(point==null){Toast.makeText(MainActivity.this,"定位坐标转换失败",Toast.LENGTH_SHORT).show();return;}map.setMyLocationEnabled(true);map.setMyLocationData(new MyLocationData.Builder().latitude(point.latitude).longitude(point.longitude).accuracy(Math.max(0,result.getRadius())).build());map.animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(point,16));Toast.makeText(MainActivity.this,"定位完成",Toast.LENGTH_SHORT).show();});}};
            client.registerLocationListener(locationListener);client.start();handler.postDelayed(locationTimeout,20000);Toast.makeText(this,"正在定位",Toast.LENGTH_SHORT).show();
        }catch(Exception e){stopLocation();Toast.makeText(this,"定位暂不可用",Toast.LENGTH_SHORT).show();}
    }
    private void stopLocation(){handler.removeCallbacks(locationTimeout);LocationClient client=locationClient;locationClient=null;if(client!=null){if(locationListener!=null)client.unRegisterLocationListener(locationListener);client.stop();}locationListener=null;}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==104){if(hasVideoAccess())applyMediaMode(pendingMediaMode);else Toast.makeText(this,"未授予视频权限，继续显示照片",Toast.LENGTH_SHORT).show();return;}if(request==103){resumeExperiment();return;}if(request==102){if(checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED||checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED)requestCurrentLocation();else Toast.makeText(this,"未授予位置权限，仍可浏览照片地图",Toast.LENGTH_LONG).show();}}
    private void schedule(){schedule(false);}
    private void schedule(boolean panReuse){
        if(!active)return;
        cancelRequests();
        allowPanReuse=panReuse;
        projectionRetries=0;
        if(experiment!=null && experiment.state()==MapDataRepository.State.READY){
            handler.postDelayed(viewportRefresh,40);
        }
    }

    private void retryProjection(){
        if(!active)return;
        viewportDirty=true;
        handler.removeCallbacks(viewportRefresh);
        if(++projectionRetries<=20)handler.postDelayed(viewportRefresh,250);
        else showViewportFailure(viewportGeneration);
    }

    private void queryViewport(){
        if(!active||experiment==null||map==null||view.getWidth()==0||view.getHeight()==0)return;
        int width=view.getWidth(),height=view.getHeight();
        Projection projection=map.getProjection();
        if(projection==null){retryProjection();return;}
        boolean tryPan=allowPanReuse;allowPanReuse=false;
        PanState priorPan=panState;MapStatus status=map.getMapStatus();
        if(tryPan&&priorPan!=null&&priorPan.data==viewport&&status!=null&&experiment.scopeToken()==displayedScope){
            Point anchor=projection.toScreenLocation(priorPan.anchor);
            if(anchor!=null&&ScreenClusters.canReusePan(width,height,priorPan.width,priorPan.height,status.zoom,priorPan.zoom,
                    status.rotate,priorPan.rotate,status.overlook,priorPan.tilt,anchor.x,anchor.y,width/2,height/2,
                    priorPan.marginX,priorPan.marginY,priorPan.clearance)){
                viewportDirty=false;viewportPhase="视野已复用";viewportTimings="平移复用已有标记";showViewportStatus(markers.isEmpty());return;
            }
        }
        int padX=width/4,padY=height/4;
        Point[] corners={new Point(-padX,-padY),new Point(width+padX,-padY),new Point(-padX,height+padY),new Point(width+padX,height+padY)};
        double[] latitudes=new double[corners.length],longitudes=new double[corners.length];
        boolean expanded=true;
        for(int i=0;i<corners.length;i++){
            LatLng point=projection.fromScreenLocation(corners[i]);
            if(point==null){expanded=false;break;}
            Point roundTrip=projection.toScreenLocation(point);
            if(roundTrip==null||Math.abs((long)roundTrip.x-corners[i].x)>4||Math.abs((long)roundTrip.y-corners[i].y)>4){expanded=false;break;}
            latitudes[i]=point.latitude;
            longitudes[i]=point.longitude;
        }
        if(!expanded){
            padX=0;padY=0;
            corners=new Point[]{new Point(0,0),new Point(width,0),new Point(0,height),new Point(width,height)};
            for(int i=0;i<corners.length;i++){
                LatLng point=projection.fromScreenLocation(corners[i]);if(point==null){retryProjection();return;}
                latitudes[i]=point.latitude;longitudes[i]=point.longitude;
            }
        }
        final int marginX=padX,marginY=padY;
        LatLng center=projection.fromScreenLocation(new Point(width/2,height/2));
        if(center==null){retryProjection();return;}
        projectionRetries=0;
        final ViewportBounds visibleBounds,requestedBounds;
        try{
            visibleBounds=GeographicViewport.fromCorners(latitudes,longitudes,center.longitude,.03);
            requestedBounds=GeographicViewport.fromCorners(latitudes,longitudes,center.longitude,.1);
        }catch(IllegalArgumentException invalid){return;}

        long ticket=++viewportGeneration;
        long started=SystemClock.elapsedRealtime();
        ViewportData previous=viewport;
        if(previous!=null&&previous.bounds.covers(visibleBounds.south,visibleBounds.north,visibleBounds.west,visibleBounds.east)){
            viewportCacheHits++;
            projectViewport(ticket,previous,visibleBounds,started,marginX,marginY);
            return;
        }
        viewportPhase="正在筛选";
        if(markers.isEmpty())showGalleryStatus("正在查找此范围的照片",null);
        MapDataRepository repository=experiment;
        boolean accepted=repository.query(requestedBounds.south,requestedBounds.north,requestedBounds.west,requestedBounds.east,window->{
            if(ticket!=viewportGeneration)return;
            if(window==null){
                handler.post(()->showViewportFailure(ticket));
                return;
            }
            ViewportData data=new ViewportData(requestedBounds,window.size());
            int prior=0;
            for(int i=0;i<data.ids.length;i++){
                if(ticket!=viewportGeneration)return;
                MapDataRepository.Point point=window.pointAt(i);
                if(point==null)return;
                data.ids[i]=point.id;
                data.versions[i]=point.mediaVersion;
                // Both windows keep media IDs in ascending order. Reuse coordinates in their overlap.
                if(previous!=null){
                    while(prior<previous.ids.length&&previous.ids[prior]<point.id)prior++;
                    if(prior<previous.ids.length&&previous.ids[prior]==point.id){
                        data.positions[i]=previous.positions[prior];
                        continue;
                    }
                }
                try{data.positions[i]=CoordinateAdapter.adapt(point.latitude,point.longitude);}
                catch(RuntimeException failure){
                    handler.post(()->showViewportFailure(ticket));
                    return;
                }
            }
            handler.post(()->{
                if(!active||ticket!=viewportGeneration||experiment!=repository||repository.scopeToken()!=displayedScope)return;
                viewport=data;
                projectViewport(ticket,data,visibleBounds,started,marginX,marginY);
            });
        });
        if(!accepted)showViewportFailure(ticket);
    }

    private void showViewportFailure(long ticket){
        if(!active||ticket!=viewportGeneration)return;
        viewportPhase="区域筛选失败";
        showGalleryStatus("此范围读取失败 · 重试",this::schedule);
    }

    private void projectViewport(long ticket,ViewportData data,ViewportBounds visibleBounds,long started,int marginX,int marginY){
        viewportPhase="正在生成地图标记";
        long projectionStarted=SystemClock.elapsedRealtime();
        int width=view.getWidth(),height=view.getHeight();
        Projection projection=map.getProjection();
        if(projection==null){retryProjection();return;}
        int markerPixels=Math.max(1,dp(48));
        int[] gaps={dp(8),dp(12),dp(16),dp(24)};
        MapStatus status=map.getMapStatus();LatLng anchor=projection.fromScreenLocation(new Point(width/2,height/2));
        PanState candidate=status==null||anchor==null?null:new PanState(data,anchor,width,height,marginX,marginY,markerPixels+gaps[0],status);
        projectionTask=new Runnable(){
            final int[] xs=new int[data.ids.length],ys=new int[data.ids.length],sources=new int[data.ids.length];
            final Map<ProjectionKey,Point> projected=new HashMap<>();
            int cursor,size,slices,projectionCalls,projectionHits;
            long activeNanos;
            @Override public void run(){
                if(!active||ticket!=viewportGeneration)return;
                long sliceStart=System.nanoTime(),deadline=sliceStart+PROJECTION_BUDGET_NANOS;
                while(cursor<data.ids.length){
                    int end=Math.min(cursor+64,data.ids.length);
                    for(;cursor<end;cursor++){
                        LatLng position=data.positions[cursor];
                        if(position==null||!visibleBounds.contains(position.latitude,position.longitude))continue;
                        ProjectionKey key=new ProjectionKey(position);
                        Point pixel=projected.get(key);
                        if(pixel==null){projectionCalls++;pixel=projection.toScreenLocation(position);if(pixel!=null){pixel=new Point(pixel.x,pixel.y);projected.put(key,pixel);}}
                        else projectionHits++;
                        if(pixel!=null&&pixel.x>=-marginX&&pixel.y>=-marginY&&pixel.x<width+marginX&&pixel.y<height+marginY){
                            xs[size]=pixel.x+marginX;
                            ys[size]=pixel.y+marginY;
                            sources[size++]=cursor;
                        }
                    }
                    if(cursor<data.ids.length&&System.nanoTime()>=deadline){
                        activeNanos+=System.nanoTime()-sliceStart;
                        // Give the event queue opportunities to process input between
                        // short slices, but do not wait a full frame after every slice.
                        if(++slices%3==0)view.postOnAnimation(this);else handler.post(this);
                        return;
                    }
                }
                activeNanos+=System.nanoTime()-sliceStart;
                projectionTask=null;
                int visible=size;
                long projectionMillis=SystemClock.elapsedRealtime()-projectionStarted;
                String projectionDetails="（执行 "+(activeNanos/1_000_000)+" ms · SDK "+projectionCalls+" 次 · 坐标复用 "+projectionHits+" 次）";
                clusterWorker.execute(()->{
                    if(ticket!=viewportGeneration)return;
                    long clusteringStarted=SystemClock.elapsedRealtime();
                    ClusterProjection previous=clusterProjection;
                    List<RenderCluster> reused=previous==null?null:previous.translated(data,markerPixels,marginX,marginY,gaps,xs,ys,sources,visible);
                    if(reused!=null){
                        long clusteringMillis=SystemClock.elapsedRealtime()-clusteringStarted;
                        handler.post(()->{publishClusters(ticket,reused,data.ids.length,visible,started,projectionMillis,clusteringMillis,projectionDetails);if(ticket==viewportGeneration&&!viewportDirty)panState=candidate;});
                        return;
                    }
                    List<ScreenClusters.Cluster> cells;
                    try {
                        cells=ScreenClusters.group(Arrays.copyOf(xs,visible),Arrays.copyOf(ys,visible),
                            markerPixels,gaps,100,()->ticket!=viewportGeneration||Thread.currentThread().isInterrupted());
                    } catch(java.util.concurrent.CancellationException cancelled){return;}
                    List<RenderCluster> result=new ArrayList<>(cells.size());
                    for(ScreenClusters.Cluster item:cells){
                        long[] ids=new long[item.members.length];
                        for(int i=0;i<ids.length;i++)ids[i]=data.ids[sources[item.members[i]]];
                        LatLng singleton=ids.length==1?data.positions[sources[item.members[0]]]:null;
                        result.add(new RenderCluster(new ClusterKey(ids),new Point(item.x-marginX,item.y-marginY),singleton));
                    }
                    ClusterProjection next=new ClusterProjection(data,markerPixels,marginX,marginY,gaps,Arrays.copyOf(xs,visible),Arrays.copyOf(ys,visible),Arrays.copyOf(sources,visible),result);
                    long clusteringMillis=SystemClock.elapsedRealtime()-clusteringStarted;
                    handler.post(()->{
                        if(!active||ticket!=viewportGeneration||experiment==null||experiment.scopeToken()!=displayedScope)return;
                        clusterProjection=next;
                        publishClusters(ticket,result,data.ids.length,visible,started,projectionMillis,clusteringMillis,projectionDetails);
                        if(ticket==viewportGeneration&&!viewportDirty)panState=candidate;
                    });
                });
            }
        };
        view.postOnAnimation(projectionTask);
    }

    private void publishClusters(long ticket,List<RenderCluster> clusters,int matches,int visible,long started,long projectionMillis,long clusteringMillis,String projectionDetails){
        if(!active||ticket!=viewportGeneration||experiment==null||experiment.scopeToken()!=displayedScope)return;
        long publishStarted=SystemClock.elapsedRealtime();
        Set<ClusterKey> retained=new HashSet<>();
        Projection projection=map.getProjection();
        if(projection==null){retryProjection();return;}
        for(RenderCluster cluster:clusters){
            LatLng center=cluster.singleton!=null?cluster.singleton:projection.fromScreenLocation(cluster.center);
            if(center==null)continue;
            ClusterKey key=cluster.key;
            retained.add(key);
            MarkerResource resource=markers.get(key);
            if(resource!=null){
                if(!center.equals(resource.marker.getPosition()))resource.marker.setPosition(center);
                continue;
            }
            Bitmap bitmap=markerBitmap(null,key.ids.length);
            if(key.ids.length==1&&MediaIdentity.isVideo(key.ids[0]))drawVideoBadge(bitmap);
            BitmapDescriptor descriptor=BitmapDescriptorFactory.fromBitmap(bitmap);
            long markerId=++nextMarkerId;
            Bundle info=new Bundle();
            info.putLong("markerId",markerId);
            Marker overlay=(Marker)map.addOverlay(new MarkerOptions().position(center).icon(descriptor)
                .anchor(.5f,.5f).zIndex(key.ids.length).extraInfo(info));
            resource=new MarkerResource(markerId,key,overlay,descriptor,bitmap);
            markers.put(key,resource);
            markerClicks.put(markerId,resource);
        }
        Iterator<MarkerResource> iterator=markers.values().iterator();
        while(iterator.hasNext()){
            MarkerResource resource=iterator.next();
            if(retained.contains(resource.key))continue;
            cancelMarkerCover(resource);
            resource.release();
            markerClicks.remove(resource.clickId);
            iterator.remove();
        }
        viewportDirty=false;
        viewportMatches=matches;
        viewportLoaded=visible;
        viewportPhase="标记已更新 · "+(SystemClock.elapsedRealtime()-started)+" ms";
        viewportTimings="刷新耗时：投影 "+projectionMillis+" ms "+projectionDetails+" · 聚合 "+clusteringMillis+" ms · 更新标记 "+(SystemClock.elapsedRealtime()-publishStarted)+" ms（不含封面加载）";
        showViewportStatus(markers.isEmpty());
        if(groupMode)restartVisibleImages();else loadMarkerCovers();
    }

    private void openCluster(MarkerResource resource){
        MapDataRepository repository=experiment;
        if(!active||repository==null||repository.scopeToken()!=displayedScope||groupMode)return;
        if(viewportDirty){
            Toast.makeText(this,"正在更新此范围的照片",Toast.LENGTH_SHORT).show();
            return;
        }
        if(openingCluster)return;
        long[] ids=resource.key.ids;
        if(ids.length==1){
            if(MediaIdentity.isVideo(ids[0])){openVideo(ids[0]);return;}
            openingCluster=true;
            pendingPhotoReturn=null;
            openPreviewSession(new PreviewSessionStore.Session(Collections.emptyList(),repository,displayedScope,ids[0]),0);
            return;
        }
        long selection=++photoSelection;
        openingCluster=true;
        cancelMarkerCovers();group=null;groupMode=true;oldestFirst=false;
        panel.showLoading(ids.length);updatePanel();
        photoRequest=repository.loadPhotoTimeline(ids,timeline->
            handler.post(()->finishClusterSelection(selection,repository,timeline)));
        if(photoRequest==null){
            openingCluster=false;
            if(groupMode){groupMode=false;renderList();}
            showGalleryStatus("照片暂时无法打开 · 重试",()->openCluster(resource));
        }
    }

    private void finishClusterSelection(long selection,MapDataRepository repository,PhotoTimeline timeline){
        if(!active||selection!=photoSelection||experiment!=repository)return;
        openingCluster=false;
        photoRequest=null;
        if(timeline==null||repository.scopeToken()!=displayedScope){
            if(groupMode){groupMode=false;renderList();}
            checkRepository();
            Toast.makeText(this,"照片暂时无法打开，请重新选择",Toast.LENGTH_SHORT).show();
            return;
        }
        rebuiltAfterMediaChange=false;
        showViewportStatus(false);
        GalleryPhotoList items=new GalleryPhotoList(timeline,false);
        cancelMarkerCovers();
        group=items;
        oldestFirst=false;
        groupMode=true;
        restorePosition=-1;
        renderList();
        panel.scrollToPhoto(0,0);
    }

    private void cancelRequests(){
        if(openingCluster&&groupMode){groupMode=false;group=null;renderList();}
        cancelPhotoLoad();
        viewportDirty=true;
        viewportGeneration++;
        handler.removeCallbacks(viewportRefresh);
        if(projectionTask!=null){view.removeCallbacks(projectionTask);handler.removeCallbacks(projectionTask);projectionTask=null;}
        clusterWorker.getQueue().clear();
    }

    private void cancelPhotoLoad(){
        photoSelection++;
        openingCluster=false;
        if(photoRequest!=null){
            photoRequest.cancel();
            photoRequest=null;
        }
    }

    private void loadMarkerCovers(){
        if(!active||groupMode||experiment==null||experiment.scopeToken()!=displayedScope)return;
        for(MarkerResource resource:markers.values())loadMarkerCover(resource);
    }

    private void loadMarkerCover(MarkerResource resource){
        long[] ids=resource.key.ids;
        if(!active||groupMode||markerClicks.get(resource.clickId)!=resource||resource.hasCover
            ||resource.coverRequest!=null||resource.coverRetry!=null
            ||resource.coverAttempt>=Math.min(MAX_COVER_ATTEMPTS,ids.length))return;
        long id=ids[ids.length-1-resource.coverAttempt];
        ViewportData data=viewport;int at=data==null?-1:Arrays.binarySearch(data.ids,id);
        if(at<0){retryMarkerCover(resource);return;}
        loadMarkerBitmap(resource,id,data.versions[at]);
    }
    private void loadMarkerBitmap(MarkerResource resource,long id,long version){
        long[] ids=resource.key.ids;
        Uri collection=MediaIdentity.isVideo(id)?android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY):android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri uri=android.content.ContentUris.withAppendedId(collection,MediaIdentity.id(id));
        resource.coverRequest=thumbnails.tryLoad(uri,version,160,bitmap->{
            resource.coverRequest=null;
            if(!active||groupMode||markerClicks.get(resource.clickId)!=resource
                ||experiment==null||experiment.scopeToken()!=displayedScope)return;
            if(bitmap==null){resource.coverAttempt++;loadMarkerCover(resource);return;}
            Bitmap next=markerBitmap(bitmap,ids.length);
            if(ids.length==1&&MediaIdentity.isVideo(id))drawVideoBadge(next);
            BitmapDescriptor descriptor=BitmapDescriptorFactory.fromBitmap(next);
            resource.marker.setIcon(descriptor);
            resource.descriptor.recycle();
            resource.bitmap.recycle();
            resource.descriptor=descriptor;
            resource.bitmap=next;
            resource.hasCover=true;
        });
        if(resource.coverRequest==null)retryMarkerCover(resource);
    }
    private void retryMarkerCover(MarkerResource resource){if(resource.coverRetry!=null)return;resource.coverRetry=()->{resource.coverRetry=null;loadMarkerCover(resource);};handler.postDelayed(resource.coverRetry,120);}

    private void cancelMarkerCover(MarkerResource resource){
        if(resource.coverRequest!=null){resource.coverRequest.cancel();resource.coverRequest=null;}
        if(resource.coverRetry!=null){handler.removeCallbacks(resource.coverRetry);resource.coverRetry=null;}
    }

    private void cancelMarkerCovers(){
        for(MarkerResource resource:markers.values())cancelMarkerCover(resource);
    }
    private Bitmap markerBitmap(Bitmap original,int count){int n=dp(48),border=dp(2);Bitmap out=Bitmap.createBitmap(n,n,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(out);Paint p=new Paint(3);p.setColor(SURFACE);c.drawRoundRect(0,0,n,n,dp(9),dp(9),p);p.setColor(PLACEHOLDER);c.drawRoundRect(border,border,n-border,n-border,dp(7),dp(7),p);if(original!=null){float scale=Math.max((float)(n-2*border)/original.getWidth(),(float)(n-2*border)/original.getHeight());Matrix m=new Matrix();m.setScale(scale,scale);m.postTranslate((n-original.getWidth()*scale)/2,(n-original.getHeight()*scale)/2);BitmapShader shader=new BitmapShader(original,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);shader.setLocalMatrix(m);p.setShader(shader);c.drawRoundRect(border,border,n-border,n-border,dp(7),dp(7),p);p.setShader(null);}if(count>1){String text=MarkerCount.format(count);p.setTextSize(dp(10));p.setTypeface(Typeface.DEFAULT_BOLD);float w=Math.max(dp(19),p.measureText(text)+dp(8));p.setColor(0xe6263a49);c.drawRoundRect(n-w-dp(1),n-dp(17),n-dp(1),n-dp(1),dp(8),dp(8),p);p.setColor(-1);p.setTextAlign(Paint.Align.CENTER);c.drawText(text,n-w/2-dp(1),n-dp(5),p);}return out;}
    static void verifyPreviewScope(PreviewSessionStore.Session session,MapDataRepository.Callback<Boolean> callback){
        if(experiment==null||session.repository!=experiment){callback.complete(false);return;}
        experiment.verifyExistingScope(session.scopeToken,callback);
    }
    static void verifySinglePreview(PreviewSessionStore.Session session,
                                    MapDataRepository.Callback<MediaStoreLookup.Metadata> callback){
        if(experiment==null||session.repository!=experiment){callback.complete(null);return;}
        experiment.verifyPreviewPhoto(session.scopeToken,session.singleId,callback);
    }
    static void suspendPreviewScope(PreviewSessionStore.Session session){
        if(experiment!=null&&session!=null&&session.repository==experiment)experiment.suspend();
    }
    private void openPhoto(int position){
        if(!canLoadGalleryImages()||group==null)return;
        openPreview(group,position);
    }
    private void openVideo(long key){Uri uri=android.content.ContentUris.withAppendedId(android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),MediaIdentity.id(key));openVideoUri(uri);}
    private void openVideoUri(Uri uri){
        try{Intent intent=new Intent(Intent.ACTION_VIEW);intent.setDataAndType(uri,"video/*");intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(intent);}
        catch(android.content.ActivityNotFoundException unavailable){Toast.makeText(this,"没有可用的视频播放器",Toast.LENGTH_SHORT).show();}
        catch(SecurityException denied){Toast.makeText(this,"视频权限已失效，请重新授权",Toast.LENGTH_SHORT).show();}
    }
    private void drawVideoBadge(Bitmap bitmap){Canvas canvas=new Canvas(bitmap);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);float x=bitmap.getWidth()-dp(11),y=bitmap.getHeight()-dp(11);paint.setColor(0xbb000000);canvas.drawCircle(x,y,dp(9),paint);paint.setColor(Color.WHITE);Path path=new Path();path.moveTo(x-dp(2),y-dp(5));path.lineTo(x+dp(5),y);path.lineTo(x-dp(2),y+dp(5));path.close();canvas.drawPath(path,paint);}
    private void openPreview(List<PhotoItem> source,int index){
        if(experiment==null||index<0||index>=source.size())return;
        pendingPhotoReturn=source.size()>1?snapshotGallery():null;
        openPreviewSession(new PreviewSessionStore.Session(source,experiment,displayedScope,-1),index);
    }
    private void openPreviewSession(PreviewSessionStore.Session session,int index){
        startActivity(new Intent(this,PhotoPreviewActivity.class).putExtra("session",PreviewSessionStore.put(session)).putExtra("index",index));
    }
    private PendingPhotoReturn snapshotGallery(){
        if(!groupMode||group==null||displayedScope<0||experiment==null||panel==null)return null;
        return new PendingPhotoReturn(experiment,displayedScope,group,oldestFirst,panel.firstVisiblePhoto(),panel.scrollOffset());
    }
    @Override public Object onRetainNonConfigurationInstance(){return snapshotGallery();}
    private boolean canLoadGalleryImages(){
        return active&&groupMode&&displayedScope>=0&&experiment!=null&&experiment.scopeToken()==displayedScope;
    }
    private void cancelVisibleImages(){if(panel!=null)panel.cancelImages();}
    private void restartVisibleImages(){if(panel!=null)panel.restartImages();}
    private void clearVisibleImages(){if(panel!=null)panel.clearImages();}
    private void clearData(){
        displayedScope=-1;
        cancelRequests();
        viewport=null;
        clusterProjection=null;
        panState=null;
        viewportPhase="尚未筛选";
        viewportTimings="尚无刷新耗时";
        viewportMatches=viewportLoaded=0;
        cancelMarkerCovers();
        for(MarkerResource resource:markers.values())resource.release();
        markers.clear();
        markerClicks.clear();
        clearVisibleImages();
        thumbnails.clear();
        group=null;
        groupMode=false;
        restorePosition=-1;
        restoreOffset=0;
        showInitialGalleryStatus();
        renderList();
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);if(map!=null)transientMapStatus=map.getMapStatus();out.putBoolean("oldestFirst",oldestFirst);}
    private void updateNetworkStatus(){
        if(networkStatus==null)return;
        ConnectivityManager manager=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities capabilities=manager==null?null:manager.getNetworkCapabilities(manager.getActiveNetwork());
        boolean online=capabilities!=null&&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        networkStatus.setVisibility(online?View.GONE:View.VISIBLE);
    }
    @Override protected void onStart(){super.onStart();ConnectivityManager manager=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);if(manager!=null){manager.registerDefaultNetworkCallback(networkCallback);networkCallbackRegistered=true;}updateNetworkStatus();}
    @Override protected void onStop(){stopLocation();if(networkCallbackRegistered){ConnectivityManager manager=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);if(manager!=null)manager.unregisterNetworkCallback(networkCallback);networkCallbackRegistered=false;}super.onStop();}
    private void showScopeCover(){
        if(root==null)return;
        if(scopeCover==null){
            scopeCover=label("",14,SECONDARY);
            scopeCover.setGravity(Gravity.CENTER);
            scopeCover.setBackgroundColor(MAP_BACKGROUND);
            scopeCover.setElevation(dp(12));
            scopeCover.setClickable(true);
            scopeCover.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            root.addView(scopeCover,new FrameLayout.LayoutParams(-1,-1));
        }
        handler.removeCallbacks(showScopeMessage);
        scopeCover.setText("");
        if(active)handler.postDelayed(showScopeMessage,200);
    }
    private void hideScopeCover(){
        handler.removeCallbacks(showScopeMessage);
        if(scopeCover!=null){root.removeView(scopeCover);scopeCover=null;}
    }
    private void resumeMapView(){if(active&&view!=null&&!mapResumed){view.onResume();mapResumed=true;}}
    @Override protected void onResume(){super.onResume();active=true;if(view!=null){resumeMapView();resumeExperiment();locateOnColdStart();}}
    @Override protected void onPause(){
        lastExitBack=-1;
        active=false;
        if(placeSearch!=null)placeSearch.close();
        if(displayedScope>=0)showScopeCover();
        boolean needsRedraw=viewportDirty;
        cancelRequests();
        viewportDirty=needsRedraw;
        cancelMarkerCovers();
        cancelVisibleImages();
        handler.removeCallbacks(checkLoad);
        stopLocation();
        if(experiment!=null)experiment.suspend();
        if(view!=null&&mapResumed){view.onPause();mapResumed=false;}
        super.onPause();
    }
    @Override protected void onDestroy(){if(placeSearch!=null)placeSearch.close();clearSearchMarker();clearData();thumbnails.close();clusterWorker.shutdownNow();if(view!=null)view.onDestroy();if(!isChangingConfigurations()&&isFinishing()&&experiment!=null){experiment.close();experiment=null;pendingPhotoReturn=null;}super.onDestroy();if(exitRequested)android.os.Process.killProcess(android.os.Process.myPid());}
    private final class LayerIcon extends android.graphics.drawable.Drawable{
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        public void draw(Canvas c){
            Rect b=getBounds();float x=b.exactCenterX(),y=b.exactCenterY(),w=b.width()*.38f,h=b.height()*.21f;
            p.setColor(ACCENT);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1.7f));p.setStrokeJoin(Paint.Join.ROUND);p.setStrokeCap(Paint.Cap.ROUND);
            Path top=new Path();top.moveTo(x,y-h*1.5f);top.lineTo(x+w,y-h*.5f);top.lineTo(x,y+h*.5f);top.lineTo(x-w,y-h*.5f);top.close();c.drawPath(top,p);
            Path lower=new Path();lower.moveTo(x-w,y+h*.1f);lower.lineTo(x,y+h*1.1f);lower.lineTo(x+w,y+h*.1f);c.drawPath(lower,p);
            Path bottom=new Path();bottom.moveTo(x-w,y+h*.7f);bottom.lineTo(x,y+h*1.7f);bottom.lineTo(x+w,y+h*.7f);c.drawPath(bottom,p);
        }
        public void setAlpha(int a){}public void setColorFilter(android.graphics.ColorFilter f){}public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }
    private final class FitIcon extends android.graphics.drawable.Drawable{private final Paint p=new Paint(3);public void draw(Canvas c){android.graphics.Rect b=getBounds();float x=b.exactCenterX(),y=b.exactCenterY(),r=Math.min(b.width(),b.height())*.32f;p.setColor(ACCENT);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setStrokeCap(Paint.Cap.ROUND);c.drawCircle(x,y,r,p);c.drawLine(x,b.top,x,y-r,p);c.drawLine(x,y+r,x,b.bottom,p);c.drawLine(b.left,y,x-r,y,p);c.drawLine(x+r,y,b.right,y,p);p.setStyle(Paint.Style.FILL);c.drawCircle(x,y,dp(2),p);}public void setAlpha(int a){}public void setColorFilter(android.graphics.ColorFilter f){}public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}}
}
