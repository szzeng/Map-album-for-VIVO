package io.github.szzeng.mapalbum;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewConfiguration;
import android.widget.ImageView;

/** Zooms the in-memory preview thumbnail; the original remains in the native gallery. */
final class ZoomablePreview extends ImageView {
    private final Matrix transform=new Matrix();
    private final ScaleGestureDetector detector;
    private final GestureDetector taps;
    interface SwipeListener { void onSwipe(boolean next); }
    private SwipeListener swipeListener;
    private Runnable openOriginalListener;
    private float baseScale=1,zoom=1,lastX,lastY,startX,startY;
    private final int touchSlop;
    private int activePointer=-1;
    private boolean dragging,multiTouch,moved,gestureStartedZoomed,doubleTap;
    ZoomablePreview(Context context){
        super(context);setScaleType(ScaleType.MATRIX);setContentDescription("照片预览，双击在原生相册打开原图");setClickable(true);touchSlop=ViewConfiguration.get(context).getScaledTouchSlop();
        taps=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent event){return true;}
            @Override public boolean onDoubleTap(MotionEvent event){doubleTap=true;return true;}
        });
        detector=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector gesture){
                float next=Math.max(1,Math.min(4,zoom*gesture.getScaleFactor()));float factor=next/zoom;zoom=next;
                transform.postScale(factor,factor,gesture.getFocusX(),gesture.getFocusY());constrain();return true;
            }
        });
        detector.setQuickScaleEnabled(false);
    }
    void setSwipeListener(SwipeListener listener){swipeListener=listener;}
    void setOpenOriginalListener(Runnable listener){openOriginalListener=listener;}
    @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){super.onSizeChanged(w,h,oldW,oldH);fit();}
    void resetPreview(){zoom=1;transform.reset();setImageMatrix(transform);fit();}
    void replacePreview(Bitmap bitmap){
        Drawable current=getDrawable();float oldZoom=zoom,focusX=.5f,focusY=.5f;
        if(current!=null&&current.getIntrinsicWidth()>0&&current.getIntrinsicHeight()>0){RectF bounds=new RectF(0,0,current.getIntrinsicWidth(),current.getIntrinsicHeight());transform.mapRect(bounds);if(bounds.width()>0&&bounds.height()>0){focusX=(getWidth()/2f-bounds.left)/bounds.width();focusY=(getHeight()/2f-bounds.top)/bounds.height();}}
        setImageBitmap(bitmap);fit();
        if(oldZoom>1&&getDrawable()!=null){zoom=oldZoom;transform.postScale(oldZoom,oldZoom,getWidth()/2f,getHeight()/2f);RectF bounds=new RectF(0,0,getDrawable().getIntrinsicWidth(),getDrawable().getIntrinsicHeight());transform.mapRect(bounds);transform.postTranslate(getWidth()/2f-(bounds.left+focusX*bounds.width()),getHeight()/2f-(bounds.top+focusY*bounds.height()));constrain();}
    }
    private void fit(){
        Drawable d=getDrawable();if(d==null||d.getIntrinsicWidth()<=0||d.getIntrinsicHeight()<=0||getWidth()<=0||getHeight()<=0)return;
        baseScale=Math.min((float)getWidth()/d.getIntrinsicWidth(),(float)getHeight()/d.getIntrinsicHeight());zoom=1;
        float x=(getWidth()-d.getIntrinsicWidth()*baseScale)/2f,y=(getHeight()-d.getIntrinsicHeight()*baseScale)/2f;
        transform.reset();transform.postScale(baseScale,baseScale);transform.postTranslate(x,y);setImageMatrix(transform);
    }
    private void constrain(){
        Drawable d=getDrawable();if(d==null)return;
        RectF bounds=new RectF(0,0,d.getIntrinsicWidth(),d.getIntrinsicHeight());transform.mapRect(bounds);
        float dx=0,dy=0;
        if(bounds.width()<=getWidth())dx=(getWidth()-bounds.width())/2-bounds.left;
        else if(bounds.left>0)dx=-bounds.left;else if(bounds.right<getWidth())dx=getWidth()-bounds.right;
        if(bounds.height()<=getHeight())dy=(getHeight()-bounds.height())/2-bounds.top;
        else if(bounds.top>0)dy=-bounds.top;else if(bounds.bottom<getHeight())dy=getHeight()-bounds.bottom;
        transform.postTranslate(dx,dy);setImageMatrix(transform);
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN)doubleTap=false;
        taps.onTouchEvent(event);
        if(getDrawable()!=null)detector.onTouchEvent(event);
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);activePointer=event.getPointerId(0);startX=lastX=event.getX();startY=lastY=event.getY();dragging=true;multiTouch=false;moved=false;gestureStartedZoomed=zoom>1.01f;return true;
            case MotionEvent.ACTION_POINTER_DOWN: dragging=false;multiTouch=true;break;
            case MotionEvent.ACTION_MOVE:
                int at=event.findPointerIndex(activePointer);if(at<0)break;float x=event.getX(at),y=event.getY(at);
                if(Math.abs(x-startX)>touchSlop||Math.abs(y-startY)>touchSlop)moved=true;
                if(dragging&&!detector.isInProgress()&&zoom>1){transform.postTranslate(x-lastX,y-lastY);constrain();}
                lastX=x;lastY=y;break;
            case MotionEvent.ACTION_POINTER_UP:
                if(event.getPointerCount()==2){int remaining=event.getActionIndex()==0?1:0;activePointer=event.getPointerId(remaining);lastX=event.getX(remaining);lastY=event.getY(remaining);dragging=true;}else dragging=false;break;
            case MotionEvent.ACTION_UP:
                float dx=event.getX()-startX,dy=event.getY()-startY;
                if(!multiTouch&&!gestureStartedZoomed&&zoom<=1.01f&&Math.abs(dx)>64*getResources().getDisplayMetrics().density&&Math.abs(dx)>Math.abs(dy)*1.3f&&swipeListener!=null)swipeListener.onSwipe(dx<0);
                else if(doubleTap&&!multiTouch&&!moved&&Math.abs(dx)<=touchSlop&&Math.abs(dy)<=touchSlop
                    &&event.getEventTime()-event.getDownTime()<ViewConfiguration.getLongPressTimeout())performClick();
                dragging=false;activePointer=-1;getParent().requestDisallowInterceptTouchEvent(false);break;
            case MotionEvent.ACTION_CANCEL:
                dragging=false;activePointer=-1;getParent().requestDisallowInterceptTouchEvent(false);break;
        }
        return true;
    }
    @Override public boolean performClick(){
        super.performClick();
        if(openOriginalListener==null)return false;
        openOriginalListener.run();
        return true;
    }
}
