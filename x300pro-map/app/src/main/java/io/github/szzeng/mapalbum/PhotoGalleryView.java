package io.github.szzeng.mapalbum;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.NumberFormat;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.example.map002probe.PhotoTimeline;

final class PhotoGalleryView extends LinearLayout {
    private static final int COLUMNS=4, TILE_GAP_DP=5, SIDE_PADDING_DP=14, GRID_PADDING_DP=5;
    private static final int RAIL_WIDTH_DP=24, RAIL_EDGE_DP=1;
    private static final int SCROLL_MONTH_HEIGHT_DP=46;
    private final int primary, secondary, controlText, control, ripple, placeholder;
    private final NumberFormat number=NumberFormat.getIntegerInstance(Locale.CHINA);
    private final ThumbnailLoader thumbnails;
    private final BooleanSupplier canLoad;
    private final IntConsumer openPhoto;
    private final TextView total, sort;
    private final FrameLayout sortTarget;
    private final TextView scrollMonth, loading;
    private final ListView grid;
    private final PhotoRows adapter;
    private boolean oldestFirst;

    PhotoGalleryView(Context context,ThumbnailLoader thumbnails,BooleanSupplier canLoad,
                     Runnable close,Consumer<Boolean> changeSort,IntConsumer openPhoto){
        super(context);
        this.thumbnails=thumbnails;
        this.canLoad=canLoad;
        this.openPhoto=openPhoto;
        primary=context.getColor(R.color.app_text);
        secondary=context.getColor(R.color.app_secondary);
        controlText=context.getColor(R.color.gallery_control_text);
        control=context.getColor(R.color.gallery_control);
        ripple=context.getColor(R.color.gallery_ripple);
        placeholder=context.getColor(R.color.app_placeholder);
        setOrientation(VERTICAL);
        setBackgroundColor(context.getColor(R.color.gallery_background));
        setElevation(dp(8));
        setClickable(true);
        setAccessibilityPaneTitle("此处照片");

        LinearLayout bar=new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(context.getColor(R.color.gallery_header));
        bar.setPadding(dp(SIDE_PADDING_DP),dp(5),dp(SIDE_PADDING_DP),dp(5));
        bar.setMinimumHeight(dp(58));
        addView(bar,new LayoutParams(-1,-2));

        ImageView back=new ImageView(context);
        back.setImageDrawable(new BackIcon());
        back.setPadding(dp(7),dp(7),dp(7),dp(7));
        back.setBackground(interactiveBackground(control,16));
        FrameLayout backTarget=new FrameLayout(context);
        backTarget.addView(back,new FrameLayout.LayoutParams(dp(32),dp(32),Gravity.CENTER));
        backTarget.setContentDescription("返回地图");
        backTarget.setOnClickListener(v->close.run());
        bar.addView(backTarget,new LayoutParams(dp(48),dp(48)));

        LinearLayout identity=new LinearLayout(context);
        identity.setOrientation(VERTICAL);
        LayoutParams identityParams=new LayoutParams(0,-2,1);
        identityParams.setMarginStart(dp(4));
        identityParams.setMarginEnd(dp(8));
        bar.addView(identity,identityParams);
        TextView title=text(18,primary,true);
        title.setText("此处照片");
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setAccessibilityHeading(true);
        identity.addView(title,new LayoutParams(-1,-2));
        total=text(12,secondary,false);
        LayoutParams totalParams=new LayoutParams(-1,-2);
        totalParams.topMargin=dp(3);
        identity.addView(total,totalParams);

        sort=text(13,controlText,true);
        sort.setSingleLine();
        sort.setPadding(dp(10),0,dp(10),0);
        sort.setGravity(Gravity.CENTER);
        sort.setBackground(interactiveBackground(control,16));
        sort.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        sortTarget=new FrameLayout(context);
        sortTarget.addView(sort,new FrameLayout.LayoutParams(-2,dp(30),Gravity.CENTER));
        sortTarget.setOnClickListener(v->changeSort.accept(!oldestFirst));
        bar.addView(sortTarget,new LayoutParams(-2,dp(48)));

        View line=new View(context);
        line.setBackgroundColor(context.getColor(R.color.app_divider));
        addView(line,new LayoutParams(-1,Math.max(1,dp(0.5f))));

        grid=new ListView(context);
        grid.setDivider(null);
        grid.setDividerHeight(0);
        grid.setPadding(dp(GRID_PADDING_DP),dp(4),dp(GRID_PADDING_DP),dp(14));
        grid.setClipToPadding(true);
        grid.setVerticalScrollBarEnabled(false);
        adapter=new PhotoRows();
        grid.setAdapter(adapter);
        grid.setRecyclerListener(view->{if(view instanceof PhotoGridRow)((PhotoGridRow)view).clearImages();});
        FrameLayout listArea=new FrameLayout(context);
        listArea.addView(grid,new FrameLayout.LayoutParams(-1,-1));
        loading=text(14,secondary,false);
        loading.setText("正在读取照片…");
        loading.setGravity(Gravity.CENTER);
        loading.setVisibility(View.GONE);
        listArea.addView(loading,new FrameLayout.LayoutParams(-1,-1));
        scrollMonth=text(18,controlText,true);
        scrollMonth.setPadding(dp(16),0,dp(16),0);
        scrollMonth.setBackground(rounded(control,20));
        scrollMonth.setVisibility(View.GONE);
        scrollMonth.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams bubble=new FrameLayout.LayoutParams(-2,dp(SCROLL_MONTH_HEIGHT_DP),Gravity.TOP|Gravity.END);
        bubble.setMarginEnd(dp(30));
        listArea.addView(scrollMonth,bubble);

        ScrollRail rail=new ScrollRail(context);
        FrameLayout.LayoutParams railParams=new FrameLayout.LayoutParams(dp(RAIL_WIDTH_DP),-1,Gravity.END);
        railParams.setMarginEnd(dp(RAIL_EDGE_DP));
        railParams.topMargin=dp(5);
        railParams.bottomMargin=dp(5);
        listArea.addView(rail,railParams);
        grid.setOnScrollListener(new AbsListView.OnScrollListener(){
            @Override public void onScrollStateChanged(AbsListView list,int state){}
            @Override public void onScroll(AbsListView list,int first,int visible,int total){rail.update(first,visible,total);}
        });
        addView(listArea,new LayoutParams(-1,0,1));
    }

