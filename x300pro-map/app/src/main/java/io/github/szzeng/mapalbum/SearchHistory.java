package io.github.szzeng.mapalbum;

import java.util.ArrayList;
import java.util.List;

final class SearchHistory {
    static final int LIMIT=20;
    static List<String> record(List<String> existing,String keyword){
        List<String> updated=new ArrayList<>();
        String value=keyword==null?"":keyword.trim();
        if(!value.isEmpty())updated.add(value);
        for(String item:existing){
            if(item==null)continue;
            String normalized=item.trim();
            if(normalized.isEmpty()||updated.contains(normalized))continue;
            updated.add(normalized);
            if(updated.size()==LIMIT)break;
        }
        return updated;
    }
}
