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
        System.out.println("ScreenClusters: passed");
    }
}
