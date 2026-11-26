package io.github.szzeng.mapalbum;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

/** Groups viewport points by screen pixels. Inputs and results are transient. */
final class ScreenClusters {
    static final class Cluster {
        final int x, y;
        final int[] members;
        Cluster(int x, int y, int[] members) { this.x = x; this.y = y; this.members = members; }
    }
    private static final class Builder {
        long sumX, sumY;
        int[] members = new int[4];
        int size;
        Builder() { }
        void add(int member, int x, int y) {
            sumX += x; sumY += y;
            if (size == members.length) members = Arrays.copyOf(members, members.length * 2);
            members[size++] = member;
        }
        Cluster finish() {
            return new Cluster((int)(sumX / size), (int)(sumY / size), Arrays.copyOf(members, size));
        }
    }
    static List<Cluster> group(int[] xs, int[] ys, int initialCellPixels, int maxMarkers) {
        if (xs == null || ys == null || xs.length != ys.length || initialCellPixels < 1 || maxMarkers < 1)
            throw new IllegalArgumentException();
        for (int i = 0; i < xs.length; i++) if (xs[i] < 0 || ys[i] < 0) throw new IllegalArgumentException("points must be in viewport");
        long cell = initialCellPixels;
        Map<Long, Builder> cells;
        while (true) {
            cells = new LinkedHashMap<>();
            for (int i = 0; i < xs.length; i++) {
                int column = (int) (xs[i] / cell), row = (int) (ys[i] / cell);
                long key = ((long) column << 32) | (row & 0xffffffffL);
                Builder builder = cells.get(key);
                if (builder == null) { builder = new Builder(); cells.put(key, builder); }
                builder.add(i, xs[i], ys[i]);
            }
            if (cells.size() <= maxMarkers) break;
            cell *= 2;
        }
        List<Cluster> result = new ArrayList<>(cells.size());
        for (Builder builder : cells.values()) result.add(builder.finish());
        return result;
    }
    private ScreenClusters() { }
}
