package io.github.szzeng.mapalbum;

import java.util.List;

public final class ScreenClustersTest {
    private static void require(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        List<ScreenClusters.Cluster> same = ScreenClusters.group(new int[]{7, 7, 7}, new int[]{9, 9, 9}, 20, 100);
        require(same.size() == 1 && same.get(0).members.length == 3);
        for (int i = 0; i < 3; i++) require(same.get(0).members[i] == i);
        List<ScreenClusters.Cluster> close = ScreenClusters.group(new int[]{10, 20, 120}, new int[]{10, 20, 20}, 100, 100);
        require(close.size() == 2 && close.get(0).members.length == 2 && close.get(1).members[0] == 2);
        require(close.get(0).x == 15 && close.get(0).y == 15);
        List<ScreenClusters.Cluster> zoomed = ScreenClusters.group(new int[]{10, 110, 220}, new int[]{10, 110, 220}, 100, 100);
        require(zoomed.size() == 3);
        int[] xs = new int[1000], ys = new int[1000];
        for (int i = 0; i < xs.length; i++) { xs[i] = i % 40 * 20; ys[i] = i / 40 * 20; }
        List<ScreenClusters.Cluster> bounded = ScreenClusters.group(xs, ys, 1, 100);
        require(bounded.size() <= 100);
        boolean[] seen = new boolean[xs.length]; int count = 0;
        for (ScreenClusters.Cluster c : bounded) for (int member : c.members) { require(!seen[member]); seen[member] = true; count++; }
        require(count == xs.length);
        require(ScreenClusters.group(new int[0], new int[0], 20, 100).isEmpty());
        require(ScreenClusters.group(new int[]{0, Integer.MAX_VALUE}, new int[]{0, Integer.MAX_VALUE}, 1, 1).size() == 1);
        try { ScreenClusters.group(new int[]{-1}, new int[]{0}, 20, 100); throw new AssertionError(); } catch (IllegalArgumentException expected) { }
        List<ScreenClusters.Cluster> anchored=ScreenClusters.group(new int[]{110,120,210},new int[]{210,220,220},100,200,100,100);
        List<ScreenClusters.Cluster> panned=ScreenClusters.group(new int[]{147,157,247},new int[]{183,193,193},137,173,100,100);
        require(anchored.size()==panned.size());
        for(int i=0;i<anchored.size();i++){
            require(java.util.Arrays.equals(anchored.get(i).members,panned.get(i).members));
            require(panned.get(i).x==anchored.get(i).x+37 && panned.get(i).y==anchored.get(i).y-27);
        }
        require(ScreenClusters.group(new int[]{0,Integer.MAX_VALUE},new int[]{0,Integer.MAX_VALUE},Integer.MAX_VALUE,0,1,1).size()==1);
        int stableCell=ScreenClusters.stableCellPixels(1260,2800,72,100);
        require((((1260-1)/stableCell)+2)*(((2800-1)/stableCell)+2)<=100);
        require(stableCell==ScreenClusters.stableCellPixels(1260,2800,72,100));
        int totalWorld=69637;int[] worldX=new int[totalWorld],worldY=new int[totalWorld];
        for(int i=0;i<totalWorld;i++){worldX[i]=(i*17)%1260;worldY[i]=(i*31)%2800;}
        List<ScreenClusters.Cluster> world=ScreenClusters.group(worldX,worldY,-50,-80,stableCell,100);
        require(world.size()<=100);int assigned=0;for(ScreenClusters.Cluster cluster:world)assigned+=cluster.members.length;require(assigned==totalWorld);
        System.out.println("ScreenClusters: passed");
    }
}
