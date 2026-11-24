package org.example.map002probe;

import android.os.CancellationSignal;
import android.os.SystemClock;

/** Large synthetic fixture, run on the phone; not evidence about real locations. */
final class PrimitiveLocationSetProbe {
    private static void check(boolean value){if(!value)throw new AssertionError("primitive fixture failed");}
    static void run() {
        final int count=69637;PrimitiveLocationSet data=new PrimitiveLocationSet(count);
        for(int i=0;i<count;i++)data.add(i+1,(i%179)-89.5,(i%359)-179.5);
        data.seal();check(data.size()==count);
        CancellationSignal cancel=new CancellationSignal();int[] out=new int[count];
        long start=SystemClock.elapsedRealtimeNanos();
        int all=data.query(-90,90,-180,180,out,cancel);
        long worldUs=(SystemClock.elapsedRealtimeNanos()-start)/1000;check(all==count);
        for(int i=0;i<count;i++)check(data.idAt(out[i])==i+1);
        int matches=data.query(-30,30,170,-170,out,cancel),expected=0;
        for(int i=0;i<count;i++) {
            double x=(i%179)-89.5,y=(i%359)-179.5;
            if(x>=-30 && x<=30 && (y>=170 || y<=-170)){check(data.idAt(out[expected])==i+1);expected++;}
        }
        check(matches==expected);
        int[] small=new int[2];int total=data.query(-90,90,-180,180,small,cancel);
        check(total==count && small[0]==0 && small[1]==1);
        CancellationSignal cancelled=new CancellationSignal();cancelled.cancel();boolean observed=false;
        try{data.query(-90,90,-180,180,out,cancelled);}catch(android.os.OperationCanceledException e){observed=true;}
        check(observed);data.close();check(data.size()==0);
        boolean inaccessible=false;try{data.idAt(0);}catch(IllegalStateException e){inaccessible=true;}check(inaccessible);
        PrimitiveLocationSet limited=new PrimitiveLocationSet(1);limited.add(1,1,1);boolean overflow=false;
        try{limited.add(2,2,2);}catch(IllegalStateException e){overflow=true;}check(overflow);limited.close();
        ReadOnlyQuery.log("primitive_fixture_pass=true synthetic_points="+count+" exact_world=true exact_wrap=true truncated_detected=true pre_cancel=true clear=true capacity=true world_scan_us="+worldUs);
    }
}
