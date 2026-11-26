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
        return group(xs,ys,0,0,initialCellPixels,maxMarkers);
    }
    private static final class Cell {
        final long column,row;
        Cell(long column,long row){this.column=column;this.row=row;}
        @Override public boolean equals(Object other){if(!(other instanceof Cell))return false;Cell c=(Cell)other;return column==c.column&&row==c.row;}
        @Override public int hashCode(){return 31*Long.hashCode(column)+Long.hashCode(row);}
    }
    /** Origin follows a fixed map location, keeping grid membership stable when the map pans. */
    static List<Cluster> group(int[] xs, int[] ys, int originX, int originY, int initialCellPixels, int maxMarkers) {
        if (xs == null || ys == null || xs.length != ys.length || initialCellPixels < 1 || maxMarkers < 1)
            throw new IllegalArgumentException();
        for (int i = 0; i < xs.length; i++) if (xs[i] < 0 || ys[i] < 0) throw new IllegalArgumentException("points must be in viewport");
        long cell = initialCellPixels;
        Map<Cell, Builder> cells;
        while (true) {
            cells = new LinkedHashMap<>();
            for (int i = 0; i < xs.length; i++) {
                long column = cell >= (1L<<33) ? 0 : Math.floorDiv((long)xs[i]-originX,cell);
                long row = cell >= (1L<<33) ? 0 : Math.floorDiv((long)ys[i]-originY,cell);
                Cell key = new Cell(column,row);
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
    /** Choose from viewport dimensions so small changes in visible photos do not resize every cluster. */
    static int stableCellPixels(int width,int height,int initial,int limit){
        if(width<=0||height<=0||initial<=0||limit<4)throw new IllegalArgumentException();
        long cell=initial;
        while((((width-1L)/cell)+2)*(((height-1L)/cell)+2)>limit){
            cell=Math.max(cell+1,(cell*5+3)/4);
            if(cell>=Integer.MAX_VALUE)return Integer.MAX_VALUE;
        }
        return (int)cell;
    }
    private ScreenClusters() { }
}
