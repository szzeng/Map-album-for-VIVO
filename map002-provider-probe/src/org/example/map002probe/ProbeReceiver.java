package org.example.map002probe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shell-only diagnostic trigger, no repeating background work. */
public final class ProbeReceiver extends BroadcastReceiver {
    private static final AtomicBoolean running=new AtomicBoolean(false);
    private static final ExecutorService worker=Executors.newSingleThreadExecutor();
    @Override public void onReceive(final Context context, final Intent intent) {
        if(!running.compareAndSet(false,true)){ReadOnlyQuery.log("receiver_stopped=already_running");return;}
        final PendingResult pending=goAsync();
        final CancellationSignal signal=new CancellationSignal();
        final Handler handler=new Handler(Looper.getMainLooper());
        final Runnable timeout=new Runnable(){@Override public void run(){signal.cancel();}};
        handler.postDelayed(timeout,8000);
        worker.execute(new Runnable(){@Override public void run(){
            try {ReadOnlyQuery.runSample(context.getApplicationContext(),intent,signal);}
            finally {handler.removeCallbacks(timeout);running.set(false);pending.finish();}
        }});
    }
}
