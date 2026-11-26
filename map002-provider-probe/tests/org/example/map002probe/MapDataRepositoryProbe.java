package org.example.map002probe;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.SystemClock;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic sources only; the device factory is separately checked to remain blocked. */
final class MapDataRepositoryProbe {
    private static void check(boolean value){if(!value)throw new AssertionError("repository fixture failed");}
    private static boolean await(CountDownLatch latch){try{return latch.await(8,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}}
    private static void waitFor(MapDataRepository repo,MapDataRepository.State expected){
        long end=SystemClock.elapsedRealtime()+10000;
        while(repo.state()!=expected && SystemClock.elapsedRealtime()<end)SystemClock.sleep(10);
        check(repo.state()==expected);
    }
    static void run(Context context) {
        ViewportFilterProbe.run(new double[0],new double[0],0);
        Set<String> volumes=new HashSet<>();volumes.add("external_primary");check(PrimaryPhotoSource.acceptsVolumes(volumes));
        volumes.add("external_other");check(!PrimaryPhotoSource.acceptsVolumes(volumes));volumes.clear();check(!PrimaryPhotoSource.acceptsVolumes(volumes));
        MapDataRepository device=MapDataRepository.forDevice(context);device.foreground();
        check(!device.load() && device.state()==MapDataRepository.State.IDENTITY_UNVERIFIED);device.close();
        SyntheticMapData deny=new SyntheticMapData(2,false,true,false);MapDataRepository blocked=MapDataRepository.forSyntheticFixture(deny);blocked.foreground();
        check(!blocked.load() && blocked.state()==MapDataRepository.State.IDENTITY_UNVERIFIED && deny.reads.get()==0);blocked.close();
        final SyntheticMapData fake=new SyntheticMapData(69637,true,true,true);final MapDataRepository repo=MapDataRepository.forSyntheticFixture(fake);repo.foreground();
        check(await(fake.entered) && repo.load() && fake.reads.get()==1);fake.release.countDown();waitFor(repo,MapDataRepository.State.READY);
        final CountDownLatch done=new CountDownLatch(1);final MapDataRepository.Window[] result=new MapDataRepository.Window[1];
        check(repo.query(-90,90,-180,180,new MapDataRepository.Callback<MapDataRepository.Window>(){public void complete(MapDataRepository.Window value){result[0]=value;done.countDown();}}));
        check(await(done) && result[0]!=null && result[0].size()==69637 && result[0].pointAt(0).id==1 && result[0].pointAt(69636).id==69637);
        final MapDataRepository.Window oldWindow=result[0];
        final CountDownLatch latest=new CountDownLatch(1);
        check(repo.query(-10,10,170,-170,new MapDataRepository.Callback<MapDataRepository.Window>(){public void complete(MapDataRepository.Window value){result[0]=value;latest.countDown();}}));
        check(await(latest) && oldWindow.size()==0 && oldWindow.pointAt(0)==null && result[0]!=null && result[0].size()>0);
        final CountDownLatch meta=new CountDownLatch(1);final boolean[] metaOk={false};
        check(repo.metadata(1,new MapDataRepository.Callback<MediaStoreLookup.Metadata>(){public void complete(MediaStoreLookup.Metadata value){metaOk[0]=value!=null && value.id==1 && value.dateTaken==123L;meta.countDown();}}));
        check(await(meta) && metaOk[0]);
        final CountDownLatch absent=new CountDownLatch(1);final boolean[] absentOk={false};
        check(repo.metadata(999999,new MapDataRepository.Callback<MediaStoreLookup.Metadata>(){public void complete(MediaStoreLookup.Metadata value){absentOk[0]=value==null;absent.countDown();}}));
        check(await(absent) && absentOk[0]);
        final CountDownLatch thumb=new CountDownLatch(1);final boolean[] thumbOk={false};
        check(repo.thumbnail(1,new MapDataRepository.Callback<Bitmap>(){public void complete(Bitmap value){thumbOk[0]=value!=null;if(value!=null)value.recycle();thumb.countDown();}}));
        check(await(thumb) && thumbOk[0]);
        repo.background();check(result[0].size()==0 && result[0].pointAt(0)==null && repo.state()==MapDataRepository.State.INACTIVE);
        check(!repo.query(-90,90,-180,180,new MapDataRepository.Callback<MapDataRepository.Window>(){public void complete(MapDataRepository.Window value){throw new AssertionError();}}));
        repo.foreground();waitFor(repo,MapDataRepository.State.READY);check(fake.reads.get()==2 && result[0].size()==0);
        repo.background();repo.close();
        SyntheticMapData changed=new SyntheticMapData(10,true,false,false);MapDataRepository scope=MapDataRepository.forSyntheticFixture(changed);scope.foreground();waitFor(scope,MapDataRepository.State.SCOPE_CHANGED);
        scope.background();scope.foreground();check(scope.state()==MapDataRepository.State.SCOPE_CHANGED && changed.reads.get()==1);
        check(scope.load());waitFor(scope,MapDataRepository.State.SCOPE_CHANGED);check(changed.reads.get()==2);scope.close();
        SyntheticMapData alien=new SyntheticMapData(10,true,true,false);alien.unexpected=true;MapDataRepository unknown=MapDataRepository.forSyntheticFixture(alien);unknown.foreground();waitFor(unknown,MapDataRepository.State.FAILED);unknown.close();
        SyntheticMapData over=new SyntheticMapData(100001,true,true,false);MapDataRepository capacity=MapDataRepository.forSyntheticFixture(over);capacity.foreground();waitFor(capacity,MapDataRepository.State.CAPACITY_EXCEEDED);capacity.close();
        SyntheticMapData rejected=new SyntheticMapData(2,true,true,false);rejected.denied=true;MapDataRepository permission=MapDataRepository.forSyntheticFixture(rejected);permission.foreground();waitFor(permission,MapDataRepository.State.ACCESS_DENIED);permission.close();
        SyntheticMapData otherVolume=new SyntheticMapData(2,true,true,false);otherVolume.invalidVolume=true;MapDataRepository volume=MapDataRepository.forSyntheticFixture(otherVolume);volume.foreground();waitFor(volume,MapDataRepository.State.VOLUME_UNSUPPORTED);volume.close();
        SyntheticMapData wrong=new SyntheticMapData(2,true,true,false);wrong.wrongMetadata=true;MapDataRepository mismatch=MapDataRepository.forSyntheticFixture(wrong);mismatch.foreground();waitFor(mismatch,MapDataRepository.State.READY);
        check(mismatch.metadata(1,new MapDataRepository.Callback<MediaStoreLookup.Metadata>(){public void complete(MediaStoreLookup.Metadata value){throw new AssertionError("wrong identity exposed");}}));
        waitFor(mismatch,MapDataRepository.State.FAILED);mismatch.close();
        final SyntheticMapData imageRace=new SyntheticMapData(2,true,true,false);imageRace.holdThumbnail=true;MapDataRepository image=MapDataRepository.forSyntheticFixture(imageRace);image.foreground();waitFor(image,MapDataRepository.State.READY);
        final AtomicInteger delivered=new AtomicInteger();check(image.thumbnail(1,new MapDataRepository.Callback<Bitmap>(){public void complete(Bitmap value){delivered.incrementAndGet();}}));
        check(await(imageRace.thumbnailEntered));image.background();imageRace.thumbnailRelease.countDown();
        long deadline=SystemClock.elapsedRealtime()+3000;while(!imageRace.lastThumbnail.isRecycled() && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(10);
        check(imageRace.lastThumbnail.isRecycled() && delivered.get()==0);image.close();
        SyntheticMapData paced=new SyntheticMapData(20001,true,true,false);paced.pauseMs=5000;MapDataRepository paused=MapDataRepository.forSyntheticFixture(paced);paused.foreground();
        check(await(paced.batchProcessed));paused.background();SystemClock.sleep(350);
        check(paused.state()==MapDataRepository.State.INACTIVE && paced.batches.get()==1);paused.close();
        SyntheticMapData slow=new SyntheticMapData(10,true,true,true);MapDataRepository cancelled=MapDataRepository.forSyntheticFixture(slow);cancelled.foreground();check(await(slow.entered));cancelled.background();slow.release.countDown();
        check(cancelled.state()==MapDataRepository.State.INACTIVE);cancelled.close();
        ReadOnlyQuery.log("repository_fixture_pass=true synthetic_points=69637 device_identity_gate_closed=true duplicate_load_coalesced=true scope_change_rejected=true unexpected_id_rejected=true capacity_rejected=true permission_denied=true volume_rejected=true metadata_identity_rejected=true stale_thumbnail_recycled=true obsolete_view_invalidated=true paced_cancel=true foreground_recheck=true failure_no_auto_retry=true lifecycle_invalidation=true synthetic_media=true");
    }
}
