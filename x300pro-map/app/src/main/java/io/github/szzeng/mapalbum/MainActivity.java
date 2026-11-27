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
    private static final String MAP_SETTINGS="map_settings", CUSTOM_BAIDU_AK="baidu_ak";
    private static final int MAX_CLUSTER_PHOTOS=10000, MAX_COVER_ATTEMPTS=3;
    private static final long PROJECTION_BUDGET_NANOS=4_000_000;
    private int TEXT, SECONDARY, ACCENT, PLACEHOLDER, SURFACE, FLOATING, MAP_BACKGROUND, MENU_ICON;
    private boolean darkMode;
    private MapView view; private BaiduMap map; private FrameLayout root;
    private PhotoGalleryView panel;
    private TextView count,networkStatus; private ImageView fit;
    private ThumbnailLoader thumbnails;
    private final ThreadPoolExecutor clusterWorker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1),new ThreadPoolExecutor.DiscardOldestPolicy());
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable viewportRefresh=this::queryViewport;
    private final ArrayList<PhotoItem> group=new ArrayList<>();
    private final Map<ClusterKey,MarkerResource> markers=new HashMap<>();
    private final Map<Long,MarkerResource> markerClicks=new HashMap<>();
    private long nextMarkerId, photoSelection;
    private boolean groupMode, oldestFirst, rebuiltAfterMediaChange, openingCluster;
    private boolean galleryBars;
    private int mapBarFlags,mapStatusColor,mapNavigationColor;
    private int restorePosition=-1, restoreOffset, bottomInset;
    private boolean networkCallbackRegistered;
    private Bundle saved;
    private LocationClient locationClient; private BDAbstractLocationListener locationListener;
    private static MapStatus transientMapStatus;
    private static MapDataRepository experiment;
    private static PendingPhotoReturn pendingPhotoReturn;
    private PendingPhotoReturn retainedGallery;
    private static final class PendingPhotoReturn {
        final MapDataRepository repository;final long scopeToken;final ArrayList<PhotoItem> photos;
        final boolean oldestFirst;final int scroll,offset;
        PendingPhotoReturn(MapDataRepository repository,long scopeToken,List<PhotoItem> photos,boolean oldestFirst,int scroll,int offset){
            this.repository=repository;this.scopeToken=scopeToken;this.photos=new ArrayList<>(photos);this.oldestFirst=oldestFirst;this.scroll=scroll;this.offset=offset;
        }
    }
    private boolean active, mapResumed, viewportDirty=true;
    private long displayedScope=-1;
    private TextView scopeCover;
    private volatile long viewportGeneration;
    private int projectionRetries;
    private Runnable projectionTask;
    private final Runnable checkLoad=this::checkRepository;
    private String scopeLabel="未授权";
    private String viewportPhase="尚未筛选";
    private int viewportMatches, viewportLoaded, viewportCacheHits;
    private ViewportData viewport;
    private static final class ViewportData {
        final ViewportBounds bounds;
        final long[] ids;
        final LatLng[] positions;
        ViewportData(ViewportBounds bounds,int size){
            this.bounds=bounds;
            ids=new long[size];
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
    private TextView label(String text,int size,int color){TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
    @Override protected void attachBaseContext(Context base){super.attachBaseContext(base);Appearance.applyOverride(this,base);}
    @Override public void onCreate(Bundle state){super.onCreate(state);saved=state;darkMode=(getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
        TEXT=getColor(R.color.app_text);SECONDARY=getColor(R.color.app_secondary);ACCENT=getColor(R.color.app_accent);PLACEHOLDER=getColor(R.color.app_placeholder);SURFACE=getColor(R.color.app_surface);FLOATING=getColor(R.color.app_floating);MAP_BACKGROUND=getColor(R.color.app_map_background);MENU_ICON=getColor(R.color.app_menu_icon);
        retainedGallery=(PendingPhotoReturn)getLastNonConfigurationInstance();
        thumbnails=new ThumbnailLoader(getContentResolver());
        TextView waiting=label("等待隐私选择",14,TEXT);setContentView(waiting);
        if(PrivacyConsent.isCurrent(getPreferences(MODE_PRIVATE).getInt(PrivacyConsent.KEY,0))){initializeMap();return;}
        AlertDialog privacy=new AlertDialog.Builder(this).setTitle("百度地图与定位隐私说明").setMessage("图迹使用百度地图 SDK。点击当前位置后，图迹会请求前台位置权限，并使用百度定位 SDK 获取当前位置，SDK 可能读取 Wi-Fi 状态辅助定位；百度按其隐私政策处理定位请求。图迹不进行后台持续定位、不建立位置历史、不上传位置到图迹自有服务。拒绝定位权限不影响地图和照片浏览。请先阅读百度隐私政策；不同意时不初始化 SDK。")
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
        root=new FrameLayout(this);root.setBackgroundColor(MAP_BACKGROUND);view=new MapView(this,options);view.showZoomControls(false);root.addView(view);setContentView(root);resumeMapView();map=view.getMap();map.setMapType(BaiduMap.MAP_TYPE_NORMAL);map.setMapThemeMode(darkMode?BMThemeMode.MapThemeModeDark:BMThemeMode.MapThemeModeDefault);view.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol||b-t!=ob-ot)schedule();});
        map.setOnMarkerClickListener(m->{
            Bundle info=m.getExtraInfo();
            MarkerResource resource=info==null?null:markerClicks.get(info.getLong("markerId"));
            if(resource!=null)openCluster(resource);
            return true;
        });
        map.setOnMapStatusChangeListener(new BaiduMap.OnMapStatusChangeListener(){public void onMapStatusChangeStart(MapStatus s){cancelRequests();}public void onMapStatusChangeStart(MapStatus s,int reason){cancelRequests();}public void onMapStatusChange(MapStatus s){}public void onMapStatusChangeFinish(MapStatus s){schedule();}});
        buildUi();
        if(experiment==null && getPreferences(MODE_PRIVATE).getBoolean(confirmationKey(),false) && supportedGallery())experiment=createGallery();
        if(saved!=null)oldestFirst=saved.getBoolean("oldestFirst");
        if(active)resumeExperiment();
        schedule();
    }catch(RuntimeException e){LinearLayout failure=new LinearLayout(this);failure.setOrientation(LinearLayout.VERTICAL);failure.setGravity(Gravity.CENTER);TextView message=label("地图加载失败，请检查百度地图 AK",14,SECONDARY);message.setGravity(Gravity.CENTER);failure.addView(message);Button settings=new Button(this);settings.setText("设置百度地图 AK");settings.setOnClickListener(v->showBaiduAkSettings());failure.addView(settings);setContentView(failure);}}
    private void buildUi(){
        count=label("",13,ACCENT);
        count.setMinHeight(dp(48));
        count.setPadding(dp(12),dp(6),dp(12),dp(6));
        count.setBackground(shape(FLOATING,16));
        count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams statusParams=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.START);
        statusParams.setMarginStart(dp(16));statusParams.setMarginEnd(dp(76));statusParams.topMargin=dp(12);
        root.addView(count,statusParams);
        showInitialGalleryStatus();
        TextView menu=label("•••",18,MENU_ICON);menu.setGravity(Gravity.CENTER);menu.setContentDescription("更多菜单");menu.setBackground(shape(FLOATING,24));menu.setElevation(dp(3));menu.setOnClickListener(v->showMenu(menu));
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP|Gravity.RIGHT);bp.setMargins(0,dp(12),dp(16),0);root.addView(menu,bp);
        networkStatus=label("网络不可用，地图可能无法加载",12,SECONDARY);networkStatus.setPadding(dp(12),0,dp(12),0);networkStatus.setBackground(shape(FLOATING,12));
        FrameLayout.LayoutParams np=new FrameLayout.LayoutParams(-2,dp(36),Gravity.TOP|Gravity.LEFT);np.setMargins(dp(16),dp(76),0,0);root.addView(networkStatus,np);updateNetworkStatus();
        fit=new ImageView(this);fit.setImageDrawable(new FitIcon());fit.setPadding(dp(12),dp(12),dp(12),dp(12));fit.setBackground(shape(SURFACE,24));fit.setElevation(dp(3));fit.setContentDescription("定位当前位置");fit.setOnClickListener(v->requestCurrentLocation());root.addView(fit,new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.RIGHT|Gravity.BOTTOM));
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
    private void setPhotoOrder(boolean oldest){if(oldestFirst==oldest)return;oldestFirst=oldest;sortGroup();restorePosition=-1;renderList();panel.scrollToPhoto(0,0);}
    private void sortGroup(){Collections.sort(group,(a,b)->{if(a.dateTaken==null)return b.dateTaken==null?0:1;if(b.dateTaken==null)return -1;return oldestFirst?Long.compare(a.dateTaken,b.dateTaken):Long.compare(b.dateTaken,a.dateTaken);});}
    private void showMenu(View anchor){
        PopupMenu p=new PopupMenu(this,anchor);
        android.view.MenuItem night=p.getMenu().add("暗夜模式");night.setCheckable(true);night.setChecked(darkMode);night.setOnMenuItemClickListener(i->{Appearance.setManual(this,!darkMode);recreate();return true;});
        p.getMenu().add("刷新照片数据").setOnMenuItemClickListener(i->{if(experiment==null)enableExperiment();else reloadGallery();return true;});
        p.getMenu().add("照片 GPS 情况").setOnMenuItemClickListener(i->{showPhotoLocationSummary();return true;});
        p.getMenu().add("百度地图 AK").setOnMenuItemClickListener(i->{showBaiduAkSettings();return true;});
        p.getMenu().add("隐私说明").setOnMenuItemClickListener(i->{new AlertDialog.Builder(this).setMessage("照片和位置仅在设备内存中处理。点击定位后使用百度定位 SDK，可能读取 Wi-Fi 状态；不后台持续定位、不保存位置历史。图库仅限 V2502A / PD2502 当前授权主卷。照片位置已开放全球；跨境地区及海外坐标尚待验证。").setPositiveButton("知道了",null).show();return true;});
        p.getMenu().add("关于图迹").setOnMenuItemClickListener(i->{new AlertDialog.Builder(this).setTitle("关于图迹").setMessage("图迹 0.2.0\n个人使用版").setPositiveButton("知道了",null).show();return true;});
        if(BuildConfig.PERSONAL_DEVICE_RELEASE){
            p.getMenu().add("启用个人图库（仅支持指定设备）").setOnMenuItemClickListener(i->{enableExperiment();return true;});
            p.getMenu().add("修改照片授权").setOnMenuItemClickListener(i->{openPhotoPermissionSettings();return true;});
        }
        if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0){
            p.getMenu().add("主卷全图库实验模式").setOnMenuItemClickListener(i->{enableExperiment();return true;});
            p.getMenu().add("关闭全图库实验模式").setOnMenuItemClickListener(i->{getPreferences(MODE_PRIVATE).edit().remove("experiment_confirmed").apply();if(experiment!=null){experiment.close();experiment=null;}pendingPhotoReturn=null;clearData();return true;});
            p.getMenu().add("修改照片授权").setOnMenuItemClickListener(i->{openPhotoPermissionSettings();return true;});
            p.getMenu().add("数据诊断").setOnMenuItemClickListener(i->{showDiagnostics();return true;});
        }
        p.show();
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
        String summary=experiment==null?null:experiment.photoLocationSummary();
        if(summary==null)summary=count==null?"请先启用个人图库并授权照片访问。":count.getText().toString()+"\n请在照片数据读取完成后查看。";
        new AlertDialog.Builder(this).setTitle("照片 GPS 情况").setMessage(summary).setPositiveButton("知道了",null).show();
    }
    private void openPhotoPermissionSettings(){
        new AlertDialog.Builder(this).setTitle("修改照片授权").setMessage("在系统设置的权限页面中，可以调整图迹可访问的照片范围。返回后图迹会重新检查授权。")
            .setNegativeButton("取消",null).setPositiveButton("打开设置",(d,w)->startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))).show();
    }
    private String confirmationKey(){return BuildConfig.PERSONAL_DEVICE_RELEASE?"personal_gallery_confirmed":"experiment_confirmed";}
    private boolean supportedGallery(){return BuildConfig.PERSONAL_DEVICE_RELEASE?MapDataRepository.isSupportedPersonalRelease(this):MapDataRepository.isSupportedDevice(this);}
    private MapDataRepository createGallery(){return BuildConfig.PERSONAL_DEVICE_RELEASE?MapDataRepository.forConfirmedPersonalRelease(getApplicationContext(),true):MapDataRepository.forConfirmedExperiment(getApplicationContext(),true);}
    private void showGalleryStatus(String message,Runnable action){
        if(count==null)return;
        if(message!=null && !message.contentEquals(count.getText()))count.setText(message);
        count.setVisibility(message==null?View.GONE:View.VISIBLE);
        count.setOnClickListener(action==null?null:v->action.run());
        count.setClickable(action!=null);
        count.setFocusable(action!=null);
    }
    private void showInitialGalleryStatus(){
        if(supportedGallery())showGalleryStatus("启用照片地图",this::enableExperiment);
        else showGalleryStatus(null,null);
    }
    private void enableExperiment(){
        if(!supportedGallery()){new AlertDialog.Builder(this).setMessage("此功能仅支持指定的 vivo V2502A / PD2502 设备，当前设备或构建不符合限制。").setPositiveButton("知道了",null).show();return;}
        new AlertDialog.Builder(this).setTitle(BuildConfig.PERSONAL_DEVICE_RELEASE?"启用个人图库":"启用主卷全图库实验模式")
            .setMessage("当前设备：V2502A / PD2502。仅访问当前授权的主卷照片及其图库位置，读取结果保存在内存中。此功能仍是个人设备实验版本，身份和坐标并非对所有照片或其他设备得到保证。授权范围变化时会重新检查。")
            .setNegativeButton("取消",null).setPositiveButton("确认启用",(d,w)->{
                getPreferences(MODE_PRIVATE).edit().putBoolean(confirmationKey(),true).apply();
                if(experiment==null)experiment=createGallery();
                requestPhotoAccess();
            }).show();
    }
    private boolean hasPhotoAccess(){
        if(android.os.Build.VERSION.SDK_INT>=34)return checkSelfPermission("android.permission.READ_MEDIA_IMAGES")==android.content.pm.PackageManager.PERMISSION_GRANTED || checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")==android.content.pm.PackageManager.PERMISSION_GRANTED;
        if(android.os.Build.VERSION.SDK_INT>=33)return checkSelfPermission("android.permission.READ_MEDIA_IMAGES")==android.content.pm.PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE")==android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
    private void requestPhotoAccess(){
        if(hasPhotoAccess()){resumeExperiment();return;}
        if(android.os.Build.VERSION.SDK_INT>=34)requestPermissions(new String[]{"android.permission.READ_MEDIA_IMAGES","android.permission.READ_MEDIA_VISUAL_USER_SELECTED"},103);
        else if(android.os.Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{"android.permission.READ_MEDIA_IMAGES"},103);
        else requestPermissions(new String[]{"android.permission.READ_EXTERNAL_STORAGE"},103);
    }
    private void resumeExperiment(){
        if(!active||map==null||experiment==null)return;
        if(!hasPhotoAccess()){
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
        if(state==MapDataRepository.State.READY){
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
            case CAPACITY_EXCEEDED: showGalleryStatus("照片超过 10 万张 · 调整授权",this::openPhotoPermissionSettings);break;
            case ACCESS_DENIED: showGalleryStatus("照片访问被拒绝 · 调整权限",this::openPhotoPermissionSettings);break;
            case VOLUME_UNSUPPORTED: showGalleryStatus("当前存储卷不符合主卷限制",null);break;
            case IDENTITY_UNVERIFIED: showGalleryStatus("设备未通过照片地图校验",null);break;
            default: showGalleryStatus("照片位置读取失败 · 重试",this::reloadGallery);
        }
    }
    private void showViewportStatus(boolean emptyViewport){
        if(!emptyViewport)showGalleryStatus(null,null);
        else if(experiment.validCount()==0){
            showGalleryStatus("授权照片中没有可用的位置 · 查看详情",this::showPhotoLocationSummary);
        }else showGalleryStatus("此范围没有照片",null);
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
        group.clear();group.addAll(pending.photos);oldestFirst=pending.oldestFirst;groupMode=true;
        restorePosition=pending.scroll;restoreOffset=pending.offset;renderList();
    }
    private void showDiagnostics(){
        String message="数据模式："+(experiment==null?"未启用":"主卷实验")+"\n授权范围："+scopeLabel+"\n"+(experiment==null?"身份：未验证":experiment.diagnostics())+"\n视野状态："+viewportPhase+" · 匹配 "+viewportMatches+" · 已读取 "+viewportLoaded+" · 地图标记 "+markers.size()+" · 视野复用 "+viewportCacheHits+"\n"+thumbnails.diagnosticCounts();
        new AlertDialog.Builder(this).setTitle("数据诊断").setMessage(message).setNegativeButton("关闭",null).setPositiveButton("复制",(d,w)->{android.content.ClipboardManager clip=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(clip!=null)clip.setPrimaryClip(android.content.ClipData.newPlainText("图迹诊断",message));}).show();
    }
    private void updatePanel(){
        if(panel==null)return;
        panel.setVisibility(groupMode?View.VISIBLE:View.GONE);
        FrameLayout.LayoutParams position=(FrameLayout.LayoutParams)fit.getLayoutParams();
        int margin=bottomInset+dp(16);
        if(position.rightMargin!=dp(16)||position.bottomMargin!=margin){
            position.rightMargin=dp(16);
            position.bottomMargin=margin;
            fit.setLayoutParams(position);
        }
        fit.setVisibility(groupMode?View.GONE:View.VISIBLE);
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
    private void closePhotoPanel(){clearVisibleImages();groupMode=false;group.clear();renderList();loadMarkerCovers();}
    @Override public void onBackPressed(){if(groupMode){closePhotoPanel();return;}super.onBackPressed();}
    private void renderList(){
        if(panel==null)return;
        panel.setPhotos(group,oldestFirst);
        updatePanel();
        if(restorePosition>=0&&groupMode&&!group.isEmpty()){
            panel.scrollToPhoto(restorePosition,restoreOffset);
            restorePosition=-1;
            restoreOffset=0;
        }
    }
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
                LatLng point=new LatLng(lat,lon);if("gcj02".equalsIgnoreCase(type)){point=new CoordinateConverter().from(CoordinateConverter.CoordType.COMMON).coord(point).convert();}else if("wgs84".equalsIgnoreCase(type)&&CoordinateAdapter.likelyChinaRegion(lat,lon)){point=CoordinateAdapter.adapt(lat,lon);}else if(!"bd09ll".equalsIgnoreCase(type)&&!"bd09".equalsIgnoreCase(type)&&!"wgs84".equalsIgnoreCase(type)){Toast.makeText(MainActivity.this,"定位坐标类型不可用",Toast.LENGTH_SHORT).show();return;}
                // 百度海外底图使用 WGS84；国内请求 bd09ll，不能把海外 WGS84 当成 BD09 再转换。
                if(point==null){Toast.makeText(MainActivity.this,"定位坐标转换失败",Toast.LENGTH_SHORT).show();return;}map.setMyLocationEnabled(true);map.setMyLocationData(new MyLocationData.Builder().latitude(point.latitude).longitude(point.longitude).accuracy(Math.max(0,result.getRadius())).build());map.animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(point,16));Toast.makeText(MainActivity.this,"定位完成",Toast.LENGTH_SHORT).show();});}};
            client.registerLocationListener(locationListener);client.start();handler.postDelayed(locationTimeout,20000);Toast.makeText(this,"正在定位",Toast.LENGTH_SHORT).show();
        }catch(Exception e){stopLocation();Toast.makeText(this,"定位暂不可用",Toast.LENGTH_SHORT).show();}
    }
    private void stopLocation(){handler.removeCallbacks(locationTimeout);LocationClient client=locationClient;locationClient=null;if(client!=null){if(locationListener!=null)client.unRegisterLocationListener(locationListener);client.stop();}locationListener=null;}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==103){resumeExperiment();return;}if(request==102){if(checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED||checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED)requestCurrentLocation();else Toast.makeText(this,"未授予位置权限，仍可浏览照片地图",Toast.LENGTH_LONG).show();}}
    private void schedule(){
        if(!active)return;
        cancelRequests();
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
        Point[] corners={new Point(0,0),new Point(width,0),new Point(0,height),new Point(width,height)};
        double[] latitudes=new double[corners.length],longitudes=new double[corners.length];
        for(int i=0;i<corners.length;i++){
            LatLng point=projection.fromScreenLocation(corners[i]);
            if(point==null){retryProjection();return;}
            latitudes[i]=point.latitude;
            longitudes[i]=point.longitude;
        }
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
            projectViewport(ticket,previous,visibleBounds,started);
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
                projectViewport(ticket,data,visibleBounds,started);
            });
        });
        if(!accepted)showViewportFailure(ticket);
    }

    private void showViewportFailure(long ticket){
        if(!active||ticket!=viewportGeneration)return;
        viewportPhase="区域筛选失败";
        showGalleryStatus("此范围读取失败 · 重试",this::schedule);
    }

    private void projectViewport(long ticket,ViewportData data,ViewportBounds visibleBounds,long started){
        viewportPhase="正在生成地图标记";
        int width=view.getWidth(),height=view.getHeight();
        Projection projection=map.getProjection();
        if(projection==null){retryProjection();return;}
        Point origin=projection.toScreenLocation(new LatLng(23,113));
        int originX=origin==null?0:origin.x,originY=origin==null?0:origin.y;
        int cell=ScreenClusters.stableCellPixels(width,height,Math.max(1,dp(72)),100);
        projectionTask=new Runnable(){
            final int[] xs=new int[data.ids.length],ys=new int[data.ids.length],sources=new int[data.ids.length];
            int cursor,size;
            @Override public void run(){
                if(!active||ticket!=viewportGeneration)return;
                long deadline=System.nanoTime()+PROJECTION_BUDGET_NANOS;
                while(cursor<data.ids.length){
                    int end=Math.min(cursor+64,data.ids.length);
                    for(;cursor<end;cursor++){
                        LatLng position=data.positions[cursor];
                        if(position==null||!visibleBounds.contains(position.latitude,position.longitude))continue;
                        Point pixel=projection.toScreenLocation(position);
                        if(pixel!=null&&pixel.x>=0&&pixel.y>=0&&pixel.x<width&&pixel.y<height){
                            xs[size]=pixel.x;
                            ys[size]=pixel.y;
                            sources[size++]=cursor;
                        }
                    }
                    if(cursor<data.ids.length&&System.nanoTime()>=deadline){
                        view.postOnAnimation(this);
                        return;
                    }
                }
                projectionTask=null;
                int visible=size;
                clusterWorker.execute(()->{
                    if(ticket!=viewportGeneration)return;
                    List<ScreenClusters.Cluster> cells=ScreenClusters.group(
                        Arrays.copyOf(xs,visible),Arrays.copyOf(ys,visible),originX,originY,cell,100);
                    List<RenderCluster> result=new ArrayList<>(cells.size());
                    for(ScreenClusters.Cluster item:cells){
                        long[] ids=new long[item.members.length];
                        for(int i=0;i<ids.length;i++)ids[i]=data.ids[sources[item.members[i]]];
                        LatLng singleton=ids.length==1?data.positions[sources[item.members[0]]]:null;
                        result.add(new RenderCluster(new ClusterKey(ids),new Point(item.x,item.y),singleton));
                    }
                    handler.post(()->publishClusters(ticket,result,data.ids.length,visible,started));
                });
            }
        };
        view.postOnAnimation(projectionTask);
    }

    private void publishClusters(long ticket,List<RenderCluster> clusters,int matches,int visible,long started){
        if(!active||ticket!=viewportGeneration||experiment==null||experiment.scopeToken()!=displayedScope)return;
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
        if(ids.length>MAX_CLUSTER_PHOTOS){
            float zoom=map.getMapStatus().zoom;
            if(zoom>=20.5f)Toast.makeText(this,"此处照片超过一万张，请移动地图缩小范围",Toast.LENGTH_LONG).show();
            else map.animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(resource.marker.getPosition(),Math.min(21f,zoom+2)));
            return;
        }
        long selection=++photoSelection;
        openingCluster=true;
        showGalleryStatus("正在打开照片",null);
        boolean accepted=repository.metadataForViewport(ids,metadata->{
            List<PhotoItem> items=null;
            if(metadata!=null){
                items=new ArrayList<>(metadata.length);
                for(MediaStoreLookup.Metadata item:metadata){
                    items.add(new PhotoItem(item.uri,item.dateTaken,item.mediaVersion));
                }
                Comparator<Long> newest=Comparator.nullsLast(Comparator.reverseOrder());
                items.sort((a,b)->newest.compare(a.dateTaken,b.dateTaken));
            }
            List<PhotoItem> photos=items;
            handler.post(()->finishClusterSelection(selection,repository,photos));
        });
        if(!accepted){
            openingCluster=false;
            showGalleryStatus("照片暂时无法打开 · 重试",()->openCluster(resource));
        }
    }

    private void finishClusterSelection(long selection,MapDataRepository repository,List<PhotoItem> items){
        if(!active||selection!=photoSelection||experiment!=repository)return;
        openingCluster=false;
        if(items==null||repository.scopeToken()!=displayedScope){
            checkRepository();
            Toast.makeText(this,"照片已变化，请重新选择",Toast.LENGTH_SHORT).show();
            return;
        }
        rebuiltAfterMediaChange=false;
        showViewportStatus(false);
        if(items.size()==1){openPreview(items,items.get(0));return;}
        cancelMarkerCovers();
        group.clear();
        group.addAll(items);
        oldestFirst=false;
        groupMode=true;
        restorePosition=-1;
        renderList();
        panel.scrollToPhoto(0,0);
    }

    private void cancelRequests(){
        viewportDirty=true;
        viewportGeneration++;
        photoSelection++;
        openingCluster=false;
        handler.removeCallbacks(viewportRefresh);
        if(projectionTask!=null){view.removeCallbacks(projectionTask);projectionTask=null;}
        clusterWorker.getQueue().clear();
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
        Uri collection=android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri uri=android.content.ContentUris.withAppendedId(collection,id);
        resource.coverRequest=thumbnails.tryLoad(uri,0,160,bitmap->{
            resource.coverRequest=null;
            if(!active||groupMode||markerClicks.get(resource.clickId)!=resource
                ||experiment==null||experiment.scopeToken()!=displayedScope)return;
            if(bitmap==null){resource.coverAttempt++;loadMarkerCover(resource);return;}
            Bitmap next=markerBitmap(bitmap,ids.length);
            BitmapDescriptor descriptor=BitmapDescriptorFactory.fromBitmap(next);
            resource.marker.setIcon(descriptor);
            resource.descriptor.recycle();
            resource.bitmap.recycle();
            resource.descriptor=descriptor;
            resource.bitmap=next;
            resource.hasCover=true;
        });
        if(resource.coverRequest==null){
            resource.coverRetry=()->{resource.coverRetry=null;loadMarkerCover(resource);};
            handler.postDelayed(resource.coverRetry,120);
        }
    }

    private void cancelMarkerCover(MarkerResource resource){
        if(resource.coverRequest!=null){resource.coverRequest.cancel();resource.coverRequest=null;}
        if(resource.coverRetry!=null){handler.removeCallbacks(resource.coverRetry);resource.coverRetry=null;}
    }

    private void cancelMarkerCovers(){
        for(MarkerResource resource:markers.values())cancelMarkerCover(resource);
    }
    private Bitmap markerBitmap(Bitmap original,int count){int n=dp(48),border=dp(2);Bitmap out=Bitmap.createBitmap(n,n,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(out);Paint p=new Paint(3);p.setColor(SURFACE);c.drawRoundRect(0,0,n,n,dp(9),dp(9),p);p.setColor(PLACEHOLDER);c.drawRoundRect(border,border,n-border,n-border,dp(7),dp(7),p);if(original!=null){float scale=Math.max((float)(n-2*border)/original.getWidth(),(float)(n-2*border)/original.getHeight());Matrix m=new Matrix();m.setScale(scale,scale);m.postTranslate((n-original.getWidth()*scale)/2,(n-original.getHeight()*scale)/2);BitmapShader shader=new BitmapShader(original,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);shader.setLocalMatrix(m);p.setShader(shader);c.drawRoundRect(border,border,n-border,n-border,dp(7),dp(7),p);p.setShader(null);}if(count>1){String text=MarkerCount.format(count);p.setTextSize(dp(10));p.setTypeface(Typeface.DEFAULT_BOLD);float w=Math.max(dp(19),p.measureText(text)+dp(8));p.setColor(0xe6263a49);c.drawRoundRect(n-w-dp(1),n-dp(17),n-dp(1),n-dp(1),dp(8),dp(8),p);p.setColor(-1);p.setTextAlign(Paint.Align.CENTER);c.drawText(text,n-w/2-dp(1),n-dp(5),p);}return out;}
    static void verifyPreviewScope(MapDataRepository.Callback<Boolean> callback){
        if(experiment==null){callback.complete(false);return;}
        experiment.verifyExistingScope(callback);
    }
    static void suspendPreviewScope(){if(experiment!=null)experiment.suspend();}
    private void openPhoto(PhotoItem photo){
        if(canLoadGalleryImages())openPreview(group,photo);
    }
    private void openPreview(List<PhotoItem> source,PhotoItem selected){
        if(source.isEmpty()||experiment==null)return;int index=source.indexOf(selected);if(index<0)return;
        pendingPhotoReturn=source.size()>1?snapshotGallery():null;
        startActivity(new Intent(this,PhotoPreviewActivity.class).putExtra("session",PreviewSessionStore.put(source)).putExtra("index",index));
    }
    private PendingPhotoReturn snapshotGallery(){
        if(!groupMode||displayedScope<0||experiment==null||panel==null)return null;
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
        viewportPhase="尚未筛选";
        viewportMatches=viewportLoaded=0;
        cancelMarkerCovers();
        for(MarkerResource resource:markers.values())resource.release();
        markers.clear();
        markerClicks.clear();
        clearVisibleImages();
        thumbnails.clear();
        group.clear();
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
        if(root==null||scopeCover!=null)return;
        scopeCover=label("正在检查照片授权",14,SECONDARY);
        scopeCover.setGravity(Gravity.CENTER);
        scopeCover.setBackgroundColor(MAP_BACKGROUND);
        scopeCover.setElevation(dp(12));
        scopeCover.setClickable(true);
        scopeCover.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(scopeCover,new FrameLayout.LayoutParams(-1,-1));
    }
    private void hideScopeCover(){if(scopeCover!=null){root.removeView(scopeCover);scopeCover=null;}}
    private void resumeMapView(){if(active&&view!=null&&!mapResumed){view.onResume();mapResumed=true;}}
    @Override protected void onResume(){super.onResume();active=true;if(view!=null){resumeMapView();resumeExperiment();}}
    @Override protected void onPause(){
        if(displayedScope>=0)showScopeCover();
        active=false;
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
    @Override protected void onDestroy(){clearData();thumbnails.close();clusterWorker.shutdownNow();if(view!=null)view.onDestroy();if(!isChangingConfigurations()&&isFinishing()&&experiment!=null){experiment.close();experiment=null;pendingPhotoReturn=null;}super.onDestroy();}
    private final class FitIcon extends android.graphics.drawable.Drawable{private final Paint p=new Paint(3);public void draw(Canvas c){android.graphics.Rect b=getBounds();float x=b.exactCenterX(),y=b.exactCenterY(),r=Math.min(b.width(),b.height())*.32f;p.setColor(ACCENT);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setStrokeCap(Paint.Cap.ROUND);c.drawCircle(x,y,r,p);c.drawLine(x,b.top,x,y-r,p);c.drawLine(x,y+r,x,b.bottom,p);c.drawLine(b.left,y,x-r,y,p);c.drawLine(x+r,y,b.right,y,p);p.setStyle(Paint.Style.FILL);c.drawCircle(x,y,dp(2),p);}public void setAlpha(int a){}public void setColorFilter(android.graphics.ColorFilter f){}public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}}
}