    void setPhotos(GalleryPhotoList photos,boolean oldest){
        loading.setVisibility(View.GONE);
        sortTarget.setVisibility(View.VISIBLE);
        oldestFirst=oldest;
        total.setText(number.format(photos==null?0:photos.size())+" 张照片");
        String order=oldest?"最早优先":"最新优先";
        sort.setText(order+"  ⇅");
        sortTarget.setContentDescription("照片排序，当前"+order+"，点击切换");
        adapter.setPhotos(photos);
    }

    void showLoading(int totalPhotos){
        adapter.setPhotos(null);
        total.setText(number.format(totalPhotos)+" 张照片");
        sortTarget.setVisibility(View.INVISIBLE);
        loading.setVisibility(View.VISIBLE);
    }

    int firstVisiblePhoto(){
        int count=adapter.getCount();
        return count==0?0:adapter.getItem(Math.max(0,Math.min(grid.getFirstVisiblePosition(),count-1))).first;
    }

    int scrollOffset(){
        View first=grid.getChildAt(0);
        return first==null?0:first.getTop()-grid.getPaddingTop();
    }

    void scrollToPhoto(int index,int offset){
        if(adapter.getCount()>0)grid.setSelectionFromTop(adapter.rowForPhoto(index),offset);
    }

    void cancelImages(){visitVisibleRows(PhotoGridRow::cancelImages);}
    void restartImages(){visitVisibleRows(PhotoGridRow::restartImages);}
    void clearImages(){visitVisibleRows(PhotoGridRow::clearImages);}

