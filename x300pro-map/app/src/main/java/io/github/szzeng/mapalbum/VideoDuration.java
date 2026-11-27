package io.github.szzeng.mapalbum;

import java.util.Locale;

final class VideoDuration {
    private VideoDuration(){}
    static String format(long milliseconds){
        if(milliseconds<0)return "--:--";
        long seconds=milliseconds/1000, hours=seconds/3600;
        return hours>0?String.format(Locale.ROOT,"%d:%02d:%02d",hours,(seconds/60)%60,seconds%60):String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);
    }
}
