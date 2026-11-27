package org.example.map002probe;

import android.os.CancellationSignal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Compact, authorized-in-scope date index. A zero date means unknown and sorts last. */
public final class PhotoTimeline {
    public static final class Day {
        public final int first,count;
        public final LocalDate date;
        Day(int first,int count,LocalDate date){this.first=first;this.count=count;this.date=date;}
    }

    public final class Order {
        private final Integer[] rows;
        private final List<Day> days;
        Order(Integer[] rows,ZoneId zone,CancellationSignal cancel){
            this.rows=rows;
            List<Day> sections=new ArrayList<>();
            if(rows.length>0){
                int first=0;
                LocalDate prior=localDate(dateAt(0),zone);
                for(int i=1;i<rows.length;i++){
                    if((i&255)==0)cancel.throwIfCanceled();
                    LocalDate next=localDate(dateAt(i),zone);
                    if(java.util.Objects.equals(prior,next))continue;
                    sections.add(new Day(first,i-first,prior));
                    first=i;
                    prior=next;
                }
                sections.add(new Day(first,rows.length-first,prior));
            }
            days=Collections.unmodifiableList(sections);
        }
        public int size(){return rows.length;}
        public long idAt(int index){return ids[rows[index]];}
        public long dateAt(int index){return dates[rows[index]];}
        public long versionAt(int index){return versions[rows[index]];}
        public List<Day> days(){return days;}
    }

    private final long[] ids,dates,versions;
    private final Order newest,oldest;

    PhotoTimeline(long[] ids,long[] dates,long[] versions,CancellationSignal cancel){
        this.ids=ids;
        this.dates=dates;
        this.versions=versions;
        Integer[] sorted=new Integer[ids.length];
        for(int i=0;i<ids.length;i++)sorted[i]=i;
        // Object-array sort is stable, so ties retain ascending media IDs.
        java.util.Arrays.sort(sorted,Comparator.comparingLong((Integer row)->dates[row]).reversed());
        cancel.throwIfCanceled();
        Integer[] reversed=new Integer[sorted.length];
        int known=sorted.length;
        while(known>0&&dates[sorted[known-1]]==0)known--;
        int offset=0;
        for(int end=known;end>0;){
            if((offset&255)==0)cancel.throwIfCanceled();
            int start=end-1;
            long date=dates[sorted[start]];
            while(start>0&&dates[sorted[start-1]]==date)start--;
            System.arraycopy(sorted,start,reversed,offset,end-start);
            offset+=end-start;
            end=start;
        }
        System.arraycopy(sorted,known,reversed,known,sorted.length-known);
        ZoneId zone=ZoneId.systemDefault();
        newest=new Order(sorted,zone,cancel);
        oldest=new Order(reversed,zone,cancel);
    }

    public Order order(boolean oldestFirst){return oldestFirst?oldest:newest;}

    private static LocalDate localDate(long timestamp,ZoneId zone){
        return timestamp==0?null:Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate();
    }
}