    private void visitVisibleRows(Consumer<PhotoGridRow> operation){
        for(int i=0;i<grid.getChildCount();i++){
            if(grid.getChildAt(i) instanceof PhotoGridRow)operation.accept((PhotoGridRow)grid.getChildAt(i));
        }
    }

    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}

    private TextView text(int size,int color,boolean bold){
        TextView view=new TextView(getContext());
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(false);
        if(bold)view.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));
        return view;
    }

    private GradientDrawable rounded(int color,int radius){
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private RippleDrawable interactiveBackground(int color,int radius){
        return new RippleDrawable(ColorStateList.valueOf(ripple),rounded(color,radius),null);
    }

    private static final class DayHeader {
        final String date,year;
        final int count;
        DayHeader(String date,String year,int count){
            this.date=date;this.year=year;this.count=count;
        }
    }

    private static final class PhotoRow {
        final int first,count;
        final DayHeader heading;
        PhotoRow(int first,int count,DayHeader heading){
            this.first=first;this.count=count;this.heading=heading;
        }
    }

    private final class PhotoRows extends BaseAdapter {
        private GalleryPhotoList photos;
        private List<PhotoTimeline.Day> days=Collections.emptyList();
        private int[] firstRows={};
        private int rowCount;

        void setPhotos(GalleryPhotoList items){
            photos=items;
            days=items==null?Collections.emptyList():items.days();
            firstRows=new int[days.size()];
            rowCount=0;
            for(int i=0;i<days.size();i++){
                firstRows[i]=rowCount;
                rowCount+=(days.get(i).count+COLUMNS-1)/COLUMNS;
            }
            notifyDataSetChanged();
        }

        int rowForPhoto(int index){
            int low=0,high=days.size()-1;
            while(low<high){
                int middle=(low+high+1)>>>1;
                if(days.get(middle).first<=index)low=middle;else high=middle-1;
            }
            return firstRows[low]+(index-days.get(low).first)/COLUMNS;
        }

        private int dayForRow(int row){
            int low=0,high=firstRows.length-1;
            while(low<high){
                int middle=(low+high+1)>>>1;
                if(firstRows[middle]<=row)low=middle;else high=middle-1;
            }
            return low;
        }

        @Override public int getCount(){return rowCount;}
        @Override public PhotoRow getItem(int position){
            int section=dayForRow(position);
            PhotoTimeline.Day day=days.get(section);
            int offset=(position-firstRows[section])*COLUMNS;
            DayHeader heading=null;
            if(offset==0){
                String label=day.date==null?"日期未知":day.date.getMonthValue()+"月"+day.date.getDayOfMonth()+"日";
                String year=day.date==null?"":day.date.getYear()+"年";
                heading=new DayHeader(label,year,day.count);
            }
            return new PhotoRow(day.first+offset,Math.min(COLUMNS,day.count-offset),heading);
        }
        @Override public long getItemId(int position){return position;}
        String monthAt(int position){
            if(days.isEmpty())return "";
            PhotoTimeline.Day day=days.get(dayForRow(Math.min(position,rowCount-1)));
            return day.date==null?"日期未知":day.date.getYear()+"年"+day.date.getMonthValue()+"月";
        }
        @Override public View getView(int position,View recycled,ViewGroup parent){
            PhotoGridRow row=recycled instanceof PhotoGridRow?(PhotoGridRow)recycled:new PhotoGridRow();
            int width=parent.getWidth();
            if(width<=0)width=PhotoGalleryView.this.getWidth();
            if(width<=0)width=getResources().getDisplayMetrics().widthPixels;
            int tile=Math.max(1,(width-parent.getPaddingLeft()-parent.getPaddingRight()-(COLUMNS-1)*dp(TILE_GAP_DP))/COLUMNS);
            row.bind(getItem(position),photos,tile);
            return row;
        }
    }

    private final class PhotoGridRow extends LinearLayout {
        private final LinearLayout dayHeading;
        private final TextView date,year,dayCount;
        private final PhotoCell[] cells=new PhotoCell[COLUMNS];

        PhotoGridRow(){
            super(PhotoGalleryView.this.getContext());
            setOrientation(VERTICAL);
            Context context=getContext();
            dayHeading=new LinearLayout(context);
            dayHeading.setGravity(Gravity.BOTTOM);
            dayHeading.setPaddingRelative(dp(1),dp(15),dp(RAIL_WIDTH_DP+RAIL_EDGE_DP),dp(9));
            dayHeading.setAccessibilityHeading(true);
            dayHeading.setScreenReaderFocusable(true);
            addView(dayHeading,new LayoutParams(-1,-2));
            date=text(16,primary,true);
            dayHeading.addView(date,new LayoutParams(-2,-2));
            year=text(12,secondary,false);
            LayoutParams yearParams=new LayoutParams(0,-2,1);
            yearParams.setMarginStart(dp(7));
            dayHeading.addView(year,yearParams);
            dayCount=text(12,secondary,false);
            dayHeading.addView(dayCount,new LayoutParams(-2,-2));

            LinearLayout pictures=new LinearLayout(context);
            pictures.setPadding(0,0,0,dp(TILE_GAP_DP));
            addView(pictures,new LayoutParams(-1,-2));
            for(int i=0;i<COLUMNS;i++){
                cells[i]=new PhotoCell();
                LayoutParams params=new LayoutParams(0,0,1);
                if(i>0)params.setMarginStart(dp(TILE_GAP_DP));
                pictures.addView(cells[i],params);
            }
        }

        void bind(PhotoRow row,List<PhotoItem> photos,int size){
            DayHeader heading=row.heading;
            dayHeading.setVisibility(heading==null?View.GONE:View.VISIBLE);
            if(heading!=null){
                date.setText(heading.date);
                year.setText(heading.year);
                dayCount.setText(number.format(heading.count)+" 张");
            }
            for(int i=0;i<COLUMNS;i++)cells[i].bind(i<row.count?photos.get(row.first+i):null,size,row.first+i);
        }

        void clearImages(){for(PhotoCell cell:cells)cell.bind(null,cell.tileSize,0);}
        void cancelImages(){for(PhotoCell cell:cells)cell.cancel();}
        void restartImages(){for(PhotoCell cell:cells)cell.loadImage();}
    }

    private final class PhotoCell extends ImageView {
        private PhotoItem photo;
        private int photoIndex;
        private int tileSize;
        private ThumbnailLoader.Request request;
        private boolean retryPending;
        private final Runnable retry=()->{retryPending=false;loadImage();};

        PhotoCell(){
            super(PhotoGalleryView.this.getContext());
            setScaleType(ScaleType.CENTER_CROP);
            setBackground(rounded(placeholder,7));
            setForeground(new RippleDrawable(ColorStateList.valueOf(ripple),null,rounded(0xffffffff,7)));
            setClipToOutline(true);
            setOnClickListener(v->{if(photo!=null&&canLoad.getAsBoolean())openPhoto.accept(photoIndex);});
        }
        void bind(PhotoItem next,int size,int index){
            photoIndex=index;
            if(photo!=next||tileSize!=size){
                cancel();
                setImageDrawable(null);
                photo=next;
                tileSize=size;
                setVisibility(photo==null?View.INVISIBLE:View.VISIBLE);
                ViewGroup.LayoutParams params=getLayoutParams();
                if(params.height!=size){params.height=size;setLayoutParams(params);}
            }
            setContentDescription(photo==null?null:"查看第 "+(index+1)+" 张照片");
            loadImage();
        }
        void loadImage(){
            if(photo==null||tileSize<=1||getDrawable()!=null||request!=null||retryPending||!canLoad.getAsBoolean())return;
            PhotoItem bound=photo;
            request=thumbnails.tryLoad(bound.uri,bound.mediaVersion,Math.min(512,tileSize),bitmap->{
                request=null;
                if(bound==photo&&canLoad.getAsBoolean())setImageBitmap(bitmap);
            });
            if(request==null){retryPending=true;postDelayed(retry,120);}
        }
        void cancel(){
            if(request!=null){request.cancel();request=null;}
            if(retryPending){removeCallbacks(retry);retryPending=false;}
        }
        @Override protected void onDetachedFromWindow(){cancel();super.onDetachedFromWindow();}
    }

    /** A translucent scroll handle overlaid on the outer edge of the photos. */
    private final class ScrollRail extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private int first,visible,total;
        private boolean dragging;

        ScrollRail(Context context){
            super(context);
            setVisibility(INVISIBLE);
            setClickable(true);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void update(int firstRow,int visibleRows,int totalRows){
            first=firstRow;
            visible=visibleRows;
            total=totalRows;
            setVisibility(visible>0&&(dragging||grid.canScrollVertically(-1)||grid.canScrollVertically(1))?VISIBLE:INVISIBLE);
            invalidate();
        }

        private float thumbLength(){
            return Math.min(getHeight()*.85f,Math.max(dp(56),getHeight()*(float)visible/total));
        }

        @Override protected void onDraw(Canvas canvas){
            if(total==0||getHeight()==0)return;
            float length=thumbLength();
            float fraction=!grid.canScrollVertically(1)&&grid.canScrollVertically(-1)?1f:Math.min(1,first/(float)Math.max(1,total-visible));
            float top=(getHeight()-length)*fraction;
            float x=getWidth()/2f,radius=dp(dragging?5:3);
            paint.setColor(controlText);
            paint.setAlpha(dragging?200:132);
            canvas.drawRoundRect(x-radius,top,x+radius,top+length,radius,radius,paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event){
            if(total==0||getHeight()==0)return false;
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    dragging=true;
                    invalidate();
                    getParent().requestDisallowInterceptTouchEvent(true);
                    scrollTo(event.getY());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if(dragging)scrollTo(event.getY());
                    return true;
                case MotionEvent.ACTION_UP:
                    if(dragging)scrollTo(event.getY());
                    performClick();
                    finishDrag();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    finishDrag();
                    return true;
                default:return true;
            }
        }

        private void scrollTo(float y){
            float length=thumbLength();
            float fraction=Math.max(0,Math.min(1,(y-length/2)/Math.max(1,getHeight()-length)));
            int row=Math.min(total-1,Math.round(fraction*Math.max(1,total-visible)));
            grid.setSelection(row);
            scrollMonth.setText(adapter.monthAt(row));
            FrameLayout.LayoutParams bubble=(FrameLayout.LayoutParams)scrollMonth.getLayoutParams();
            int height=dp(SCROLL_MONTH_HEIGHT_DP);
            int parentHeight=((View)getParent()).getHeight();
            bubble.topMargin=Math.max(0,Math.min(Math.max(0,parentHeight-height),Math.round(getTop()+y-height-dp(24))));
            scrollMonth.setLayoutParams(bubble);
            scrollMonth.setVisibility(VISIBLE);
        }

        private void finishDrag(){
            dragging=false;
            getParent().requestDisallowInterceptTouchEvent(false);
            scrollMonth.setVisibility(GONE);
            update(grid.getFirstVisiblePosition(),grid.getChildCount(),adapter.getCount());
        }

        @Override public boolean performClick(){return super.performClick();}
    }

    private final class BackIcon extends Drawable {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(Canvas canvas){
            Rect bounds=getBounds();
            float x=bounds.exactCenterX(),y=bounds.exactCenterY(),arm=Math.min(bounds.width(),bounds.height())*.25f;
            paint.setColor(controlText);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(x+arm,y,x-arm,y,paint);
            canvas.drawLine(x-arm,y,x,y-arm,paint);
            canvas.drawLine(x-arm,y,x,y+arm,paint);
        }
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}
        @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
