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
    private int TEXT, SECONDARY, ACCENT, PLACEHOLDER, SURFACE, FLOATING, CHIP, DIVIDER, MAP_BACKGROUND, MENU_ICON;
    private boolean darkMode;
    private MapView view; private BaiduMap map; private FrameLayout root; private LinearLayout panel, header;
    private TextView count, title, quantity, empty, networkStatus; private ListView grid; private ImageView fit;
    private AreaAdapter adapter; private ThumbnailLoader thumbnails; private CancellationSignal pending;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refresh=this::refreshClusters;
    private final Runnable viewportRefresh=this::queryViewport;
    private final ArrayList<PhotoItem> samples=new ArrayList<>(), area=new ArrayList<>(), group=new ArrayList<>();
    private final Map<PhotoItem,LatLng> positions=new IdentityHashMap<>();
    private final Map<String,MarkerResource> markers=new HashMap<>();
    private final Map<String,int[]> overviewMembers=new HashMap<>();
    private long generation; private boolean expanded, groupMode, loading, privacyAccepted, oldestFirst;
    private int restorePosition=-1, restoreOffset, unmapped, withoutGps, unavailable, excluded;
    private String loadError; private int sampleLoads, bottomInset; private boolean networkCallbackRegistered; private Bundle saved;
    private LocationClient locationClient; private BDAbstractLocationListener locationListener;
    private static MapStatus transientMapStatus;
    private static MapDataRepository experiment;
    private static PendingPhotoReturn pendingPhotoReturn;
    private static final class PendingPhotoReturn {
        final MapDataRepository repository;final long scopeToken;final ArrayList<PhotoItem> photos;
        final boolean oldestFirst;final int scroll,offset;
        PendingPhotoReturn(MapDataRepository repository,long scopeToken,List<PhotoItem> photos,boolean oldestFirst,int scroll,int offset){
            this.repository=repository;this.scopeToken=scopeToken;this.photos=new ArrayList<>(photos);this.oldestFirst=oldestFirst;this.scroll=scroll;this.offset=offset;
        }
    }
    private boolean active, mapResumed, viewportDirty=true;
    private long displayedScope=-1;
    private View scopeCover;
    private long viewportGeneration;
    private final Runnable checkLoad=this::checkRepository;
    private String scopeLabel="未授权";
    private String viewportPhase="尚未筛选";
    private int viewportMatches, viewportLoaded, viewportCacheHits;
    private ViewportBounds cachedViewport;
    private OverviewData overview;
    private static final class OverviewData {
        final long[] ids;final double[] latitudes,longitudes;
        OverviewData(int size){ids=new long[size];latitudes=new double[size];longitudes=new double[size];}
    }
    private final Runnable locationTimeout=()->{stopLocation();Toast.makeText(this,"定位超时，请重试",Toast.LENGTH_SHORT).show();};
    private final ConnectivityManager.NetworkCallback networkCallback=new ConnectivityManager.NetworkCallback(){
        @Override public void onAvailable(Network network){handler.post(MainActivity.this::updateNetworkStatus);}
        @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities capabilities){handler.post(MainActivity.this::updateNetworkStatus);}
        @Override public void onLost(Network network){handler.postDelayed(MainActivity.this::updateNetworkStatus,500);}
    };
    private static final Comparator<PhotoItem> NEWEST=(a,b)->Long.compare(b.dateTaken==null?0:b.dateTaken,a.dateTaken==null?0:a.dateTaken);
    private static final class MarkerResource {
        final Marker marker; BitmapDescriptor descriptor; Bitmap bitmap; boolean requested,hasCover;
        MarkerResource(Marker m,BitmapDescriptor d,Bitmap b){marker=m;descriptor=d;bitmap=b;}
        void release(){marker.remove();descriptor.recycle();bitmap.recycle();}
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView label(String text,int size,int color){TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
    @Override protected void attachBaseContext(Context base){super.attachBaseContext(base);Appearance.applyOverride(this,base);}
    @Override public void onCreate(Bundle state){super.onCreate(state);saved=state;darkMode=(getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
        TEXT=getColor(R.color.app_text);SECONDARY=getColor(R.color.app_secondary);ACCENT=getColor(R.color.app_accent);PLACEHOLDER=getColor(R.color.app_placeholder);SURFACE=getColor(R.color.app_surface);FLOATING=getColor(R.color.app_floating);CHIP=getColor(R.color.app_chip);DIVIDER=getColor(R.color.app_divider);MAP_BACKGROUND=getColor(R.color.app_map_background);MENU_ICON=getColor(R.color.app_menu_icon);
        thumbnails=new ThumbnailLoader(getContentResolver());
        TextView waiting=label("等待隐私选择",14,TEXT);setContentView(waiting);
        if(PrivacyConsent.isCurrent(getPreferences(MODE_PRIVATE).getInt(PrivacyConsent.KEY,0))){privacyAccepted=true;initializeMap();return;}
        new AlertDialog.Builder(this).setTitle("百度地图与定位隐私说明").setMessage("图迹使用百度地图 SDK。点击当前位置后，图迹会请求前台位置权限，并使用百度定位 SDK 获取当前位置，SDK 可能读取 Wi-Fi 状态辅助定位；百度按其隐私政策处理定位请求。图迹不进行后台持续定位、不建立位置历史、不上传位置到图迹自有服务。拒绝定位权限不影响地图和照片浏览。请先阅读百度隐私政策；不同意时不初始化 SDK。")
            .setNeutralButton("查看隐私政策",(d,w)->{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://map.baidu.com/zt/client/privacy/index.html")));finish();})
            .setNegativeButton("不同意",(d,w)->finish()).setPositiveButton("同意并打开",(d,w)->{if(!getPreferences(MODE_PRIVATE).edit().putInt(PrivacyConsent.KEY,PrivacyConsent.VERSION).commit()){Toast.makeText(this,"无法保存隐私选择，请重试",Toast.LENGTH_LONG).show();finish();return;}privacyAccepted=true;initializeMap();}).setOnCancelListener(d->finish()).show();
    }
    private void initializeMap(){try {
        String ak=effectiveBaiduAk();if(ak!=null){SDKInitializer.setApiKey(ak);LocationClient.setKey(ak);}
        SDKInitializer.setAgreePrivacy(getApplicationContext(),true);SDKInitializer.initialize(getApplicationContext());SDKInitializer.setCoordType(CoordType.BD09LL);
        BaiduMapOptions options=new BaiduMapOptions();
        if(saved!=null&&transientMapStatus!=null)options.mapStatus(transientMapStatus);
        root=new FrameLayout(this);root.setBackgroundColor(MAP_BACKGROUND);view=new MapView(this,options);view.showZoomControls(false);root.addView(view);setContentView(root);resumeMapView();map=view.getMap();map.setMapType(BaiduMap.MAP_TYPE_NORMAL);map.setMapThemeMode(darkMode?BMThemeMode.MapThemeModeDark:BMThemeMode.MapThemeModeDefault);view.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol||b-t!=ob-ot)schedule();});
        map.setOnMarkerClickListener(m->{Bundle b=m.getExtraInfo();if(b!=null&&b.getBoolean("overview")){int[] members=overviewMembers.get(b.getString("overviewKey"));if(members!=null&&members.length<=10000)openOverviewGroup(members);else{float zoom=map.getMapStatus().zoom;if(zoom>=20.5f)Toast.makeText(this,"此处照片超过一万张，请移动地图缩小范围",Toast.LENGTH_LONG).show();else map.animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(m.getPosition(),Math.min(21f,zoom+2)));}return true;}int[] ids=b==null?null:b.getIntArray("samples");if(ids!=null)showCluster(ids);return true;});
        map.setOnMapStatusChangeListener(new BaiduMap.OnMapStatusChangeListener(){public void onMapStatusChangeStart(MapStatus s){cancelRequests();}public void onMapStatusChangeStart(MapStatus s,int reason){cancelRequests();}public void onMapStatusChange(MapStatus s){}public void onMapStatusChangeFinish(MapStatus s){schedule();}});
        buildUi();
        if(experiment==null && getPreferences(MODE_PRIVATE).getBoolean(confirmationKey(),false) && supportedGallery())experiment=createGallery();
        if(saved!=null){expanded=saved.getBoolean("expanded");oldestFirst=saved.getBoolean("oldestFirst");restorePosition=saved.getInt("scroll",0);restoreOffset=saved.getInt("scrollOffset",0);updatePanel();}
        if(active)resumeExperiment();
        schedule();
    }catch(RuntimeException e){LinearLayout failure=new LinearLayout(this);failure.setOrientation(LinearLayout.VERTICAL);failure.setGravity(Gravity.CENTER);TextView message=label("地图加载失败，请检查百度地图 AK",14,SECONDARY);message.setGravity(Gravity.CENTER);failure.addView(message);Button settings=new Button(this);settings.setText("设置百度地图 AK");settings.setOnClickListener(v->showBaiduAkSettings());failure.addView(settings);setContentView(failure);}}
    private void buildUi(){
        count=label(galleryStatus("未启用"),12,ACCENT);
        TextView menu=label("•••",18,MENU_ICON);menu.setGravity(Gravity.CENTER);menu.setContentDescription("更多菜单");menu.setBackground(shape(FLOATING,24));menu.setElevation(dp(3));menu.setOnClickListener(v->showMenu(menu));
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP|Gravity.RIGHT);bp.setMargins(0,dp(12),dp(16),0);root.addView(menu,bp);
        networkStatus=label("网络不可用，地图可能无法加载",12,SECONDARY);networkStatus.setPadding(dp(12),0,dp(12),0);networkStatus.setBackground(shape(FLOATING,12));
        FrameLayout.LayoutParams np=new FrameLayout.LayoutParams(-2,dp(36),Gravity.TOP|Gravity.LEFT);np.setMargins(dp(16),dp(76),0,0);root.addView(networkStatus,np);updateNetworkStatus();
        fit=new ImageView(this);fit.setImageDrawable(new FitIcon());fit.setPadding(dp(12),dp(12),dp(12),dp(12));fit.setBackground(shape(SURFACE,24));fit.setElevation(dp(3));fit.setContentDescription("定位当前位置");fit.setOnClickListener(v->requestCurrentLocation());root.addView(fit,new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.RIGHT|Gravity.BOTTOM));
        panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setBackgroundColor(SURFACE);panel.setElevation(dp(8));panel.setClickable(true);
        header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(8),0,dp(16),0);
        ImageView close=new ImageView(this);close.setImageDrawable(new BackArrowIcon());close.setPadding(dp(14),dp(14),dp(14),dp(14));close.setContentDescription("返回地图");close.setOnClickListener(v->closePhotoPanel());header.addView(close,new LinearLayout.LayoutParams(dp(48),dp(56)));
        title=label("此处照片",17,TEXT);title.setTypeface(null,Typeface.BOLD);header.addView(title,new LinearLayout.LayoutParams(0,dp(56),1));
        TextView sort=label("排序 ▾",13,ACCENT);sort.setGravity(Gravity.CENTER);sort.setBackground(shape(CHIP,16));sort.setContentDescription("调整照片排序");sort.setOnClickListener(v->showSortMenu(sort));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(72),dp(48));sp.topMargin=dp(4);sp.bottomMargin=dp(4);header.addView(sort,sp);panel.addView(header);
        quantity=label("0 张照片",13,SECONDARY);quantity.setPadding(dp(16),0,dp(16),0);panel.addView(quantity,new LinearLayout.LayoutParams(-1,dp(36)));
        View divider=new View(this);divider.setBackgroundColor(DIVIDER);LinearLayout.LayoutParams line=new LinearLayout.LayoutParams(-1,dp(1));line.leftMargin=dp(16);line.rightMargin=dp(16);panel.addView(divider,line);
        empty=label("暂无照片",13,SECONDARY);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(16),0,dp(16),0);panel.addView(empty,new LinearLayout.LayoutParams(-1,dp(64)));
        grid=new DraggableScrollList();grid.setDivider(null);grid.setDividerHeight(0);grid.setPadding(dp(16),0,dp(16),dp(16));grid.setClipToPadding(false);adapter=new AreaAdapter();grid.setAdapter(adapter);panel.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(panel,new FrameLayout.LayoutParams(-1,-1));root.setOnApplyWindowInsetsListener((v,insets)->{int safe=android.os.Build.VERSION.SDK_INT>=30?insets.getInsets(WindowInsets.Type.systemGestures()).bottom:insets.getSystemWindowInsetBottom();if(bottomInset!=safe){bottomInset=safe;panel.setPadding(0,0,0,safe);updatePanel();}return insets;});renderList();updatePanel();
    }
    private void showSortMenu(View anchor){PopupMenu menu=new PopupMenu(this,anchor);android.view.MenuItem latest=menu.getMenu().add("拍摄时间：最新在前");android.view.MenuItem earliest=menu.getMenu().add("拍摄时间：最早在前");latest.setCheckable(true);earliest.setCheckable(true);latest.setChecked(!oldestFirst);earliest.setChecked(oldestFirst);latest.setOnMenuItemClickListener(item->{setPhotoOrder(false);return true;});earliest.setOnMenuItemClickListener(item->{setPhotoOrder(true);return true;});menu.show();}
    private void setPhotoOrder(boolean oldest){if(oldestFirst==oldest)return;oldestFirst=oldest;sortGroup();restorePosition=-1;renderList();grid.setSelection(0);}
    private void sortGroup(){Collections.sort(group,(a,b)->{if(a.dateTaken==null)return b.dateTaken==null?0:1;if(b.dateTaken==null)return -1;return oldestFirst?Long.compare(a.dateTaken,b.dateTaken):Long.compare(b.dateTaken,a.dateTaken);});}
    private void showMenu(View anchor){
        PopupMenu p=new PopupMenu(this,anchor);
        android.view.MenuItem night=p.getMenu().add("暗夜模式");night.setCheckable(true);night.setChecked(darkMode);night.setOnMenuItemClickListener(i->{Appearance.setManual(this,!darkMode);recreate();return true;});
        p.getMenu().add("刷新照片数据").setOnMenuItemClickListener(i->{if(experiment!=null){experiment.stopAndClear();resumeExperiment();}return true;});
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
    private String galleryStatus(String status){return (BuildConfig.PERSONAL_DEVICE_RELEASE?"照片数据 · ":"实验模式 · ")+status;}
    private void enableExperiment(){
        if(!supportedGallery()){new AlertDialog.Builder(this).setMessage("此功能仅支持指定的 vivo V2502A / PD2502 设备，当前设备或构建不符合限制。").setPositiveButton("知道了",null).show();return;}
        new AlertDialog.Builder(this).setTitle(BuildConfig.PERSONAL_DEVICE_RELEASE?"启用个人图库":"启用主卷全图库实验模式")
            .setMessage("当前设备：V2502A / PD2502。仅访问当前授权的主卷照片及其图库位置，读取结果保存在内存中。此功能仍是个人设备实验版本，身份和坐标并非对所有照片或其他设备得到保证。授权范围变化时会重新检查。")
            .setNegativeButton("取消",null).setPositiveButton("确认启用",(d,w)->{getPreferences(MODE_PRIVATE).edit().putBoolean(confirmationKey(),true).apply();if(experiment!=null)experiment.close();experiment=createGallery();requestPhotoAccess();}).show();
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
        if(!hasPhotoAccess()){pendingPhotoReturn=null;experiment.stopAndClear();clearData();hideScopeCover();scopeLabel="未授权";loadError="照片权限未授予，请在菜单中启用图库并授权";count.setText(galleryStatus("无权限"));renderList();return;}
        scopeLabel=android.os.Build.VERSION.SDK_INT>=34 && checkSelfPermission("android.permission.READ_MEDIA_IMAGES")!=android.content.pm.PackageManager.PERMISSION_GRANTED?"部分照片":"完整照片";
        showScopeCover();loading=true;count.setText("正在检查授权范围");
        experiment.resumeChecked(state->handler.post(()->{if(active)checkRepository();}));
        handler.removeCallbacks(checkLoad);handler.postDelayed(checkLoad,200);
    }
    private void checkRepository(){
        if(!active||experiment==null||count==null)return;
        MapDataRepository.State state=experiment.state();
        if(state==MapDataRepository.State.READY){long token=experiment.scopeToken();if(token<0)return;boolean changed=displayedScope!=token;if(changed){clearData();displayedScope=token;viewportDirty=true;}loading=false;loadError=null;count.setText(galleryStatus(experiment.validCount()+" 张有位置"));restorePreviewReturn();hideScopeCover();if(viewportDirty||cachedViewport==null){viewportDirty=false;schedule();}}
        else if(state==MapDataRepository.State.LOADING||state==MapDataRepository.State.IDLE){loading=true;count.setText("正在读取照片位置");handler.removeCallbacks(checkLoad);handler.postDelayed(checkLoad,300);}
        else {pendingPhotoReturn=null;clearData();hideScopeCover();loading=false;loadError=state==MapDataRepository.State.CAPACITY_EXCEEDED?"照片超过 100,000 张容量限制":state==MapDataRepository.State.ACCESS_DENIED?"照片访问被拒绝":state==MapDataRepository.State.VOLUME_UNSUPPORTED?"当前存储卷不符合主卷限制":state==MapDataRepository.State.SCOPE_CHANGED?"授权范围发生变化，请刷新":state==MapDataRepository.State.IDENTITY_UNVERIFIED?"设备身份未验证":"照片位置读取失败，请刷新";count.setText(galleryStatus("读取失败"));renderList();}
    }
    private void restorePreviewReturn(){
        PendingPhotoReturn pending=pendingPhotoReturn;if(pending==null)return;pendingPhotoReturn=null;
        if(pending.repository!=experiment||pending.scopeToken<0||pending.scopeToken!=experiment.scopeToken()){
            Toast.makeText(this,"照片授权范围已变化，请重新选择照片",Toast.LENGTH_LONG).show();return;
        }
        if(groupMode)return;
        group.clear();group.addAll(pending.photos);oldestFirst=pending.oldestFirst;groupMode=true;expanded=true;
        restorePosition=pending.scroll;restoreOffset=pending.offset;renderList();
    }
    private void showDiagnostics(){
        String message="数据模式："+(experiment==null?"未启用":"主卷实验")+"\n授权范围："+scopeLabel+"\n"+(experiment==null?"身份：未验证":experiment.diagnostics())+"\n视野状态："+viewportPhase+" · 匹配 "+viewportMatches+" · 已读取 "+viewportLoaded+" · 地图标记 "+markers.size()+" · 视野复用 "+viewportCacheHits+"\n"+thumbnails.diagnosticCounts();
        new AlertDialog.Builder(this).setTitle("数据诊断").setMessage(message).setNegativeButton("关闭",null).setPositiveButton("复制",(d,w)->{android.content.ClipboardManager clip=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(clip!=null)clip.setPrimaryClip(android.content.ClipData.newPlainText("图迹诊断",message));}).show();
    }
    private void updatePanel(){if(panel==null)return;panel.setVisibility(groupMode?View.VISIBLE:View.GONE);FrameLayout.LayoutParams f=(FrameLayout.LayoutParams)fit.getLayoutParams();f.rightMargin=dp(16);f.bottomMargin=bottomInset+dp(16);fit.setLayoutParams(f);fit.setVisibility(groupMode?View.GONE:View.VISIBLE);grid.setVisibility(adapter.items.isEmpty()?View.GONE:View.VISIBLE);empty.setVisibility(adapter.items.isEmpty()?View.VISIBLE:View.GONE);}
    private void closePhotoPanel(){groupMode=false;expanded=false;renderList();}
    @Override public void onBackPressed(){if(groupMode){closePhotoPanel();return;}super.onBackPressed();}
    private void renderList(){if(adapter==null)return;List<PhotoItem> list=groupMode?group:area;adapter.setPhotos(list);title.setText("此处照片");quantity.setText(list.size()+" 张照片");empty.setText(loading?"正在读取照片位置":loadError!=null?loadError:"暂无照片");updatePanel();if(restorePosition>=0&&groupMode&&!adapter.items.isEmpty()){grid.setSelectionFromTop(restorePosition,restoreOffset);restorePosition=-1;restoreOffset=0;}}
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
    private void schedule(){if(!active)return;viewportDirty=true;handler.removeCallbacks(viewportRefresh);if(experiment!=null && experiment.state()==MapDataRepository.State.READY){viewportDirty=false;handler.postDelayed(viewportRefresh,200);}}
    private void queryViewport(){
        if(!active||experiment==null||map==null||view.getWidth()==0||view.getHeight()==0)return;
        int width=view.getWidth(),height=view.getHeight();
        Point[] corners={new Point(0,0),new Point(width,0),new Point(0,height),new Point(width,height)};
        double[] latitudes=new double[corners.length],longitudes=new double[corners.length];
        for(int i=0;i<corners.length;i++){LatLng point=map.getProjection().fromScreenLocation(corners[i]);if(point==null)return;latitudes[i]=point.latitude;longitudes[i]=point.longitude;}
        LatLng center=map.getProjection().fromScreenLocation(new Point(width/2,height/2));if(center==null)return;
        final ViewportBounds visibleBounds;
        try{visibleBounds=GeographicViewport.fromCorners(latitudes,longitudes,center.longitude,.03);}catch(IllegalArgumentException invalid){return;}
        final long ticket=++viewportGeneration;
        if(cachedViewport!=null&&cachedViewport.covers(visibleBounds.south,visibleBounds.north,visibleBounds.west,visibleBounds.east)){
            viewportCacheHits++;viewportPhase="复用已读取视野";if(overview!=null)renderOverview(ticket,overview);else refreshClusters();return;
        }
        final ViewportBounds requestedBounds=GeographicViewport.fromCorners(latitudes,longitudes,center.longitude,.1);
        double south=requestedBounds.south,north=requestedBounds.north,west=requestedBounds.west,east=requestedBounds.east;
        cachedViewport=null;overview=null;clearMarkers();samples.clear();area.clear();viewportMatches=0;viewportLoaded=0;
        viewportPhase="正在筛选";renderList();
        final MapDataRepository repository=experiment;
        boolean accepted=repository.query(south,north,west,east,window->{
            if(window==null){handler.post(()->{if(active&&ticket==viewportGeneration)viewportPhase="区域筛选失败";});return;}
            int total=window.size();
            // Large regions keep only primitive location arrays; map markers zoom into detail.
            if(total>10000){
                OverviewData data=new OverviewData(total);
                for(int i=0;i<total;i++){MapDataRepository.Point point=window.pointAt(i);if(point==null)return;data.ids[i]=point.id;data.latitudes[i]=point.latitude;data.longitudes[i]=point.longitude;}
                handler.post(()->{if(active&&ticket==viewportGeneration&&experiment==repository&&repository.state()==MapDataRepository.State.READY){overview=data;cachedViewport=requestedBounds;viewportMatches=viewportLoaded=total;viewportPhase="全球概览";count.setText(galleryStatus(repository.validCount()+" 张有位置"));renderOverview(ticket,data);}});
                return;
            }
            handler.post(()->{if(active&&ticket==viewportGeneration){viewportMatches=total;viewportPhase=total==0?"当前视野无匹配照片":"正在读取视野照片";count.setText(galleryStatus(repository.validCount()+" 张有位置"));if(total==0)cachedViewport=requestedBounds;}});
            for(int from=0;from<total;from+=512){
                int n=Math.min(512,total-from);MapDataRepository.Point[] points=new MapDataRepository.Point[n];long[] ids=new long[n];
                for(int i=0;i<n;i++){points[i]=window.pointAt(from+i);if(points[i]==null)return;ids[i]=points[i].id;}
                MediaStoreLookup.Metadata[] metadata=repository.metadataBatchNow(ids);
                if(metadata==null){handler.post(()->{if(active&&ticket==viewportGeneration){viewportPhase="照片元数据读取失败或范围已变化";checkRepository();}});return;}
                ArrayList<PhotoItem> batch=new ArrayList<>(n);
                for(int i=0;i<n;i++)batch.add(new PhotoItem(metadata[i].uri,points[i].latitude,points[i].longitude,metadata[i].dateTaken,metadata[i].dateFallback,metadata[i].mediaVersion,metadata[i].relativePath));
                final boolean last=from+n==total;
                handler.post(()->{
                    if(!active||ticket!=viewportGeneration||experiment==null||experiment.state()!=MapDataRepository.State.READY)return;
                    for(PhotoItem item:batch){samples.add(item);LatLng point=CoordinateAdapter.adapt(item.latitude,item.longitude);if(point!=null)positions.put(item,point);}
                    viewportLoaded+=batch.size();viewportPhase=last?"视野读取完成":"正在读取视野照片";if(last)cachedViewport=requestedBounds;
                    handler.removeCallbacks(refresh);
                    if(viewportLoaded==batch.size())refreshClusters();else handler.postDelayed(refresh,250);
                });
            }
        });
        if(!accepted)viewportPhase="区域查询未启动，请移动地图重试";
    }
    /** Projects the primitive overview in short UI batches; it never constructs whole-gallery photo objects. */
    private void renderOverview(final long ticket,final OverviewData data){
        if(!active||overview!=data||map==null||view==null)return;
        viewportPhase="正在准备全球概览";count.setText("全球概览");
        final int[] xs=new int[data.ids.length],ys=new int[data.ids.length],sources=new int[data.ids.length];
        final int[] cursor={0},countVisible={0};
        final int width=view.getWidth(),height=view.getHeight();
        Runnable task=new Runnable(){@Override public void run(){
            if(!active||ticket!=viewportGeneration||overview!=data||map==null)return;
            int end=Math.min(data.ids.length,cursor[0]+256);
            for(int i=cursor[0];i<end;i++){
                LatLng point=CoordinateAdapter.adapt(data.latitudes[i],data.longitudes[i]);if(point==null)continue;
                Point pixel=map.getProjection().toScreenLocation(point);
                if(pixel!=null&&pixel.x>=0&&pixel.y>=0&&pixel.x<width&&pixel.y<height){int at=countVisible[0]++;xs[at]=pixel.x;ys[at]=pixel.y;sources[at]=i;}
            }
            cursor[0]=end;if(end<data.ids.length){handler.post(this);return;}
            int[] px=Arrays.copyOf(xs,countVisible[0]),py=Arrays.copyOf(ys,countVisible[0]);
            Point origin=map.getProjection().toScreenLocation(new LatLng(23,113));int ox=origin==null?0:origin.x,oy=origin==null?0:origin.y;
            int cell=ScreenClusters.stableCellPixels(width,height,Math.max(1,dp(72)),100);
            Set<String> retained=new HashSet<>();Map<String,int[]> nextMembers=new HashMap<>();
            for(ScreenClusters.Cluster c:ScreenClusters.group(px,py,ox,oy,cell,100)){
                int[] members=new int[c.members.length];for(int i=0;i<members.length;i++)members[i]=sources[c.members[i]];
                String key="overview:"+Arrays.toString(members);retained.add(key);nextMembers.put(key,members);
                LatLng position=members.length==1?CoordinateAdapter.adapt(data.latitudes[members[0]],data.longitudes[members[0]]):map.getProjection().fromScreenLocation(new Point(c.x,c.y));if(position==null)continue;
                MarkerResource marker=markers.get(key);
                if(marker==null){Bitmap bitmap=markerBitmap(null,members.length);BitmapDescriptor descriptor=BitmapDescriptorFactory.fromBitmap(bitmap);Bundle info=new Bundle();info.putBoolean("overview",true);info.putString("overviewKey",key);Marker overlay=(Marker)map.addOverlay(new MarkerOptions().position(position).icon(descriptor).anchor(.5f,.5f).zIndex(members.length).extraInfo(info));marker=new MarkerResource(overlay,descriptor,bitmap);markers.put(key,marker);}else marker.marker.setPosition(position);
                if(!marker.hasCover&&!marker.requested){int first=members[0];Uri uri=android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),data.ids[first]);PhotoItem cover=new PhotoItem(uri,data.latitudes[first],data.longitudes[first],null,false,0,null);loadCover(key,marker,Collections.singletonList(cover),0,members.length,generation);}
            }
            Iterator<Map.Entry<String,MarkerResource>> it=markers.entrySet().iterator();while(it.hasNext()){Map.Entry<String,MarkerResource> entry=it.next();if(!retained.contains(entry.getKey())){entry.getValue().release();it.remove();}}
            overviewMembers.clear();overviewMembers.putAll(nextMembers);viewportPhase="全球概览";
        }};
        handler.post(task);
    }
    private void openOverviewGroup(int[] selected){
        final OverviewData data=overview;final MapDataRepository repository=experiment;final long ticket=viewportGeneration;
        if(data==null||repository==null||selected.length==0)return;
        long[] ids=new long[selected.length];for(int i=0;i<ids.length;i++)ids[i]=data.ids[selected[i]];
        count.setText("正在打开照片");
        boolean accepted=repository.metadataForViewport(ids,metadata->handler.post(()->{
            if(!active||ticket!=viewportGeneration||overview!=data||experiment!=repository)return;
            count.setText("全球概览");
            if(metadata==null){checkRepository();Toast.makeText(this,"照片已变化，请重试",Toast.LENGTH_SHORT).show();return;}
            ArrayList<PhotoItem> items=new ArrayList<>(metadata.length);
            for(int i=0;i<metadata.length;i++){int at=selected[i];MediaStoreLookup.Metadata item=metadata[i];items.add(new PhotoItem(item.uri,data.latitudes[at],data.longitudes[at],item.dateTaken,item.dateFallback,item.mediaVersion,item.relativePath));}
            if(items.size()==1){openPreview(items,items.get(0));return;}
            group.clear();group.addAll(items);oldestFirst=false;sortGroup();groupMode=true;expanded=true;restorePosition=-1;renderList();grid.setSelection(0);
        }));
        if(!accepted){count.setText("全球概览");Toast.makeText(this,"地图正在更新，请重试",Toast.LENGTH_SHORT).show();}
    }
    private void cancelRequests(){viewportGeneration++;handler.removeCallbacks(refresh);handler.removeCallbacks(viewportRefresh);}
    private void refreshClusters(){if(map==null||view.getWidth()==0||view.getHeight()==0)return;ArrayList<Integer> visible=new ArrayList<>();ArrayList<Point> pts=new ArrayList<>();for(int i=0;i<samples.size();i++){LatLng p=positions.get(samples.get(i));if(p==null)continue;Point pt=map.getProjection().toScreenLocation(p);if(pt!=null&&pt.x>=0&&pt.y>=0&&pt.x<view.getWidth()&&pt.y<view.getHeight()){visible.add(i);pts.add(pt);}}
        area.clear();for(int i:visible)area.add(samples.get(i));Collections.sort(area,NEWEST);if(groupMode&&restorePosition<0){restorePosition=grid.getFirstVisiblePosition();View first=grid.getChildAt(0);restoreOffset=first==null?0:first.getTop()-grid.getPaddingTop();}renderList();int[] xs=new int[pts.size()],ys=new int[pts.size()];for(int i=0;i<pts.size();i++){xs[i]=pts.get(i).x;ys[i]=pts.get(i).y;}
        Point gridOrigin=map.getProjection().toScreenLocation(new LatLng(23.0,113.0));int originX=gridOrigin==null?0:gridOrigin.x,originY=gridOrigin==null?0:gridOrigin.y;
        int cellPixels=ScreenClusters.stableCellPixels(view.getWidth(),view.getHeight(),Math.max(1,dp(72)),100);
        Set<String> retained=new HashSet<>();for(ScreenClusters.Cluster c:ScreenClusters.group(xs,ys,originX,originY,cellPixels,100)){int[] ids=new int[c.members.length];for(int i=0;i<ids.length;i++)ids[i]=visible.get(c.members[i]);String key=Arrays.toString(ids);retained.add(key);LatLng center=ids.length==1?positions.get(samples.get(ids[0])):map.getProjection().fromScreenLocation(new Point(c.x,c.y));if(center==null)continue;MarkerResource r=markers.get(key);if(r==null){Bitmap bm=markerBitmap(null,ids.length);BitmapDescriptor desc=BitmapDescriptorFactory.fromBitmap(bm);Bundle info=new Bundle();info.putIntArray("samples",ids);Marker m=(Marker)map.addOverlay(new MarkerOptions().position(center).icon(desc).anchor(.5f,.5f).zIndex(ids.length).extraInfo(info));r=new MarkerResource(m,desc,bm);markers.put(key,r);}else r.marker.setPosition(center);if(!r.hasCover&&!r.requested){ArrayList<PhotoItem> covers=new ArrayList<>();for(int id:ids)covers.add(samples.get(id));Collections.sort(covers,NEWEST);loadCover(key,r,covers,0,ids.length,generation);}}
        Iterator<Map.Entry<String,MarkerResource>> it=markers.entrySet().iterator();while(it.hasNext()){Map.Entry<String,MarkerResource> e=it.next();if(!retained.contains(e.getKey())){e.getValue().release();it.remove();}}}
    private void loadCover(String key,MarkerResource target,List<PhotoItem> covers,int index,int total,long ticket){if(index>=covers.size()){target.requested=false;return;}target.requested=true;PhotoItem s=covers.get(index);thumbnails.load(s.uri,s.mediaVersion,160,b->{if(ticket!=generation||markers.get(key)!=target)return;if(b==null){loadCover(key,target,covers,index+1,total,ticket);return;}Bitmap bm=markerBitmap(b,total);BitmapDescriptor desc=BitmapDescriptorFactory.fromBitmap(bm);target.marker.setIcon(desc);target.descriptor.recycle();target.bitmap.recycle();target.descriptor=desc;target.bitmap=bm;target.hasCover=true;target.requested=false;});}
    private Bitmap markerBitmap(Bitmap original,int count){int n=dp(48),border=dp(2);Bitmap out=Bitmap.createBitmap(n,n,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(out);Paint p=new Paint(3);p.setColor(SURFACE);c.drawRoundRect(0,0,n,n,dp(9),dp(9),p);p.setColor(PLACEHOLDER);c.drawRoundRect(border,border,n-border,n-border,dp(7),dp(7),p);if(original!=null){float scale=Math.max((float)(n-2*border)/original.getWidth(),(float)(n-2*border)/original.getHeight());Matrix m=new Matrix();m.setScale(scale,scale);m.postTranslate((n-original.getWidth()*scale)/2,(n-original.getHeight()*scale)/2);BitmapShader shader=new BitmapShader(original,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);shader.setLocalMatrix(m);p.setShader(shader);c.drawRoundRect(border,border,n-border,n-border,dp(7),dp(7),p);p.setShader(null);}if(count>1){String text=MarkerCount.format(count);p.setTextSize(dp(10));p.setTypeface(Typeface.DEFAULT_BOLD);float w=Math.max(dp(19),p.measureText(text)+dp(8));p.setColor(0xe6263a49);c.drawRoundRect(n-w-dp(1),n-dp(17),n-dp(1),n-dp(1),dp(8),dp(8),p);p.setColor(-1);p.setTextAlign(Paint.Align.CENTER);c.drawText(text,n-w/2-dp(1),n-dp(5),p);}return out;}
    private void showCluster(int[] ids){if(ids.length==1){if(ids[0]>=0&&ids[0]<samples.size())openPreview(Collections.singletonList(samples.get(ids[0])),samples.get(ids[0]));return;}group.clear();for(int i:ids)if(i>=0&&i<samples.size())group.add(samples.get(i));oldestFirst=false;sortGroup();groupMode=true;expanded=true;restorePosition=-1;renderList();grid.setSelection(0);}
    static void verifyPreviewScope(MapDataRepository.Callback<Boolean> callback){
        if(experiment==null){callback.complete(false);return;}
        experiment.verifyExistingScope(callback);
    }
    static void suspendPreviewScope(){if(experiment!=null)experiment.suspend();}
    private void openPhoto(PhotoItem s){openPreview(groupMode?group:area,s);}
    private void openPreview(List<PhotoItem> source,PhotoItem selected){
        if(source.isEmpty()||experiment==null)return;int index=source.indexOf(selected);if(index<0)return;
        int scroll=groupMode&&grid!=null?grid.getFirstVisiblePosition():0;View first=grid==null?null:grid.getChildAt(0);int offset=first==null?0:first.getTop()-grid.getPaddingTop();
        pendingPhotoReturn=source.size()>1?new PendingPhotoReturn(experiment,experiment.scopeToken(),source,oldestFirst,scroll,offset):null;
        startActivity(new Intent(this,PhotoPreviewActivity.class).putExtra("session",PreviewSessionStore.put(source)).putExtra("index",index));
    }
    private final class DraggableScrollList extends ListView {
        private boolean dragging;
        private float startY, startFraction, travel;
        DraggableScrollList(){super(MainActivity.this);}
        @Override public boolean onInterceptTouchEvent(MotionEvent event){
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN && event.getX()>=getWidth()-dp(24)){
                int range=computeVerticalScrollRange(), extent=computeVerticalScrollExtent(), offset=computeVerticalScrollOffset();
                int track=getHeight()-getPaddingTop()-getPaddingBottom();
                if(range>extent && extent>0 && track>0){
                    float thumb=Math.max(dp(24),track*(float)extent/range);
                    travel=Math.max(1,track-thumb);
                    startFraction=Math.max(0,Math.min(1,offset/(float)(range-extent)));
                    float top=getPaddingTop()+travel*startFraction;
                    if(event.getY()>=top-dp(16)&&event.getY()<=top+thumb+dp(16)){
                        dragging=true;startY=event.getY();awakenScrollBars();return true;
                    }
                }
            }
            return super.onInterceptTouchEvent(event);
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            if(!dragging)return super.onTouchEvent(event);
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_MOVE:
                    float fraction=Math.max(0,Math.min(1,startFraction+(event.getY()-startY)/travel));
                    int last=Math.max(0,getCount()-1);
                    if(fraction>=1)setSelection(last);
                    else setSelection(Math.round(fraction*Math.max(0,getCount()-getChildCount())));
                    awakenScrollBars();return true;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                    dragging=false;awakenScrollBars();return true;
                default:return true;
            }
        }
    }
    private final class AreaAdapter extends BaseAdapter {
        final ArrayList<PhotoItem> items=new ArrayList<>();
        final ArrayList<PhotoRow> rows=new ArrayList<>();
        final SimpleDateFormat dayKey=new SimpleDateFormat("yyyy-MM-dd",Locale.CHINA);
        final SimpleDateFormat dayLabel=new SimpleDateFormat("yyyy年M月d日",Locale.CHINA);
        final class PhotoRow {String heading;final ArrayList<PhotoItem> photos=new ArrayList<>();}
        void setPhotos(List<PhotoItem> photos){
            items.clear();items.addAll(photos);rows.clear();String prior=null;PhotoRow row=null;
            for(PhotoItem sample:items){String key=sample.dateTaken==null?"unknown":dayKey.format(new Date(sample.dateTaken));
                if(!key.equals(prior)){row=new PhotoRow();row.heading=sample.dateTaken==null?"日期未知":dayLabel.format(new Date(sample.dateTaken));rows.add(row);prior=key;}
                else if(row.photos.size()==4){row=new PhotoRow();rows.add(row);}row.photos.add(sample);
            }
            notifyDataSetChanged();
        }
        public int getCount(){return rows.size();}public PhotoRow getItem(int position){return rows.get(position);}public long getItemId(int position){return position;}
        public View getView(int position,View recycled,ViewGroup parent){
            LinearLayout cell=recycled instanceof LinearLayout?(LinearLayout)recycled:new LinearLayout(MainActivity.this);
            TextView heading;LinearLayout pictures;
            if(cell.getChildCount()==0){cell.setOrientation(LinearLayout.VERTICAL);heading=label("",13,TEXT);heading.setTypeface(null,android.graphics.Typeface.BOLD);cell.addView(heading,new LinearLayout.LayoutParams(-1,dp(40)));pictures=new LinearLayout(MainActivity.this);cell.addView(pictures);}
            else{heading=(TextView)cell.getChildAt(0);pictures=(LinearLayout)cell.getChildAt(1);}
            PhotoRow row=getItem(position);heading.setVisibility(row.heading==null?View.GONE:View.VISIBLE);heading.setText(row.heading);pictures.removeAllViews();
            int width=Math.max(1,(parent.getWidth()-parent.getPaddingLeft()-parent.getPaddingRight()-dp(12))/4);
            for(int i=0;i<4;i++){final int slot=i;FrameLayout frame=new FrameLayout(MainActivity.this);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,width,1);if(i>0)lp.leftMargin=dp(4);pictures.addView(frame,lp);
                if(slot>=row.photos.size())continue;PhotoItem sample=row.photos.get(slot);ImageView image=new ImageView(MainActivity.this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackground(shape(PLACEHOLDER,4));image.setClipToOutline(true);frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
                String tag=sample.uri.toString()+"|"+sample.mediaVersion+"|"+width;image.setTag(tag);thumbnails.load(sample.uri,sample.mediaVersion,width,b->{if(tag.equals(image.getTag()))image.setImageBitmap(b);});frame.setContentDescription("查看照片");frame.setOnClickListener(v->openPhoto(sample));
            }
            pictures.setPadding(0,0,0,dp(4));return cell;
        }
    }
    private final class BackArrowIcon extends android.graphics.drawable.Drawable{
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        public void draw(Canvas c){android.graphics.Rect b=getBounds();float x=b.exactCenterX(),y=b.exactCenterY(),r=Math.min(b.width(),b.height())*.27f;p.setColor(TEXT);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);Path path=new Path();path.moveTo(x+r*.5f,y-r);path.lineTo(x-r*.5f,y);path.lineTo(x+r*.5f,y+r);c.drawPath(path,p);}
        public void setAlpha(int a){}public void setColorFilter(android.graphics.ColorFilter f){}public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }
    private void clearMarkers(){overviewMembers.clear();handler.removeCallbacks(refresh);for(MarkerResource r:markers.values())r.release();markers.clear();positions.clear();}
    private void clearData(){displayedScope=-1;viewportDirty=true;cachedViewport=null;overview=null;viewportGeneration++;handler.removeCallbacks(viewportRefresh);viewportPhase="尚未筛选";viewportMatches=viewportLoaded=0;generation++;if(pending!=null){pending.cancel();pending=null;}clearMarkers();thumbnails.clear();samples.clear();area.clear();group.clear();groupMode=false;loading=false;loadError=null;unmapped=withoutGps=unavailable=excluded=0;restorePosition=-1;restoreOffset=0;if(count!=null)count.setText(galleryStatus("未启用"));renderList();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);if(map!=null)transientMapStatus=map.getMapStatus();out.putBoolean("privacyAccepted",privacyAccepted);out.putBoolean("expanded",expanded);out.putBoolean("oldestFirst",oldestFirst);}
    private void updateNetworkStatus(){
        if(networkStatus==null)return;
        ConnectivityManager manager=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities capabilities=manager==null?null:manager.getNetworkCapabilities(manager.getActiveNetwork());
        boolean online=capabilities!=null&&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        networkStatus.setVisibility(online?View.GONE:View.VISIBLE);
    }
    @Override protected void onStart(){super.onStart();ConnectivityManager manager=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);if(manager!=null){manager.registerDefaultNetworkCallback(networkCallback);networkCallbackRegistered=true;}updateNetworkStatus();}
    @Override protected void onStop(){stopLocation();if(networkCallbackRegistered){ConnectivityManager manager=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);if(manager!=null)manager.unregisterNetworkCallback(networkCallback);networkCallbackRegistered=false;}super.onStop();}
    private void showScopeCover(){if(root==null||scopeCover!=null)return;TextView cover=label("正在检查照片授权",14,SECONDARY);cover.setGravity(Gravity.CENTER);cover.setBackgroundColor(MAP_BACKGROUND);cover.setClickable(true);scopeCover=cover;root.addView(cover,new FrameLayout.LayoutParams(-1,-1));}
    private void hideScopeCover(){if(scopeCover!=null){root.removeView(scopeCover);scopeCover=null;}}
    private void resumeMapView(){if(active&&view!=null&&!mapResumed){view.onResume();mapResumed=true;}}
    @Override protected void onResume(){super.onResume();active=true;if(view!=null){resumeMapView();resumeExperiment();}}
    @Override protected void onPause(){if(experiment!=null)showScopeCover();if(handler.hasCallbacks(viewportRefresh)||handler.hasCallbacks(refresh))viewportDirty=true;active=false;cancelRequests();handler.removeCallbacks(checkLoad);stopLocation();if(experiment!=null)experiment.suspend();if(view!=null&&mapResumed){view.onPause();mapResumed=false;}super.onPause();}
    @Override protected void onDestroy(){clearData();thumbnails.close();worker.shutdownNow();if(view!=null)view.onDestroy();if(!isChangingConfigurations()&&isFinishing()&&experiment!=null){experiment.close();experiment=null;pendingPhotoReturn=null;}super.onDestroy();}
    private final class FitIcon extends android.graphics.drawable.Drawable{private final Paint p=new Paint(3);public void draw(Canvas c){android.graphics.Rect b=getBounds();float x=b.exactCenterX(),y=b.exactCenterY(),r=Math.min(b.width(),b.height())*.32f;p.setColor(ACCENT);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setStrokeCap(Paint.Cap.ROUND);c.drawCircle(x,y,r,p);c.drawLine(x,b.top,x,y-r,p);c.drawLine(x,y+r,x,b.bottom,p);c.drawLine(b.left,y,x-r,y,p);c.drawLine(x+r,y,b.right,y,p);p.setStyle(Paint.Style.FILL);c.drawCircle(x,y,dp(2),p);}public void setAlpha(int a){}public void setColorFilter(android.graphics.ColorFilter f){}public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}}
}
