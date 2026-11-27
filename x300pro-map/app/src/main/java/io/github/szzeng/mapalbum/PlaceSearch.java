package io.github.szzeng.mapalbum;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.*;
import com.baidu.mapapi.model.LatLng;
import com.baidu.mapapi.search.core.*;
import com.baidu.mapapi.search.geocode.*;
import com.baidu.mapapi.search.poi.*;
import com.baidu.mapapi.search.sug.*;
import java.util.*;

/** A map-local search surface. Each request owns its listener and generation. */
final class PlaceSearch {
    interface Host { LatLng center(); void select(LatLng point, float zoom); }
    private final Activity activity; private final FrameLayout root; private final Host host;
    private final int text, secondary, surface, accent; private final Handler handler=new Handler(Looper.getMainLooper());
    private LinearLayout overlay, results; private EditText input; private TextView status;
    private GeoCoder geo; private SuggestionSearch sug; private PoiSearch poi;
    private long generation; private Runnable debounce; private String city, lastSug, lastPoi, cityError; private LatLng cityCenter;
    PlaceSearch(Activity activity,FrameLayout root,Host host,int text,int secondary,int surface,int accent){this.activity=activity;this.root=root;this.host=host;this.text=text;this.secondary=secondary;this.surface=surface;this.accent=accent;}
    private int dp(float n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView label(String s,int size,int color){TextView t=new TextView(activity);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
    boolean isOpen(){return overlay!=null;}
    void open(){
        if(isOpen())return;
        overlay=new LinearLayout(activity);overlay.setOrientation(LinearLayout.VERTICAL);overlay.setBackgroundColor(surface);overlay.setClickable(true);overlay.setElevation(dp(20));root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout bar=new LinearLayout(activity);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(12),dp(8),dp(12),dp(8));overlay.addView(bar,new LinearLayout.LayoutParams(-1,dp(64)));
        TextView back=label("‹",32,text);back.setGravity(Gravity.CENTER);back.setContentDescription("返回地图");bar.addView(back,new LinearLayout.LayoutParams(dp(44),-1));back.setOnClickListener(v->close());
        input=new EditText(activity);input.setSingleLine(true);input.setTextSize(16);input.setTextColor(text);input.setHintTextColor(secondary);input.setHint("搜索地点，如：杭州西湖");input.setBackground(shape(0x18088888,12));input.setPadding(dp(12),0,dp(12),0);input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);bar.addView(input,new LinearLayout.LayoutParams(0,dp(44),1));
        TextView clear=label("×",26,secondary);clear.setGravity(Gravity.CENTER);clear.setContentDescription("清空搜索");bar.addView(clear,new LinearLayout.LayoutParams(dp(44),-1));clear.setOnClickListener(v->input.setText(""));
        status=label("输入至少两个字开始搜索",14,secondary);status.setPadding(dp(20),dp(10),dp(20),dp(10));overlay.addView(status);
        ScrollView scroll=new ScrollView(activity);results=new LinearLayout(activity);results.setOrientation(LinearLayout.VERTICAL);scroll.addView(results);overlay.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){} public void onTextChanged(CharSequence s,int st,int before,int count){changed();}public void afterTextChanged(Editable e){}});
        input.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH||(event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_UP)){submit(input.getText().toString().trim());return true;}return false;});
        resolveCity();input.requestFocus();input.postDelayed(()->{if(isOpen())((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);},150);
    }
    private void resolveCity(){
        LatLng center=host.center();if(center==null){message("无法获取地图中心，请返回地图后重试");return;}
        if(city!=null&&cityCenter!=null&&center.latitude==cityCenter.latitude&&center.longitude==cityCenter.longitude){changed();return;}
        city=null;cityCenter=null;cityError=null;message("正在获取地图所在城市…");geo=GeoCoder.newInstance();GeoCoder request=geo;
        request.setOnGetGeoCodeResultListener(new OnGetGeoCoderResultListener(){public void onGetGeoCodeResult(GeoCodeResult r){} public void onGetReverseGeoCodeResult(ReverseGeoCodeResult r){
            if(!isOpen()||geo!=request)return;geo=null;request.destroy();
            if(r==null||r.error!=SearchResult.ERRORNO.NO_ERROR||r.getAddressDetail()==null||empty(r.getAddressDetail().city)){cityError=r==null?"无法获取城市，请检查网络后重新打开搜索":error(r.error,"无法获取地图所在城市，请重新打开搜索");message(cityError);return;}
            city=r.getAddressDetail().city;cityCenter=center;changed();
        }});
        if(!request.reverseGeoCode(new ReverseGeoCodeOption().location(center))){geo=null;request.destroy();cityError="无法获取地图所在城市，请重新打开搜索";message(cityError);}
    }
    private static boolean empty(String s){return s==null||s.trim().isEmpty();}
    private static boolean valid(LatLng p){return p!=null&&PlaceSearchRules.validCoordinate(p.latitude,p.longitude);}
    private void changed(){
        invalidate();lastSug=null;lastPoi=null;results.removeAllViews();String q=input.getText().toString().trim();
        if(q.codePointCount(0,q.length())<2){message("输入至少两个字开始搜索");return;} if(city==null){message(cityError==null?"正在获取地图所在城市…":cityError);return;}
        long ticket=generation;message("正在搜索…");debounce=()->suggest(q,ticket);handler.postDelayed(debounce,300);
    }
    private void invalidate(){generation++;if(debounce!=null){handler.removeCallbacks(debounce);debounce=null;}if(sug!=null){sug.destroy();sug=null;}if(poi!=null){poi.destroy();poi=null;}}
    private boolean current(long t){return isOpen()&&generation==t;}
    private void suggest(String q,long ticket){
        if(!current(ticket)||q.equals(lastSug))return;lastSug=q;sug=SuggestionSearch.newInstance();SuggestionSearch request=sug;
        request.setOnGetSuggestionResultListener(r->{if(!current(ticket)||sug!=request)return;sug=null;request.destroy();results.removeAllViews();
            if(r==null||r.error!=SearchResult.ERRORNO.NO_ERROR){lastSug=null;message(r==null?"搜索暂不可用，请重试":error(r.error,"没有找到相关地点"));return;}
            List<SuggestionResult.SuggestionInfo> list=r.getAllSuggestions();if(list!=null)for(SuggestionResult.SuggestionInfo item:list){if(item==null||empty(item.key))continue;row(item.key,join(item.city,item.district,item.address),()->{switch(PlaceSearchRules.route(valid(item.pt),item.uid)){case DIRECT: choose(item.pt,zoom(item.key,item.city,item.district,item.tag));break;case DETAIL: detail(item.uid,item.key);break;default: submit(item.key);}});}
            message(results.getChildCount()==0?"没有找到相关地点，可尝试提交搜索":"选择地点，或按搜索键查看更多结果");
        });
        if(!request.requestSuggestion(new SuggestionSearchOption().city(city).keyword(q))){sug=null;request.destroy();lastSug=null;message("搜索暂不可用，请重试");}
    }
    private void submit(String q){submitInCity(q,city);}
    private void submitInCity(String q,String searchCity){if(empty(q))return;if(empty(searchCity)){message("无法获取地图所在城市，请重新打开搜索");return;} String requestKey=searchCity+"\n"+q;if(requestKey.equals(lastPoi))return;invalidate();lastPoi=requestKey;long ticket=generation;message("正在搜索地点…");results.removeAllViews();poi=PoiSearch.newInstance();PoiSearch request=poi;
        request.setOnGetPoiSearchResultListener(new PoiListener(){@Override public void onGetPoiResult(PoiResult r){if(!current(ticket)||poi!=request)return;poi=null;request.destroy();
            if(r!=null&&r.getSuggestCityList()!=null&&!r.getSuggestCityList().isEmpty()){
                for(CityInfo suggested:r.getSuggestCityList()){if(suggested==null||empty(suggested.city))continue;row(suggested.city,"查看该城市的搜索结果",()->submitInCity(q,suggested.city));}
                if(results.getChildCount()>0){message("请选择搜索结果所在城市");return;}
            }
            if(r==null||r.error!=SearchResult.ERRORNO.NO_ERROR){lastPoi=null;message(r==null?"搜索暂不可用，请重试":error(r.error,"没有找到相关地点"));return;}
            List<PoiInfo> list=r.getAllPoi();if(list!=null)for(PoiInfo item:list){if(item==null||empty(item.name)||!valid(item.location))continue;row(item.name,join(item.city,item.area,item.address),()->choose(item.location,zoom(item.name,item.city,item.area,item.tag)));}
            message(results.getChildCount()==0?"没有找到可定位的地点，请换个关键词":"选择一个地点");
        }});
        if(!request.searchInCity(new PoiCitySearchOption().city(searchCity).keyword(q).cityLimit(false).pageNum(0).pageCapacity(10))){poi=null;request.destroy();lastPoi=null;message("搜索暂不可用，请重试");}
    }
    private void detail(String uid,String name){invalidate();long ticket=generation;message("正在获取地点…");poi=PoiSearch.newInstance();PoiSearch request=poi;
        request.setOnGetPoiSearchResultListener(new PoiListener(){@Override public void onGetPoiDetailResult(PoiDetailResult r){if(!current(ticket)||poi!=request)return;poi=null;request.destroy();if(r!=null&&r.error==SearchResult.ERRORNO.NO_ERROR&&valid(r.location))choose(r.location,zoom(r.name,null,null,r.tag));else if(r!=null&&(r.error==SearchResult.ERRORNO.NETWORK_ERROR||r.error==SearchResult.ERRORNO.NETWORK_TIME_OUT||r.error==SearchResult.ERRORNO.KEY_ERROR||r.error==SearchResult.ERRORNO.PERMISSION_UNFINISHED))message(error(r.error,"地点详情不可用"));else submit(name);}});
        if(!request.searchPoiDetail(new PoiDetailSearchOption().poiUid(uid))){poi=null;request.destroy();submit(name);}
    }
    private static abstract class PoiListener implements OnGetPoiSearchResultListener {public void onGetPoiResult(PoiResult r){}public void onGetPoiDetailResult(PoiDetailResult r){}public void onGetPoiDetailResult(PoiDetailSearchResult r){}public void onGetPoiIndoorResult(PoiIndoorResult r){}}
    private void choose(LatLng point,float zoom){close();host.select(point,zoom);}
    private static float zoom(String name,String city,String district,String tag){if(!empty(name)&&(name.equals(city)||name.equals(district)||(!empty(tag)&&(tag.contains("行政区划")||tag.contains("城市")))))return 11f;return 16f;}
    private static String join(String... parts){LinkedHashSet<String> values=new LinkedHashSet<>();for(String p:parts)if(!empty(p))values.add(p.trim());return android.text.TextUtils.join(" · ",values);}
    private void row(String title,String subtitle,Runnable click){LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(20),dp(12),dp(20),dp(12));TextView t=label(title,16,text);box.addView(t);if(!empty(subtitle)){TextView sub=label(subtitle,13,secondary);sub.setPadding(0,dp(5),0,0);box.addView(sub);}box.setOnClickListener(v->click.run());results.addView(box);View line=new View(activity);line.setBackgroundColor(0x22888888);results.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));}
    private void message(String s){if(status!=null)status.setText(s);}
    private static String error(SearchResult.ERRORNO e,String fallback){if(e==SearchResult.ERRORNO.KEY_ERROR||e==SearchResult.ERRORNO.PERMISSION_UNFINISHED||e==SearchResult.ERRORNO.NO_ADVANCED_PERMISSION)return "百度地图鉴权失败，请检查 AK 和检索权限";if(e==SearchResult.ERRORNO.NETWORK_ERROR||e==SearchResult.ERRORNO.NETWORK_TIME_OUT)return "网络连接失败，请检查网络后重试";return fallback;}
    void close(){if(!isOpen())return;invalidate();if(geo!=null){geo.destroy();geo=null;}((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);root.removeView(overlay);overlay=null;input=null;status=null;results=null;lastSug=null;lastPoi=null;}
}
