package io.github.szzeng.mapalbum;

public final class MarkerCountTest {
    private static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args){
        check(MarkerCount.format(999).equals("999"));
        check(MarkerCount.format(1000).equals("1000"));
        check(MarkerCount.format(9999).equals("9999"));
        check(MarkerCount.format(10000).equals("9999+"));
        System.out.println("MarkerCountTest: passed");
    }
}
