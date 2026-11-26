package io.github.szzeng.mapalbum;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** Zooms the in-memory preview thumbnail; the original remains in the native gallery. */
final class ZoomablePreview extends ImageView {
    private final Matrix transform=new Matrix();
    private final ScaleGestureDetector detector;
    interface SwipeListener { void onSwipe(boolean next); }
    private SwipeListener swipeListener;
    private float baseScale=1,zoom=1,lastX,lastY,startX,startY;
    private boolean dragging,multiTouch;
    ZoomablePreview(Context context){
        super(context);setScaleType(ScaleType.MATRIX);setContentDescription("照片预览，可双指缩放");
        detector=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector gesture){
                float next=Math.max(1,Math.min(4,zoom*gesture.getScaleFactor()));float factor=next/zoom;zoom=next;
                transform.postScale(factor,factor,gesture.getFocusX(),gesture.getFocusY());constrain();return true;
            }
        });
    }
    void setSwipeListener(SwipeListener listener){swipeListener=listener;}
    @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){super.onSizeChanged(w,h,oldW,oldH);fit();}
    void resetPreview(){post(this::fit);}
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
        if(getDrawable()==null)return false;
        detector.onTouchEvent(event);
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);startX=lastX=event.getX();startY=lastY=event.getY();dragging=true;multiTouch=false;return true;
            case MotionEvent.ACTION_POINTER_DOWN: dragging=false;multiTouch=true;break;
            case MotionEvent.ACTION_MOVE:
                if(dragging&&!detector.isInProgress()&&zoom>1){transform.postTranslate(event.getX()-lastX,event.getY()-lastY);constrain();}
                lastX=event.getX();lastY=event.getY();break;
            case MotionEvent.ACTION_POINTER_UP:
                if(event.getPointerCount()==2){int remaining=event.getActionIndex()==0?1:0;lastX=event.getX(remaining);lastY=event.getY(remaining);dragging=true;}else dragging=false;break;
            case MotionEvent.ACTION_UP:
                float dx=event.getX()-startX,dy=event.getY()-startY;
                if(!multiTouch&&zoom<=1.01f&&Math.abs(dx)>64*getResources().getDisplayMetrics().density&&Math.abs(dx)>Math.abs(dy)*1.3f&&swipeListener!=null)swipeListener.onSwipe(dx<0);
                dragging=false;getParent().requestDisallowInterceptTouchEvent(false);break;
            case MotionEvent.ACTION_CANCEL:
                dragging=false;getParent().requestDisallowInterceptTouchEvent(false);break;
        }
        return true;
    }
}
