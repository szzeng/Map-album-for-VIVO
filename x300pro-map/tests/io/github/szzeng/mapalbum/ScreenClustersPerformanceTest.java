package io.github.szzeng.mapalbum;

import java.util.*;
import java.lang.management.*;

public final class ScreenClustersPerformanceTest {
    private static void run(String name,int[] x,int[] y,int scale){
        Runtime rt=Runtime.getRuntime();System.gc();long before=rt.totalMemory()-rt.freeMemory();
        for(MemoryPoolMXBean pool:ManagementFactory.getMemoryPoolMXBeans())if(pool.getType()==MemoryType.HEAP)pool.resetPeakUsage();
        long start=System.nanoTime();
        List<ScreenClusters.Cluster> result=ScreenClusters.group(x,y,48,new int[]{8,12,16,24},100,()->false);
        long elapsed=(System.nanoTime()-start)/1000000,used=rt.totalMemory()-rt.freeMemory();
        boolean[] seen=new boolean[x.length];int count=0;for(ScreenClusters.Cluster c:result)for(int id:c.members){if(id<0||id>=x.length||seen[id])throw new AssertionError("invalid member");seen[id]=true;count++;}
        if(count!=x.length)throw new AssertionError("missing members");
        for(int i=0;i<result.size();i++)for(int j=0;j<i;j++){ScreenClusters.Cluster a=result.get(i),b=result.get(j);long dx=Math.max(0,Math.abs((long)a.x-b.x)-48),dy=Math.max(0,Math.abs((long)a.y-b.y)-48);if(dx*dx+dy*dy<=64)throw new AssertionError("collision");}
        long peak=0;for(MemoryPoolMXBean pool:ManagementFactory.getMemoryPoolMXBeans())if(pool.getType()==MemoryType.HEAP)peak+=pool.getPeakUsage().getUsed();
        System.out.println(name+" scale="+scale+" photos="+count+" markers="+result.size()+" ms="+elapsed+" heapBeforeMiB="+(before/1048576)+" heapAfterMiB="+(used/1048576)+" heapPoolPeakMiB="+(peak/1048576));
    }
    public static void main(String[] args){
        int n=69637;int[] x=new int[n],y=new int[n];Random random=new Random(6006);
        for(int i=0;i<n;i++){x[i]=random.nextInt(1260);y[i]=random.nextInt(2800);}run("viewport",x,y,1);
        int[] translatedX=new int[n],translatedY=new int[n],sources=new int[n];
        for(int i=0;i<n;i++){translatedX[i]=x[i]+10;translatedY[i]=y[i]+20;sources[i]=i;}
        long panStart=System.nanoTime();long[] offset=ScreenClusters.translation(x,y,sources,translatedX,translatedY,sources,n);
        if(offset==null||offset[0]!=10||offset[1]!=20)throw new AssertionError("pan translation");
        System.out.println("pan_reuse_check photos="+n+" ms="+(System.nanoTime()-panStart)/1000000);
        Arrays.fill(x,400);Arrays.fill(y,400);run("same_position",x,y,1);
        for(int i=0;i<n;i++){x[i]=400+random.nextInt(300);y[i]=400+random.nextInt(300);}run("local_dense",x,y,1);
        for(int i=0;i<n;i++){x[i]=100+(i%30)*2;y[i]=100+(i%20)*2;}run("repeated_pixels",x,y,1);
        for(int scale:new int[]{1,2,4}){for(int i=0;i<n;i++){x[i]=100+(i%80)*scale;y[i]=100+((i/80)%80)*scale;}run("zoom",x,y,scale);}
    }
}
