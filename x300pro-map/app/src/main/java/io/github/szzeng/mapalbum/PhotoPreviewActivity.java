package io.github.szzeng.mapalbum;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** Displays only a stable snapshot of the selected photos and their system thumbnails. */
public final class PhotoPreviewActivity extends Activity {
    private int BACKGROUND, CARD, CONTROL, PRIMARY, SECONDARY, ACCENT, SHADE;
    private boolean darkMode;
    private ArrayList<PhotoItem> photos;
    private int index, request, topInset, bottomInset, direction;
    private boolean controlsVisible=true, activePreview;
    private ThumbnailLoader loader;
    private ZoomablePreview image;
    private FrameLayout page, photoArea;
    private LinearLayout card, topControls;
    private View topShade, scopeCover;
    private boolean photoShown;
    private TextView number, navNumber, date, path, error, previous, next;
    private SeekBar progress;

    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);return t;}

    @Override protected void attachBaseContext(Context base){super.attachBaseContext(base);Appearance.applyOverride(this,base);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        darkMode=(getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
        BACKGROUND=getColor(R.color.preview_background);CARD=getColor(R.color.preview_card);CONTROL=getColor(R.color.preview_control);PRIMARY=getColor(R.color.preview_primary);SECONDARY=getColor(R.color.preview_secondary);ACCENT=getColor(R.color.app_accent);SHADE=getColor(R.color.preview_shade);
        photos=PreviewSessionStore.get(getIntent().getStringExtra("session"));
        if(photos==null||photos.isEmpty()){finish();return;}
        index=Math.max(0,Math.min(photos.size()-1,state==null?getIntent().getIntExtra("index",0):state.getInt("index")));
        controlsVisible=state==null||state.getBoolean("controlsVisible",true);
        loader=new ThumbnailLoader(getContentResolver());
        getWindow().setBackgroundDrawableResource(R.color.preview_background);getWindow().setStatusBarColor(Color.TRANSPARENT);getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(systemUiFlags(false));

        page=new FrameLayout(this);page.setBackgroundColor(BACKGROUND);setContentView(page);
        photoArea=new FrameLayout(this);photoArea.setBackgroundColor(BACKGROUND);page.addView(photoArea,new FrameLayout.LayoutParams(-1,-1));
        image=new ZoomablePreview(this);photoArea.addView(image,new FrameLayout.LayoutParams(-1,-1));
        error=text("",14,SECONDARY);error.setGravity(Gravity.CENTER);error.setPadding(dp(24),0,dp(24),0);error.setVisibility(View.GONE);photoArea.addView(error,new FrameLayout.LayoutParams(-1,-1));
        image.setSwipeListener(forward->change(forward?1:-1));image.setTapListener(this::openOriginal);image.setLongPressListener(this::toggleControls);

        topShade=new View(this);topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{SHADE,0x00000000}));page.addView(topShade,new FrameLayout.LayoutParams(-1,dp(110),Gravity.TOP));
        topControls=new LinearLayout(this);topControls.setGravity(Gravity.CENTER_VERTICAL);topControls.setPadding(dp(12),0,dp(12),0);
        ImageView close=new ImageView(this);close.setImageDrawable(new BackIcon());close.setPadding(dp(10),dp(10),dp(10),dp(10));close.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);close.setBackground(shape(CARD,24));
        FrameLayout closeTarget=new FrameLayout(this);closeTarget.addView(close,new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.CENTER));closeTarget.setOnClickListener(v->finish());closeTarget.setContentDescription("返回");topControls.addView(closeTarget,new LinearLayout.LayoutParams(dp(48),dp(48)));
        View spacer=new View(this);topControls.addView(spacer,new LinearLayout.LayoutParams(0,1,1));
        number=text("",13,PRIMARY);number.setGravity(Gravity.CENTER);number.setPadding(dp(16),0,dp(16),0);number.setBackground(shape(CARD,20));topControls.addView(number,new LinearLayout.LayoutParams(-2,dp(40)));
        page.addView(topControls,new FrameLayout.LayoutParams(-1,dp(48),Gravity.TOP));

        card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(16),dp(14),dp(16),dp(14));card.setBackground(shape(CARD,22));
        date=text("",16,PRIMARY);date.setTypeface(Typeface.DEFAULT,Typeface.BOLD);date.setMaxLines(2);date.setEllipsize(TextUtils.TruncateAt.END);card.addView(date,new LinearLayout.LayoutParams(-1,-2));
        path=text("",12,SECONDARY);path.setSingleLine(true);path.setEllipsize(TextUtils.TruncateAt.MIDDLE);LinearLayout.LayoutParams pathParams=new LinearLayout.LayoutParams(-1,-2);pathParams.topMargin=dp(4);card.addView(path,pathParams);
        LinearLayout nav=new LinearLayout(this);nav.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams navParams=new LinearLayout.LayoutParams(-1,dp(48));navParams.topMargin=dp(8);card.addView(nav,navParams);
        previous=arrow("‹","上一张");nav.addView(previous,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout progressArea=new LinearLayout(this);progressArea.setOrientation(LinearLayout.VERTICAL);progressArea.setGravity(Gravity.CENTER);LinearLayout.LayoutParams center=new LinearLayout.LayoutParams(0,-1,1);center.leftMargin=dp(16);center.rightMargin=dp(16);nav.addView(progressArea,center);
        navNumber=text("",12,SECONDARY);navNumber.setGravity(Gravity.CENTER);progressArea.addView(navNumber,new LinearLayout.LayoutParams(-1,dp(24)));
        progress=new SeekBar(this);progress.setMax(photos.size()-1);progress.setEnabled(photos.size()>1);progress.setContentDescription("拖动选择照片");
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));progress.setProgressBackgroundTintList(ColorStateList.valueOf(CONTROL));progress.setThumbTintList(ColorStateList.valueOf(ACCENT));
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onProgressChanged(SeekBar bar,int value,boolean fromUser){if(fromUser)selectIndex(value,false);}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });
        progressArea.addView(progress,new LinearLayout.LayoutParams(-1,dp(24)));
        next=arrow("›","下一张");nav.addView(next,new LinearLayout.LayoutParams(dp(48),dp(48)));previous.setOnClickListener(v->change(-1));next.setOnClickListener(v->change(1));
        TextView note=text("轻点照片查看原图 · 长按隐藏或显示界面\n预览为限尺寸图，高清原图由 vivo 相册显示",11,SECONDARY);note.setMaxLines(3);LinearLayout.LayoutParams noteParams=new LinearLayout.LayoutParams(-1,-2);noteParams.topMargin=dp(12);card.addView(note,noteParams);
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);cp.leftMargin=dp(12);cp.rightMargin=dp(12);page.addView(card,cp);
        card.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(b-t!=ob-ot)updatePhotoBounds();});
        page.setOnApplyWindowInsetsListener((v,insets)->{topInset=insets.getSystemWindowInsetTop();bottomInset=Build.VERSION.SDK_INT>=30?Math.max(insets.getInsets(WindowInsets.Type.navigationBars()).bottom,insets.getInsets(WindowInsets.Type.systemGestures()).bottom):insets.getSystemWindowInsetBottom();updateInsets();return insets;});
        updateControls();showScopeCover();
    }

    private TextView arrow(String symbol,String description){TextView button=text(symbol,30,PRIMARY);button.setGravity(Gravity.CENTER);button.setContentDescription(description);button.setBackground(shape(CONTROL,24));return button;}
    private final class BackIcon extends android.graphics.drawable.Drawable {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(Canvas canvas){android.graphics.Rect r=getBounds();float cx=r.exactCenterX(),cy=r.exactCenterY(),arm=Math.min(r.width(),r.height())*.26f;paint.setColor(PRIMARY);paint.setStrokeWidth(dp(2));paint.setStyle(Paint.Style.STROKE);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeCap(Paint.Cap.ROUND);Path arrow=new Path();arrow.moveTo(cx+arm*.45f,cy-arm);arrow.lineTo(cx-arm*.55f,cy);arrow.lineTo(cx+arm*.45f,cy+arm);canvas.drawPath(arrow,paint);}
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}@Override public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
    private void updateInsets(){FrameLayout.LayoutParams t=(FrameLayout.LayoutParams)topControls.getLayoutParams();t.topMargin=topInset+dp(4);topControls.setLayoutParams(t);FrameLayout.LayoutParams s=(FrameLayout.LayoutParams)topShade.getLayoutParams();s.height=topInset+dp(96);topShade.setLayoutParams(s);FrameLayout.LayoutParams c=(FrameLayout.LayoutParams)card.getLayoutParams();c.bottomMargin=bottomInset+dp(12);card.setLayoutParams(c);updatePhotoBounds();}
    private void updatePhotoBounds(){if(photoArea==null||card==null)return;FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)photoArea.getLayoutParams();int bottom=controlsVisible?card.getHeight()+bottomInset+dp(12):0;if(p.bottomMargin!=bottom){p.bottomMargin=bottom;photoArea.setLayoutParams(p);}}
    private void toggleControls(){controlsVisible=!controlsVisible;updateControls();}
    private int systemUiFlags(boolean hidden){int flags=View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;if(!darkMode)flags|=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;if(hidden)flags|=View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;return flags;}
    private void updateControls(){int visibility=controlsVisible?View.VISIBLE:View.GONE;topControls.setVisibility(visibility);topShade.setVisibility(visibility);card.setVisibility(visibility);getWindow().getDecorView().setSystemUiVisibility(systemUiFlags(!controlsVisible));updatePhotoBounds();}
    private void change(int offset){selectIndex(index+offset,true);}
    private void selectIndex(int target,boolean animate){if(target<0||target>=photos.size()||target==index)return;direction=animate?Integer.compare(target,index):0;index=target;showPhoto();}
    private Bundle itemAt(int position){PhotoItem photo=photos.get(position);Bundle item=new Bundle();item.putParcelable("uri",photo.uri);item.putParcelable("document",photo.documentUri);item.putLong("version",photo.mediaVersion);item.putLong("date",photo.dateTaken==null?0:photo.dateTaken);item.putBoolean("fallback",photo.dateFallback);item.putString("path",photo.relativePath);return item;}
    private void showPhoto(){
        photoShown=true;
        loader.cancelPending();int ticket=++request;Bundle item=itemAt(index);String position=(index+1)+" / "+photos.size();number.setText(position);navNumber.setText(position);progress.setProgress(index);
        previous.setEnabled(index>0);next.setEnabled(index+1<photos.size());previous.setAlpha(previous.isEnabled()?1f:.35f);next.setAlpha(next.isEnabled()?1f:.35f);
        long time=item.getLong("date",0);date.setText(time<=0?"拍摄时间未知":new SimpleDateFormat("yyyy年M月d日 HH:mm",Locale.CHINA).format(new Date(time))+(item.getBoolean("fallback")?"（系统时间）":""));
        String relativePath=item.getString("path");if(relativePath!=null){int end=relativePath.lastIndexOf('/');relativePath=end>=0?relativePath.substring(0,end):null;}path.setVisibility(relativePath==null||relativePath.isEmpty()?View.GONE:View.VISIBLE);path.setText(relativePath);
        image.animate().cancel();image.setAlpha(1f);image.setTranslationX(0);image.setImageDrawable(null);image.resetPreview();error.setVisibility(View.GONE);
        Uri uri=item.getParcelable("uri");if(uri==null){error.setText("照片不可用");error.setVisibility(View.VISIBLE);return;}
        int size=Math.min(1440,Math.max(getResources().getDisplayMetrics().widthPixels,getResources().getDisplayMetrics().heightPixels));
        loader.load(uri,item.getLong("version"),size,b->{if(ticket!=request||isFinishing()||isDestroyed())return;
            if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0&&b!=null)android.util.Log.i("MAP004Preview","media thumbnail="+b.getWidth()+"x"+b.getHeight());
            if(b!=null)displayThumbnail(b,true);Uri document=item.getParcelable("document");
            if(document!=null&&!document.equals(uri)&&(b==null||Math.max(b.getWidth(),b.getHeight())<size*.75f)){
                final long firstPixels=b==null?0:(long)b.getWidth()*b.getHeight();
                loader.load(document,item.getLong("version"),size,alternative->{if(ticket!=request||isFinishing()||isDestroyed())return;
                    if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0&&alternative!=null)android.util.Log.i("MAP004Preview","document thumbnail="+alternative.getWidth()+"x"+alternative.getHeight());
                    Bitmap best=b;if(alternative!=null&&(long)alternative.getWidth()*alternative.getHeight()>firstPixels){best=alternative;displayThumbnail(alternative,b==null);}
                    loadBetterPreview(item,ticket,size,best);
                });
            }else if(b==null||Math.max(b.getWidth(),b.getHeight())<size*.75f)loadBetterPreview(item,ticket,size,b);
        });
    }
    private void loadBetterPreview(Bundle item,int ticket,int size,Bitmap current){
        Uri document=item.getParcelable("document");if(document==null){if(current==null){error.setText("预览加载失败，仍可尝试打开原图");error.setVisibility(View.VISIBLE);}return;}
        final long pixels=current==null?0:(long)current.getWidth()*current.getHeight();
        loader.loadSampled(document,item.getLong("version"),size,b->{if(ticket!=request||isFinishing()||isDestroyed())return;
            if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0&&b!=null)android.util.Log.i("MAP004Preview","sampled preview="+b.getWidth()+"x"+b.getHeight());
            if(b!=null&&(long)b.getWidth()*b.getHeight()>pixels)displayThumbnail(b,current==null);else if(current==null){error.setText("预览加载失败，仍可尝试打开原图");error.setVisibility(View.VISIBLE);}
        });
    }
    private void displayThumbnail(Bitmap bitmap,boolean animate){error.setVisibility(View.GONE);if(image.getDrawable()!=null)image.replacePreview(bitmap);else{image.setImageBitmap(bitmap);image.resetPreview();}if(animate&&direction!=0){image.setAlpha(0f);image.setTranslationX(dp(20)*direction);image.animate().alpha(1f).translationX(0).setDuration(180).start();}}
    private void openOriginal(){Uri uri=photos.get(index).documentUri;if(uri==null){Toast.makeText(this,"照片不可用",Toast.LENGTH_SHORT).show();return;}Intent standard=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);try{for(String packageName:new String[]{"com.vivo.gallery","com.android.gallery3d"}){Intent gallery=new Intent(standard).setPackage(packageName);if(gallery.resolveActivity(getPackageManager())!=null){try{startActivity(gallery);return;}catch(android.content.ActivityNotFoundException|SecurityException ignored){}}}startActivity(standard);}catch(Exception e){Toast.makeText(this,"无法打开原生相册",Toast.LENGTH_SHORT).show();}}
    private void showScopeCover(){if(page==null||scopeCover!=null)return;TextView cover=text("正在检查照片授权",14,SECONDARY);cover.setGravity(Gravity.CENTER);cover.setBackgroundColor(BACKGROUND);cover.setClickable(true);scopeCover=cover;page.addView(cover,new FrameLayout.LayoutParams(-1,-1));}
    private void hideScopeCover(){if(scopeCover!=null){page.removeView(scopeCover);scopeCover=null;}}
    @Override protected void onResume(){
        super.onResume();activePreview=true;if(page==null)return;showScopeCover();
        MainActivity.verifyPreviewScope(ok->runOnUiThread(()->{if(!activePreview||isFinishing())return;if(!ok){Toast.makeText(this,"照片授权范围已变化，请返回地图重新加载",Toast.LENGTH_LONG).show();finish();return;}if(!photoShown||image.getDrawable()==null)showPhoto();hideScopeCover();}));
    }
    @Override protected void onPause(){showScopeCover();activePreview=false;request++;if(loader!=null)loader.cancelPending();MainActivity.suspendPreviewScope();super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putInt("index",index);out.putBoolean("controlsVisible",controlsVisible);}
    @Override protected void onDestroy(){request++;if(image!=null)image.animate().cancel();if(loader!=null)loader.close();if(isFinishing())PreviewSessionStore.clear(getIntent().getStringExtra("session"));super.onDestroy();}
}
