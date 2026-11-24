package org.example.map002probe;

/** Synthetic values only. This exercises guard behavior, not actual Android permissions or identity. */
final class GuardedGeoStoreProbe {
    private static void check(boolean value){if(!value)throw new AssertionError("guard check failed");}
    static void run() {
        GuardedGeoStore g=new GuardedGeoStore();g.enterForeground();
        GuardedGeoStore.Ticket old=g.beginRefresh();
        check(g.publish(old,new long[]{1,2},new double[]{1,2},new double[]{3,4},true));
        check(g.get(1,new GuardedGeoStore.Validator(){public boolean currentlyVisibleAndIdentityVerified(long id){return true;}})!=null);
        check(!g.publish(old,new long[]{3},new double[]{1},new double[]{2},true));
        g.invalidate();
        check(old.signal.isCanceled() && g.count()==0 && g.get(1,new GuardedGeoStore.Validator(){public boolean currentlyVisibleAndIdentityVerified(long id){return true;}})==null);
        g.enterForeground();GuardedGeoStore.Ticket fresh=g.beginRefresh();
        check(!g.publish(old,new long[]{1},new double[]{1},new double[]{2},true));
        check(!g.publish(fresh,new long[]{1},new double[]{1},new double[]{2},false));
        check(g.publish(fresh,new long[]{1,2},new double[]{1,2},new double[]{3,4},true));
        check(g.get(1,new GuardedGeoStore.Validator(){public boolean currentlyVisibleAndIdentityVerified(long id){return false;}})==null && g.count()==0);
        check(fresh.signal.isCanceled() && !g.publish(fresh,new long[]{1},new double[]{1},new double[]{2},true));
        GuardedGeoStore.Ticket retry=g.beginRefresh();
        check(g.publish(retry,new long[]{1},new double[]{1},new double[]{2},true));
        check(g.get(1,new GuardedGeoStore.Validator(){public boolean currentlyVisibleAndIdentityVerified(long id){throw new SecurityException();}})==null && g.count()==0 && retry.signal.isCanceled());
        GuardedGeoStore.Ticket newer=g.beginRefresh();
        check(!g.publish(retry,new long[]{1},new double[]{1},new double[]{2},true));
        check(!g.publish(newer,new long[]{2,1},new double[]{1,2},new double[]{3,4},true));
        g.invalidate();
        final GuardedGeoStore concurrent=new GuardedGeoStore();concurrent.enterForeground();
        final GuardedGeoStore.Ticket pending=concurrent.beginRefresh();
        final java.util.concurrent.CountDownLatch ready=new java.util.concurrent.CountDownLatch(1), release=new java.util.concurrent.CountDownLatch(1);
        final boolean[] accepted={true};
        Thread delayed=new Thread(new Runnable(){public void run(){
            ready.countDown();
            try {release.await();} catch(InterruptedException e){Thread.currentThread().interrupt();return;}
            accepted[0]=concurrent.publish(pending,new long[]{1},new double[]{1},new double[]{1},true);
        }});
        delayed.start();
        try {
            check(ready.await(2,java.util.concurrent.TimeUnit.SECONDS));
            concurrent.invalidate();release.countDown();delayed.join(2000);
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
        check(!delayed.isAlive() && pending.signal.isCanceled() && !accepted[0] && concurrent.count()==0);
        final GuardedGeoStore readRace=new GuardedGeoStore();readRace.enterForeground();
        GuardedGeoStore.Ticket readTicket=readRace.beginRefresh();
        check(readRace.publish(readTicket,new long[]{1},new double[]{1},new double[]{1},true));
        final java.util.concurrent.CountDownLatch checking=new java.util.concurrent.CountDownLatch(1), proceed=new java.util.concurrent.CountDownLatch(1);
        final double[][] result={new double[]{1}};
        Thread reader=new Thread(new Runnable(){public void run(){
            result[0]=readRace.get(1,new GuardedGeoStore.Validator(){public boolean currentlyVisibleAndIdentityVerified(long id){
                checking.countDown();try {proceed.await();} catch(InterruptedException e){Thread.currentThread().interrupt();return false;}return true;
            }});
        }});
        reader.start();
        try {check(checking.await(2,java.util.concurrent.TimeUnit.SECONDS));readRace.invalidate();proceed.countDown();reader.join(2000);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
        check(!reader.isAlive() && result[0]==null && readTicket.signal.isCanceled());
        ReadOnlyQuery.log("guard_fixture_pass=true delayed_publish_rejected=true blocked_validation_rejected=true synthetic_values=true");
    }
}
