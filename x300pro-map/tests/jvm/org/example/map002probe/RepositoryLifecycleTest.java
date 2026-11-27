package org.example.map002probe;
import android.os.CancellationSignal; import android.graphics.Bitmap; import java.util.concurrent.CountDownLatch; import java.util.concurrent.TimeUnit;
public final class RepositoryLifecycleTest {
 static void check(boolean b){if(!b)throw new AssertionError();}
 static void waitState(MapDataRepository r,MapDataRepository.State state)throws Exception{long end=System.currentTimeMillis()+4000;while(r.state()!=state&&System.currentTimeMillis()<end)Thread.sleep(5);check(r.state()==state);}
 static class Backend extends MapDataRepository.SyntheticBackend {
  volatile long[] ids={1,2}; volatile int reads; volatile boolean denied,hold,wrongMetadata,holdVerification,missingMetadata,holdMetadata; volatile CountDownLatch verificationEntered=new CountDownLatch(1), verificationRelease=new CountDownLatch(1); final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
  final CountDownLatch metadataEntered=new CountDownLatch(1),metadataRelease=new CountDownLatch(1);
  boolean fixtureEnabled(){return true;} public int batchPauseMillis(){return 0;}
  public long[] visibleIds(int n,CancellationSignal c){if(denied)throw new SecurityException();return ids.clone();}
  public void appendBatch(long[] x,int from,int to,PrimitiveLocationSet out,CancellationSignal c){reads++;entered.countDown();if(hold)try{release.await(4,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}for(int i=from;i<to;i++){c.throwIfCanceled();out.add(x[i],23,113);}}
  public boolean scopeUnchanged(long[] x,CancellationSignal c){if(holdVerification){verificationEntered.countDown();try{verificationRelease.await(4,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}}c.throwIfCanceled();if(denied)throw new SecurityException();return java.util.Arrays.equals(x,ids);}
  public MediaStoreLookup.Metadata metadata(long id,CancellationSignal c){
   if(holdMetadata){
    metadataEntered.countDown();
    try{check(metadataRelease.await(4,TimeUnit.SECONDS));}
    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
    throw new MapDataRepository.MediaScopeChangedException();
   }
   if(missingMetadata)throw new MapDataRepository.MediaScopeChangedException();
   return new MediaStoreLookup.Metadata(wrongMetadata?id+1:id);
  }
  public Bitmap thumbnail(long id,CancellationSignal c){return null;}
 }
 public static void main(String[] args)throws Exception {
  Backend backend=new Backend();MapDataRepository r=MapDataRepository.forSyntheticFixture(backend);r.foreground();waitState(r,MapDataRepository.State.READY);check(backend.reads==1);long originalScope=r.scopeToken();check(originalScope>=0);
  CountDownLatch first=new CountDownLatch(1);r.query(22,24,112,114,w->{check(w.size()==2);MediaStoreLookup.Metadata[] metadata=r.metadataBatchNow(new long[]{w.pointAt(0).id,w.pointAt(1).id});check(metadata!=null&&metadata.length==2&&metadata[0].id==1&&metadata[1].id==2);first.countDown();});check(first.await(4,TimeUnit.SECONDS));
  CountDownLatch selected=new CountDownLatch(1);final boolean[] selectedOk={false};check(r.metadataForViewport(new long[]{1,2},values->{selectedOk[0]=values!=null&&values.length==2&&values[0].id==1&&values[1].id==2;selected.countDown();}));check(selected.await(4,TimeUnit.SECONDS)&&selectedOk[0]);
  r.suspend();check(r.scopeToken()==-1);CountDownLatch resumed=new CountDownLatch(1);r.resumeChecked(v->resumed.countDown());check(resumed.await(4,TimeUnit.SECONDS));waitState(r,MapDataRepository.State.READY);check(backend.reads==1&&r.scopeToken()==originalScope);
  r.suspend();backend.holdVerification=true;CountDownLatch stale=new CountDownLatch(1);r.resumeChecked(v->stale.countDown());check(backend.verificationEntered.await(4,TimeUnit.SECONDS));r.suspend();backend.holdVerification=false;CountDownLatch again=new CountDownLatch(1);r.resumeChecked(v->again.countDown());backend.verificationRelease.countDown();check(again.await(4,TimeUnit.SECONDS));check(stale.getCount()==1&&backend.reads==1&&r.scopeToken()==originalScope);
  for(int i=0;i<3;i++){r.suspend();CountDownLatch cycle=new CountDownLatch(1);r.resumeChecked(v->cycle.countDown());check(cycle.await(4,TimeUnit.SECONDS));check(r.scopeToken()==originalScope&&backend.reads==1);}
  r.suspend();backend.ids=new long[]{1};CountDownLatch changed=new CountDownLatch(1);r.resumeChecked(v->changed.countDown());check(changed.await(4,TimeUnit.SECONDS));waitState(r,MapDataRepository.State.READY);check(backend.reads==2&&r.visibleCount()==1&&r.scopeToken()!=originalScope);
  r.suspend();backend.ids=new long[]{2};CountDownLatch preview=new CountDownLatch(1);final boolean[] accepted={true};r.verifyExistingScope(v->{accepted[0]=v;preview.countDown();});check(preview.await(4,TimeUnit.SECONDS));check(!accepted[0]&&r.state()==MapDataRepository.State.SCOPE_CHANGED);r.close();
  Backend revoke=new Backend();MapDataRepository revoked=MapDataRepository.forSyntheticFixture(revoke);revoked.foreground();waitState(revoked,MapDataRepository.State.READY);revoked.suspend();revoke.denied=true;CountDownLatch revokedCheck=new CountDownLatch(1);revoked.resumeChecked(v->revokedCheck.countDown());check(revokedCheck.await(4,TimeUnit.SECONDS));waitState(revoked,MapDataRepository.State.ACCESS_DENIED);check(revoked.scopeToken()<0);revoked.close();
  android.content.Context release=new android.content.Context();MapDataRepository blocked=MapDataRepository.forConfirmedExperiment(release,true);blocked.resumeChecked(v->{});check(blocked.state()==MapDataRepository.State.IDENTITY_UNVERIFIED);blocked.close();
  android.content.Context debug=new android.content.Context(){@Override public android.content.pm.ApplicationInfo getApplicationInfo(){android.content.pm.ApplicationInfo info=new android.content.pm.ApplicationInfo();info.flags=android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE;return info;}};
  check(MapDataRepository.isSupportedDevice(debug));
  android.os.Build.DEVICE="OTHER";check(!MapDataRepository.isSupportedDevice(debug));MapDataRepository wrong=MapDataRepository.forConfirmedExperiment(debug,true);wrong.resumeChecked(v->{});check(wrong.state()==MapDataRepository.State.IDENTITY_UNVERIFIED);wrong.close();android.os.Build.DEVICE="PD2502";
  MapDataRepository unconfirmed=MapDataRepository.forConfirmedExperiment(debug,false);unconfirmed.resumeChecked(v->{});check(unconfirmed.state()==MapDataRepository.State.IDENTITY_UNVERIFIED);unconfirmed.close();
  check(!MapDataRepository.isSupportedPersonalRelease(release));
  io.github.szzeng.mapalbum.BuildConfig.PERSONAL_DEVICE_RELEASE=true;
  check(MapDataRepository.isSupportedPersonalRelease(release));check(!MapDataRepository.isSupportedPersonalRelease(debug));
  MapDataRepository personal=MapDataRepository.forConfirmedPersonalRelease(release,true);personal.foreground();waitState(personal,MapDataRepository.State.READY);personal.close();
  MapDataRepository personalUnconfirmed=MapDataRepository.forConfirmedPersonalRelease(release,false);personalUnconfirmed.resumeChecked(v->{});check(personalUnconfirmed.state()==MapDataRepository.State.IDENTITY_UNVERIFIED);personalUnconfirmed.close();
  android.os.Build.DEVICE="OTHER";check(!MapDataRepository.isSupportedPersonalRelease(release));MapDataRepository personalWrong=MapDataRepository.forConfirmedPersonalRelease(release,true);personalWrong.resumeChecked(v->{});check(personalWrong.state()==MapDataRepository.State.IDENTITY_UNVERIFIED);personalWrong.close();android.os.Build.DEVICE="PD2502";
  io.github.szzeng.mapalbum.BuildConfig.PERSONAL_DEVICE_RELEASE=false;MapDataRepository personalFlagOff=MapDataRepository.forConfirmedPersonalRelease(release,true);personalFlagOff.resumeChecked(v->{});check(personalFlagOff.state()==MapDataRepository.State.IDENTITY_UNVERIFIED);personalFlagOff.close();check(MapDataRepository.isSupportedDevice(debug));
  Backend large=new Backend();large.ids=new long[100001];MapDataRepository limit=MapDataRepository.forSyntheticFixture(large);limit.foreground();waitState(limit,MapDataRepository.State.CAPACITY_EXCEEDED);check(large.reads==0);limit.close();
  Backend deny=new Backend();deny.denied=true;MapDataRepository denied=MapDataRepository.forSyntheticFixture(deny);denied.foreground();waitState(denied,MapDataRepository.State.ACCESS_DENIED);check(deny.reads==0);denied.close();
  Backend race=new Backend();race.hold=true;MapDataRepository changedDuringLoad=MapDataRepository.forSyntheticFixture(race);changedDuringLoad.foreground();check(race.entered.await(4,TimeUnit.SECONDS));race.ids=new long[]{1};race.release.countDown();waitState(changedDuringLoad,MapDataRepository.State.SCOPE_CHANGED);check(changedDuringLoad.validCount()==0);changedDuringLoad.close();
  Backend mismatch=new Backend();mismatch.wrongMetadata=true;MapDataRepository badMetadata=MapDataRepository.forSyntheticFixture(mismatch);badMetadata.foreground();waitState(badMetadata,MapDataRepository.State.READY);CountDownLatch invalidBatch=new CountDownLatch(1);badMetadata.query(22,24,112,114,w->{check(badMetadata.metadataBatchNow(new long[]{1,2})==null);invalidBatch.countDown();});check(invalidBatch.await(4,TimeUnit.SECONDS));check(badMetadata.state()==MapDataRepository.State.FAILED);badMetadata.close();
  checkSelectionCancellationAndRecovery();
  checkTimelineCancellationAndOrdering();
  int[] across=ViewportFilter.queryIndexes(new double[]{0,0,0},new double[]{179.5,-179.5,0},3,-1,1,179,-179,new CancellationSignal());
  check(java.util.Arrays.equals(across,new int[]{0,1}));
  System.out.println("RepositoryLifecycleTest: passed");
 }
 private static void checkTimelineCancellationAndOrdering()throws Exception {
  CountDownLatch timelineEntered=new CountDownLatch(1), observedCancel=new CountDownLatch(1);
  java.util.concurrent.atomic.AtomicBoolean block=new java.util.concurrent.atomic.AtomicBoolean(true);
  Backend source=new Backend(){
   @Override public void timelineBatch(long[] ids,int from,int to,long[] dates,long[] versions,CancellationSignal signal){
    if(block.getAndSet(false)){
     timelineEntered.countDown();
     long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
     while(!signal.isCanceled()&&System.nanoTime()<end)java.util.concurrent.locks.LockSupport.parkNanos(100_000);
     check(signal.isCanceled());
     observedCancel.countDown();
     signal.throwIfCanceled();
    }
    for(int i=from;i<to;i++){
     dates[i]=i%5==0?0:1_700_000_000_000L+(i%3)*86_400_000L;
     versions[i]=i+7;
    }
   }
  };
  source.ids=new long[10017];
  for(int i=0;i<source.ids.length;i++)source.ids[i]=i+1;
  try(MapDataRepository repository=MapDataRepository.forSyntheticFixture(source)){
   repository.foreground();
   waitState(repository,MapDataRepository.State.READY);
   long scope=repository.scopeToken();
   CountDownLatch staleResult=new CountDownLatch(1);
   MapDataRepository.PhotoRequest stale=repository.loadPhotoTimeline(source.ids,ignored->staleResult.countDown());
   check(stale!=null&&timelineEntered.await(4,TimeUnit.SECONDS));
   stale.cancel();
   check(observedCancel.await(4,TimeUnit.SECONDS));

   CountDownLatch finished=new CountDownLatch(1);
   PhotoTimeline[] result=new PhotoTimeline[1];
   check(repository.loadPhotoTimeline(source.ids,timeline->{result[0]=timeline;finished.countDown();})!=null);
   check(finished.await(4,TimeUnit.SECONDS)&&result[0]!=null);
   check(staleResult.getCount()==1&&repository.scopeToken()==scope);
   for(boolean oldest:new boolean[]{false,true}){
    PhotoTimeline.Order order=result[0].order(oldest);
    boolean[] seen=new boolean[source.ids.length];
    check(order.size()==seen.length);
    long precedingDate=0,precedingId=0;
    for(int i=0;i<order.size();i++){
     long id=order.idAt(i),date=order.dateAt(i);
     check(!seen[(int)id-1]&&order.versionAt(i)==id+6);
     seen[(int)id-1]=true;
     if(i>0){
      if(date==precedingDate)check(id>precedingId);
      else check(precedingDate!=0&&(date==0||(oldest?date>precedingDate:date<precedingDate)));
     }
     precedingDate=date;
     precedingId=id;
    }
    check(order.days().size()==4&&order.days().get(3).date==null);
   }
  }
 }
 private static void checkSelectionCancellationAndRecovery()throws Exception {
  Backend source=new Backend();
  try(MapDataRepository repository=MapDataRepository.forSyntheticFixture(source)){
   repository.foreground();
   waitState(repository,MapDataRepository.State.READY);
   long scope=repository.scopeToken();
   CountDownLatch firstViewport=new CountDownLatch(1);
   check(repository.query(22,24,112,114,w->firstViewport.countDown()));
   check(firstViewport.await(4,TimeUnit.SECONDS));

   source.holdMetadata=true;
   CountDownLatch canceled=new CountDownLatch(1), nextViewport=new CountDownLatch(1);
   check(repository.metadataForViewport(new long[]{2},items->{check(items==null);canceled.countDown();}));
   check(source.metadataEntered.await(4,TimeUnit.SECONDS));
   check(repository.query(21,25,111,115,w->nextViewport.countDown()));
   source.metadataRelease.countDown();
   check(canceled.await(4,TimeUnit.SECONDS)&&nextViewport.await(4,TimeUnit.SECONDS));
   check(repository.scopeToken()==scope);

   source.holdMetadata=false;
   source.missingMetadata=true;
   CountDownLatch missing=new CountDownLatch(1);
   check(repository.metadataForViewport(new long[]{2},items->{check(items==null);missing.countDown();}));
   check(missing.await(4,TimeUnit.SECONDS));
   check(repository.state()==MapDataRepository.State.SCOPE_CHANGED&&repository.scopeToken()<0);
   source.missingMetadata=false;
   source.ids=new long[]{1};
   repository.resumeChecked(v->{});
   waitState(repository,MapDataRepository.State.READY);
   check(repository.scopeToken()!=scope&&repository.visibleCount()==1);
  }
 }
}
