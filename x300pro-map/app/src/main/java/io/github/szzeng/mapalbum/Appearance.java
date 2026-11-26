package io.github.szzeng.mapalbum;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

/** A user override takes precedence over the system; the default follows it. */
final class Appearance {
    private static final String PREFS="appearance", KEY="mode", SYSTEM="system", LIGHT="light", DARK="dark";
    static boolean followsSystem(Context context){return SYSTEM.equals(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY,SYSTEM));}
    static void setManual(Context context,boolean dark){context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY,dark?DARK:LIGHT).apply();}
    static void followSystem(Context context){context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove(KEY).apply();}
    static void applyOverride(Activity activity,Context base){
        String mode=base.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY,SYSTEM);
        if(!DARK.equals(mode)&&!LIGHT.equals(mode))return;
        Configuration override=new Configuration();
        override.uiMode=(base.getResources().getConfiguration().uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
            | (DARK.equals(mode)?Configuration.UI_MODE_NIGHT_YES:Configuration.UI_MODE_NIGHT_NO);
        activity.applyOverrideConfiguration(override);
    }
    private Appearance(){}
}
