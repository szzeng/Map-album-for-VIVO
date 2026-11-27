package io.github.szzeng.mapalbum;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.provider.MediaStore;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Collections;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Displays only a stable snapshot of the selected photos and their system thumbnails. */
public final class PhotoPreviewActivity extends Activity {
    private int BACKGROUND, CARD, CONTROL, PRIMARY, SECONDARY, ACCENT, SHADE;
    private boolean darkMode;
    private List<PhotoItem> photos;
    private PreviewSessionStore.Session session;
    private int index, request, topInset, bottomInset, direction;
    private long scopeVerification;
    private boolean activePreview, openingOriginal;
    private ThumbnailLoader loader;
    private ZoomablePreview image;
    private FrameLayout page, photoArea;
    private LinearLayout card, topControls;
    private View topShade, scopeCover;
    private final Runnable showScopeMessage=()->{
        if(activePreview&&scopeCover instanceof TextView)((TextView)scopeCover).setText("正在检查照片授权");
    };
    private boolean photoShown, scrubbing, scrubLoading;
    private int scrubDisplayedIndex=-1;
    private int displayedIndex=-1;
    private TextView number,error,photoDate,photoSize;
    private final ThreadPoolExecutor metadataWorker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1),new ThreadPoolExecutor.DiscardOldestPolicy());
    private int metadataIndex=-1,metadataGeneration;
    private ImageView previous,next;
    private SeekBar progress;

    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);return t;}

    @Override protected void attachBaseContext(Context base){super.attachBaseContext(base);Appearance.applyOverride(this,base);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        openingOriginal=state!=null&&state.getBoolean("openingOriginal",false);
        darkMode=(getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
        BACKGROUND=getColor(R.color.preview_background);CARD=getColor(R.color.preview_card);CONTROL=getColor(R.color.preview_control);PRIMARY=getColor(R.color.preview_primary);SECONDARY=getColor(R.color.preview_secondary);ACCENT=getColor(R.color.app_accent);SHADE=getColor(R.color.preview_shade);
        session=PreviewSessionStore.get(getIntent().getStringExtra("session"));
        if(session==null){finish();return;}
        photos=session.singleId>=0?Collections.singletonList(new PhotoItem(null,null,0)):session.photos;
        if(photos.isEmpty()){finish();return;}
        index=Math.max(0,Math.min(photos.size()-1,state==null?getIntent().getIntExtra("index",0):state.getInt("index")));
        loader=new ThumbnailLoader(getContentResolver());
        getWindow().setBackgroundDrawableResource(R.color.preview_background);getWindow().setStatusBarColor(Color.TRANSPARENT);getWindow().setNavigationBarColor(Color.TRANSPARENT);
        int systemFlags=View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if(!darkMode)systemFlags|=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(systemFlags);

        page=new FrameLayout(this);page.setBackgroundColor(BACKGROUND);setContentView(page);
        photoArea=new FrameLayout(this);photoArea.setBackgroundColor(BACKGROUND);page.addView(photoArea,new FrameLayout.LayoutParams(-1,-1));
        image=new ZoomablePreview(this);photoArea.addView(image,new FrameLayout.LayoutParams(-1,-1));
        error=text("",14,SECONDARY);error.setGravity(Gravity.CENTER);error.setPadding(dp(24),0,dp(24),0);error.setVisibility(View.GONE);photoArea.addView(error,new FrameLayout.LayoutParams(-1,-1));
        error.setOnClickListener(v->openOriginal());
        image.setSwipeListener(forward->change(forward?1:-1));
        image.setOpenOriginalListener(this::openOriginal);

        topShade=new View(this);topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{SHADE,0x00000000}));page.addView(topShade,new FrameLayout.LayoutParams(-1,dp(110),Gravity.TOP));
        topControls=new LinearLayout(this);topControls.setGravity(Gravity.CENTER_VERTICAL);topControls.setPadding(dp(12),0,dp(12),0);
        ImageView close=new ImageView(this);close.setImageDrawable(new BackIcon());close.setPadding(dp(10),dp(10),dp(10),dp(10));close.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);close.setBackground(shape(CARD,24));
        FrameLayout closeTarget=new FrameLayout(this);closeTarget.addView(close,new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.CENTER));closeTarget.setOnClickListener(v->finish());closeTarget.setContentDescription("返回");topControls.addView(closeTarget,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout details=new LinearLayout(this);details.setOrientation(LinearLayout.VERTICAL);details.setGravity(Gravity.CENTER_VERTICAL);details.setPadding(dp(14),dp(4),dp(14),dp(4));details.setBackground(shape(CARD,16));details.setLayerType(View.LAYER_TYPE_SOFTWARE,null);
        photoDate=text("",14,PRIMARY);photoDate.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL),android.graphics.Typeface.NORMAL);photoDate.setSingleLine(true);photoDate.setEllipsize(android.text.TextUtils.TruncateAt.END);photoDate.setIncludeFontPadding(true);
        photoSize=text("",12,SECONDARY);photoSize.setSingleLine(true);photoSize.setEllipsize(android.text.TextUtils.TruncateAt.END);photoSize.setIncludeFontPadding(true);
        details.addView(photoDate,new LinearLayout.LayoutParams(-1,-2));LinearLayout.LayoutParams sizeParams=new LinearLayout.LayoutParams(-1,-2);sizeParams.topMargin=dp(3);details.addView(photoSize,sizeParams);
        details.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams detailsParams=new LinearLayout.LayoutParams(0,-2,1);detailsParams.leftMargin=dp(8);topControls.addView(details,detailsParams);
        topControls.setMinimumHeight(dp(48));page.addView(topControls,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));

        boolean multiple=photos.size()>1;
        card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(multiple?8:16),dp(multiple?6:8),dp(multiple?8:16),dp(multiple?6:8));
        number=text("",13,PRIMARY);
        number.setGravity(Gravity.CENTER);
        number.setIncludeFontPadding(false);
        card.addView(number,new LinearLayout.LayoutParams(-2,-2));

        LinearLayout navigation=new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        navigation.setVisibility(multiple?View.VISIBLE:View.GONE);
        card.addView(navigation,new LinearLayout.LayoutParams(-1,-2));
        previous=arrow(false,"上一张");
        navigation.addView(previous,new LinearLayout.LayoutParams(dp(48),dp(48)));
        progress=new SeekBar(this);progress.setMax(photos.size()-1);progress.setEnabled(multiple);progress.setContentDescription("拖动选择照片");
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));progress.setProgressBackgroundTintList(ColorStateList.valueOf(CONTROL));progress.setThumbTintList(ColorStateList.valueOf(ACCENT));
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onStartTrackingTouch(SeekBar bar){
                scrubbing=true;scrubLoading=false;scrubDisplayedIndex=displayedIndex;request++;loader.cancelPending();
            }
            @Override public void onProgressChanged(SeekBar bar,int value,boolean fromUser){
                if(!fromUser)return;
                if(scrubbing)selectScrubIndex(value);else selectIndex(value,false);
            }
            @Override public void onStopTrackingTouch(SeekBar bar){
                scrubbing=false;request++;loader.cancelPending();scrubLoading=false;
                showPhoto(scrubDisplayedIndex==index);
            }
        });
        LinearLayout.LayoutParams sliderParams=new LinearLayout.LayoutParams(0,dp(48),1);
        sliderParams.leftMargin=dp(8);sliderParams.rightMargin=dp(8);
        navigation.addView(progress,sliderParams);
        next=arrow(true,"下一张");
        navigation.addView(next,new LinearLayout.LayoutParams(dp(48),dp(48)));
        previous.setOnClickListener(v->change(-1));
        next.setOnClickListener(v->change(1));
        FrameLayout.LayoutParams cardPosition=new FrameLayout.LayoutParams(multiple?-1:-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        cardPosition.leftMargin=dp(12);cardPosition.rightMargin=dp(12);
        page.addView(card,cardPosition);
        card.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(b-t!=ob-ot)updatePhotoBounds();});
        page.setOnApplyWindowInsetsListener((v,insets)->{topInset=insets.getSystemWindowInsetTop();bottomInset=Build.VERSION.SDK_INT>=30?Math.max(insets.getInsets(WindowInsets.Type.navigationBars()).bottom,insets.getInsets(WindowInsets.Type.systemGestures()).bottom):insets.getSystemWindowInsetBottom();updateInsets();return insets;});
        showScopeCover();
    }

    private ImageView arrow(boolean forward,String description){
        ImageView button=new ImageView(this);
        button.setScaleType(ImageView.ScaleType.FIT_XY);
        button.setImageDrawable(new PageArrow(forward));
        button.setContentDescription(description);
        button.setBackground(shape(CONTROL,24));
        return button;
    }
    private final class PageArrow extends Drawable {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path triangle=new Path();
        private final int direction;
        PageArrow(boolean forward){direction=forward?1:-1;paint.setColor(PRIMARY);}
        @Override public void draw(Canvas canvas){
            android.graphics.Rect bounds=getBounds();
            float x=bounds.exactCenterX(),y=bounds.exactCenterY();
            float halfWidth=dp(5),halfHeight=dp(7);
            triangle.rewind();
            triangle.moveTo(x+direction*halfWidth,y);
            triangle.lineTo(x-direction*halfWidth,y-halfHeight);
            triangle.lineTo(x-direction*halfWidth,y+halfHeight);
            triangle.close();
            canvas.drawPath(triangle,paint);
        }
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}
        @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
    private final class BackIcon extends android.graphics.drawable.Drawable {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(Canvas canvas){android.graphics.Rect r=getBounds();float cx=r.exactCenterX(),cy=r.exactCenterY(),arm=Math.min(r.width(),r.height())*.26f;paint.setColor(PRIMARY);paint.setStrokeWidth(dp(2));paint.setStyle(Paint.Style.STROKE);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeCap(Paint.Cap.ROUND);Path arrow=new Path();arrow.moveTo(cx+arm*.45f,cy-arm);arrow.lineTo(cx-arm*.55f,cy);arrow.lineTo(cx+arm*.45f,cy+arm);canvas.drawPath(arrow,paint);}
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}@Override public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
    private void updateInsets(){FrameLayout.LayoutParams t=(FrameLayout.LayoutParams)topControls.getLayoutParams();t.topMargin=topInset+dp(4);topControls.setLayoutParams(t);FrameLayout.LayoutParams s=(FrameLayout.LayoutParams)topShade.getLayoutParams();s.height=topInset+dp(96);topShade.setLayoutParams(s);FrameLayout.LayoutParams c=(FrameLayout.LayoutParams)card.getLayoutParams();c.bottomMargin=bottomInset+dp(32);card.setLayoutParams(c);updatePhotoBounds();}
    private void updatePhotoBounds(){if(photoArea==null||card==null)return;FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)photoArea.getLayoutParams();int bottom=card.getHeight()+bottomInset+dp(32);if(p.bottomMargin!=bottom){p.bottomMargin=bottom;photoArea.setLayoutParams(p);}}
    private void change(int offset){if(!scrubbing)selectIndex(index+offset,true);}
    private void selectIndex(int target,boolean animate){
        if(target<0||target>=photos.size()||target==index)return;
        direction=animate?Integer.compare(target,index):0;
        index=target;
        showPhoto(false);
    }
    private void selectScrubIndex(int target){
        if(target<0||target>=photos.size()||target==index)return;
        index=target;direction=0;
        if(!scrubLoading)loadScrubPreview();
    }
    private void loadScrubPreview(){
        if(!scrubbing||!activePreview||isFinishing())return;
        final int selected=index,ticket=request;
        PhotoItem item=photos.get(selected);
        if(item.uri==null)return;
        scrubLoading=true;
        loader.load(item.uri,item.mediaVersion,480,bitmap->{
            if(ticket!=request||!scrubbing||!activePreview||isFinishing())return;
            scrubLoading=false;
            if(bitmap!=null&&selected==index){
                image.setImageBitmap(bitmap);image.resetPreview();error.setVisibility(View.GONE);
                scrubDisplayedIndex=selected;
                displayedIndex=selected;
                updateNavigation();
            }
            if(selected!=index)loadScrubPreview();
        });
    }
    private void updateNavigation(){
        int shown=scrubbing&&displayedIndex>=0?displayedIndex:index;
        number.setText((shown+1)+" / "+photos.size());
        updatePhotoDetails(shown);
        if(!scrubbing)progress.setProgress(index);
        previous.setEnabled(index>0);next.setEnabled(index+1<photos.size());previous.setImageAlpha(previous.isEnabled()?255:90);next.setImageAlpha(next.isEnabled()?255:90);
    }
    private void updatePhotoDetails(int shown){
        if(shown<0||shown>=photos.size()||photoDate==null)return;
        if(metadataIndex==shown)return;
        metadataIndex=shown;
        int ticket=++metadataGeneration;
        metadataWorker.getQueue().clear();
        PhotoItem item=photos.get(shown);
        String time=item.dateTaken==null||item.dateTaken<=0?"拍摄时间未知":new SimpleDateFormat("yyyy年M月d日 HH:mm",Locale.getDefault()).format(new Date(item.dateTaken));
        photoDate.setText(time);photoSize.setText("文件大小 · 获取中…");
        if(item.uri==null){photoSize.setText("文件大小未知");return;}
        metadataWorker.execute(()->{
            long bytes=-1;
            try(Cursor cursor=getContentResolver().query(item.uri,new String[]{MediaStore.MediaColumns.SIZE},null,null,null)){
                if(cursor!=null&&cursor.moveToFirst()&&!cursor.isNull(0))bytes=cursor.getLong(0);
            }catch(RuntimeException ignored){}
            final long result=bytes;
            runOnUiThread(()->{
                if(!activePreview||isFinishing()||isDestroyed()||ticket!=metadataGeneration||metadataIndex!=shown)return;
                String size=result>=0?"文件大小 · "+android.text.format.Formatter.formatShortFileSize(this,result):"文件大小未知";
                photoSize.setText(size);
            });
        });
    }
    private void showPhoto(boolean keepPreview){
        photoShown=true;
        loader.cancelPending();int ticket=++request;PhotoItem item=photos.get(index);updateNavigation();
        image.animate().cancel();image.setAlpha(1f);image.setTranslationX(0);
        if(!keepPreview){image.setImageDrawable(null);displayedIndex=-1;}
        image.resetPreview();error.setVisibility(View.GONE);
        if(item.uri==null){error.setText("照片不可用");error.setVisibility(View.VISIBLE);return;}
        int size=Math.min(1440,Math.max(getResources().getDisplayMetrics().widthPixels,getResources().getDisplayMetrics().heightPixels));
        loadPreview(item,size,ticket);
    }
    private void loadPreview(PhotoItem item,int size,int ticket){
        if(!activePreview||ticket!=request||isFinishing())return;
        loader.load(item.uri,item.mediaVersion,size,b->{if(ticket!=request||isFinishing()||isDestroyed())return;
            if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0&&b!=null)android.util.Log.i("MAP004Preview","media thumbnail="+b.getWidth()+"x"+b.getHeight());
            if(b!=null)displayThumbnail(b,true);
            if(b==null||Math.max(b.getWidth(),b.getHeight())<size*.75f)loadBetterPreview(item,ticket,size,b);
        });
    }
    private void loadBetterPreview(PhotoItem item,int ticket,int size,Bitmap current){
        final long pixels=current==null?0:(long)current.getWidth()*current.getHeight();
        loader.loadSampled(item.uri,item.mediaVersion,size,b->{if(ticket!=request||isFinishing()||isDestroyed())return;
            if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0&&b!=null)android.util.Log.i("MAP004Preview","sampled preview="+b.getWidth()+"x"+b.getHeight());
            if(b!=null&&(long)b.getWidth()*b.getHeight()>pixels)displayThumbnail(b,current==null);else if(current==null){error.setText("预览加载失败，点击查看原图");error.setVisibility(View.VISIBLE);}
        });
    }
    private void displayThumbnail(Bitmap bitmap,boolean animate){displayedIndex=index;error.setVisibility(View.GONE);if(image.getDrawable()!=null)image.replacePreview(bitmap);else{image.setImageBitmap(bitmap);image.resetPreview();}if(animate&&direction!=0){image.setAlpha(0f);image.setTranslationX(dp(20)*direction);image.animate().alpha(1f).translationX(0).setDuration(180).start();}}
    private void openOriginal(){
        if(!activePreview||scopeCover!=null||openingOriginal||scrubbing)return;
        if(displayedIndex!=index&&error.getVisibility()!=View.VISIBLE){
            Toast.makeText(this,"正在加载所选照片",Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri=photos.get(index).uri;
        if(uri==null){Toast.makeText(this,"照片不可用",Toast.LENGTH_SHORT).show();return;}
        openingOriginal=true;
        Intent original=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        for(String packageName:new String[]{"com.vivo.gallery","com.android.gallery3d"}){
            try{startActivity(new Intent(original).setPackage(packageName));return;}
            catch(android.content.ActivityNotFoundException|SecurityException unavailable){}
        }
        try{startActivity(original);}
        catch(android.content.ActivityNotFoundException|SecurityException unavailable){
            openingOriginal=false;
            Toast.makeText(this,"无法打开原生相册",Toast.LENGTH_SHORT).show();
        }
    }
    private void showScopeCover(){
        if(page==null)return;
        TextView cover=(TextView)scopeCover;
        if(cover==null){
            cover=text("",14,SECONDARY);
            cover.setGravity(Gravity.CENTER);
            cover.setBackgroundColor(BACKGROUND);
            cover.setClickable(true);
            cover.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            scopeCover=cover;
            page.addView(cover,new FrameLayout.LayoutParams(-1,-1));
        }
        cover.removeCallbacks(showScopeMessage);
        cover.setText("");
        if(activePreview)cover.postDelayed(showScopeMessage,200);
    }
    private void hideScopeCover(){
        if(scopeCover!=null){
            scopeCover.removeCallbacks(showScopeMessage);
            page.removeView(scopeCover);
            scopeCover=null;
        }
    }
    @Override protected void onResume(){
        super.onResume();
        if(openingOriginal){openingOriginal=false;finish();return;}
        activePreview=true;if(page==null)return;showScopeCover();
        long verification=++scopeVerification;
        if(!MainActivity.hasPhotoAccess(this)){finishUnavailable();return;}
        if(session.singleId>=0){
            MainActivity.verifySinglePreview(session,metadata->runOnUiThread(()->{
                if(!activePreview||isFinishing()||verification!=scopeVerification)return;
                if(metadata==null||metadata.uri==null||!MainActivity.hasPhotoAccess(this)){finishUnavailable();return;}
                PhotoItem previous=photos.get(0);
                photos=Collections.singletonList(new PhotoItem(metadata.uri,metadata.dateTaken,metadata.mediaVersion));
                if(displayedIndex!=0||!metadata.uri.equals(previous.uri)||previous.mediaVersion!=metadata.mediaVersion)showPhoto(false);else updateNavigation();
                hideScopeCover();
            }));
        }else{
            MainActivity.verifyPreviewScope(session,ok->runOnUiThread(()->{
                if(!activePreview||isFinishing()||verification!=scopeVerification)return;
                if(!ok||!MainActivity.hasPhotoAccess(this)){finishUnavailable();return;}
                if(!photoShown||displayedIndex!=index)showPhoto(false);else updateNavigation();
                hideScopeCover();
            }));
        }
    }
    private void finishUnavailable(){
        Toast.makeText(this,"照片不可访问，请返回地图重新加载",Toast.LENGTH_LONG).show();
        finish();
    }
    @Override protected void onPause(){activePreview=false;metadataGeneration++;metadataIndex=-1;metadataWorker.getQueue().clear();scopeVerification++;showScopeCover();scrubbing=false;scrubLoading=false;request++;if(loader!=null)loader.cancelPending();MainActivity.suspendPreviewScope(session);super.onPause();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putInt("index",index);out.putBoolean("openingOriginal",openingOriginal);}
    @Override protected void onDestroy(){metadataGeneration++;metadataWorker.shutdownNow();request++;if(image!=null)image.animate().cancel();if(loader!=null)loader.close();if(isFinishing())PreviewSessionStore.clear(getIntent().getStringExtra("session"));super.onDestroy();}
}
