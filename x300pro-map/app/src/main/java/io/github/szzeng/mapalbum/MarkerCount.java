package io.github.szzeng.mapalbum;

final class MarkerCount {
    static String format(int count){return count>9999?"9999+":Integer.toString(count);}
    private MarkerCount(){}
}
