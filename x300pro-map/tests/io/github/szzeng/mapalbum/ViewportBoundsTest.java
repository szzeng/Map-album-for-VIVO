package io.github.szzeng.mapalbum;

public final class ViewportBoundsTest {
    private static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args){
        ViewportBounds cached=new ViewportBounds(22,24,112,115);
        check(cached.covers(22,24,112,115));
        check(cached.covers(22.1,23.9,112.2,114.9));
        check(!cached.covers(21.99,23,113,114));
        check(!cached.covers(22,24.01,113,114));
        check(!cached.covers(22,23,111.99,114));
        check(!cached.covers(22,23,113,115.01));
        check(!cached.covers(23,22,113,114));
        ViewportBounds europe=GeographicViewport.fromCorners(new double[]{48,51,48,51},new double[]{-1,3,-1,3},1,.1);
        check(europe.covers(48,51,-1,3));check(europe.west<0 && europe.east>0);
        ViewportBounds americas=GeographicViewport.fromCorners(new double[]{-35,45},new double[]{-123,-65},-90,.1);
        check(americas.covers(-35,45,-123,-65));
        ViewportBounds dateline=GeographicViewport.fromCorners(new double[]{-10,10},new double[]{179,-179},180,.1);
        check(dateline.west>dateline.east && dateline.covers(-10,10,179.5,-179.5));
        check(dateline.covers(-10,10,179.5,180));check(dateline.covers(-10,10,-180,-179.5));
        check(!dateline.covers(-10,10,-170,-160));
        ViewportBounds wide=GeographicViewport.fromCorners(new double[]{-10,10},new double[]{-130,130},0,.1);
        check(wide.west==-180 && wide.east==180);
        ViewportBounds edge=GeographicViewport.fromCorners(new double[]{-89.99,89.99},new double[]{-179.99,179.99},0,.1);
        check(edge.south==-90 && edge.north==90 && edge.west==-180 && edge.east==180);
        try {GeographicViewport.fromCorners(new double[]{Double.NaN},new double[]{0},0,.1);throw new AssertionError();}catch(IllegalArgumentException expected){}
        System.out.println("ViewportBoundsTest: passed");
    }
}
