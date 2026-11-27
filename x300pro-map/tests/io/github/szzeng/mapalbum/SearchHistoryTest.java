package io.github.szzeng.mapalbum;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SearchHistoryTest {
    private static void check(boolean condition){if(!condition)throw new AssertionError();}
    public static void main(String[] args){
        List<String> history=new ArrayList<>();
        for(int i=0;i<25;i++)history=SearchHistory.record(history,"地点"+i);
        check(history.size()==20);
        check(history.get(0).equals("地点24")&&history.get(19).equals("地点5"));
        history=SearchHistory.record(history," 地点10 ");
        check(history.size()==20&&history.get(0).equals("地点10"));
        check(history.stream().filter("地点10"::equals).count()==1);
        check(SearchHistory.record(Arrays.asList("杭州", "杭州", "", "上海"),"杭州").equals(Arrays.asList("杭州","上海")));
        System.out.println("SearchHistoryTest passed");
    }
}
