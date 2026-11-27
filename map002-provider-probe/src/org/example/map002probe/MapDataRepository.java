package org.example.map002probe;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.CancellationSignal;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Read-only, process-local index. Device access requires the explicit, debug-only experiment gate. */
public final class MapDataRepository implements AutoCloseable {
    public enum State { INACTIVE, IDLE, LOADING, READY, IDENTITY_UNVERIFIED, VOLUME_UNSUPPORTED, CAPACITY_EXCEEDED, ACCESS_DENIED, SCOPE_CHANGED, FAILED, CLOSED }
    public interface Callback<T> { void complete(T value); }
    interface Backend {
        int batchPauseMillis();
        long[] visibleIds(int capacity,CancellationSignal signal);
        void appendBatch(long[] ids,int from,int to,PrimitiveLocationSet output,CancellationSignal signal);
        boolean scopeUnchanged(long[] ids,CancellationSignal signal);
        MediaStoreLookup.Metadata metadata(long id,CancellationSignal signal);
        Bitmap thumbnail(long id,CancellationSignal signal);
    }
    /** Test seam for synthetic data only; not a device identity authority. */
    abstract static class SyntheticBackend implements Backend { abstract boolean fixtureEnabled(); }
    public static final class Point {
        public final long id;public final double latitude,longitude;
        Point(long id,double latitude,double longitude){this.id=id;this.latitude=latitude;this.longitude=longitude;}
    }
    /** Contains only indexes; after invalidation it cannot retrieve location data. */
    public static final class Window {
        private final MapDataRepository owner;private final PrimitiveLocationSet data;private final long epoch;
        private final CancellationSignal signal,querySignal;private final long queryEpoch;private final int[] indexes;private final int count;
        Window(MapDataRepository owner,PrimitiveLocationSet data,long epoch,CancellationSignal signal,long queryEpoch,CancellationSignal querySignal,int[] indexes,int count){
            this.owner=owner;this.data=data;this.epoch=epoch;this.signal=signal;this.queryEpoch=queryEpoch;this.querySignal=querySignal;this.indexes=indexes;this.count=count;
        }
        public int size(){synchronized(owner){return owner.currentQuery(epoch,signal,queryEpoch,querySignal)&&owner.cache==data?count:0;}}
        public Point pointAt(int index){
            synchronized(owner){
                if(!owner.currentQuery(epoch,signal,queryEpoch,querySignal)||owner.cache!=data||index<0||index>=count)return null;
                synchronized(data){int at=indexes[index];return new Point(data.idAt(at),data.latitudeAt(at),data.longitudeAt(at));}
            }
        }
    }
    private static final int CAPACITY=100000, BATCH=20000;
    private final Backend backend;
    private final SyntheticBackend synthetic;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(1));
    private State state=State.INACTIVE;
    private State lastFailure;
    private long epoch;
    private CancellationSignal current;
    private PrimitiveLocationSet cache;
    private boolean foreground;
    private final boolean deviceEnabled;
    private long[] scope;
    private long loadMillis, filterMillis;
    private int visibleCount, validCount;
    private long loadStarted;
    private Runnable pendingQuery;
    private CancellationSignal querySignal;
    private long queryEpoch;
    private MapDataRepository(Backend backend,SyntheticBackend synthetic,boolean enabled){if(backend==null)throw new IllegalArgumentException();this.backend=backend;this.synthetic=synthetic;this.deviceEnabled=enabled;}
    static MapDataRepository forDevice(Context context){return new MapDataRepository(new DeviceBackend(context),null,false);}
    public static MapDataRepository forConfirmedExperiment(Context context, boolean confirmed) {
        boolean debug=(context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0;
        boolean device="vivo".equalsIgnoreCase(android.os.Build.MANUFACTURER)
            && "V2502A".equalsIgnoreCase(android.os.Build.MODEL)
            && "PD2502".equalsIgnoreCase(android.os.Build.DEVICE);
        return new MapDataRepository(new DeviceBackend(context),null,debug && device && confirmed);
    }
    public static boolean isSupportedDevice(Context context) {
        return (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0
            && "vivo".equalsIgnoreCase(android.os.Build.MANUFACTURER)
            && "V2502A".equalsIgnoreCase(android.os.Build.MODEL)
            && "PD2502".equalsIgnoreCase(android.os.Build.DEVICE);
    }
    public synchronized String diagnostics() {
        return "身份："+(deviceEnabled?"V2502A / PD2502 实验配置":"未验证")+" · 状态："+state
            +"\n可见照片 "+visibleCount+" · 有效 GPS "+validCount
            +(backend instanceof DeviceBackend?((DeviceBackend)backend).diagnostics():"")+"\n加载耗时 "+loadMillis+" ms · 区域筛选 "+filterMillis+" ms";
    }
    /** Token is usable only with this repository and while the current scope is READY. */
    public synchronized long scopeToken(){return foreground && state==State.READY && cache!=null?epoch:-1;}
    public synchronized int visibleCount(){return visibleCount;}
    public synchronized int validCount(){return validCount;}
    /** Called from a query callback, on the repository worker. */
    public MediaStoreLookup.Metadata metadataNow(long id) {
        final PrimitiveLocationSet data; final CancellationSignal signal; final long ticket;
        synchronized(this){if(state!=State.READY||cache==null)return null;data=cache;signal=current;ticket=epoch;}
        synchronized(data){if(!data.contains(id))return null;}
        try {
            MediaStoreLookup.Metadata value=backend.metadata(id,signal);
            if(value==null || value.id!=id){failIfCurrent(ticket,signal);return null;}
            return current(ticket,signal)?value:null;
        } catch(android.os.OperationCanceledException canceled){return null;}
          catch(RuntimeException failure){failIfCurrent(ticket,signal);return null;}
    }
    /** Called only inside a viewport callback; canceled when its viewport becomes stale. */
    public MediaStoreLookup.Metadata[] metadataBatchNow(long[] ids) {
        final PrimitiveLocationSet data;final CancellationSignal signal,query;final long ticket,queryTicket;
        synchronized(this){if(state!=State.READY||cache==null||querySignal==null)return null;data=cache;signal=current;query=querySignal;ticket=epoch;queryTicket=queryEpoch;}
        try {
            synchronized(data){for(long id:ids){query.throwIfCanceled();if(!data.contains(id))throw new IllegalStateException("outside indexed scope");}}
            MediaStoreLookup.Metadata[] result;
            if(backend instanceof DeviceBackend)result=((DeviceBackend)backend).media.lookupBatch(ids,query);
            else {result=new MediaStoreLookup.Metadata[ids.length];for(int i=0;i<ids.length;i++){query.throwIfCanceled();result[i]=backend.metadata(ids[i],query);}}
            for(int i=0;i<ids.length;i++)if(result[i]==null||result[i].id!=ids[i])throw new IllegalStateException("metadata identity mismatch");
            return currentQuery(ticket,signal,queryTicket,query)?result:null;
        } catch(android.os.OperationCanceledException canceled){return null;}
          catch(RuntimeException failure){if(currentQuery(ticket,signal,queryTicket,query))failIfCurrent(ticket,signal);return null;}
    }
    /** Load metadata only for an explicitly selected overview cluster. */
    public boolean metadataForViewport(final long[] ids,final Callback<MediaStoreLookup.Metadata[]> callback){
        if(ids==null||callback==null||ids.length==0||ids.length>10000)throw new IllegalArgumentException();
        synchronized(this){if(!foreground||state!=State.READY||cache==null||querySignal==null)return false;}
        try{worker.execute(()->{
            MediaStoreLookup.Metadata[] result=new MediaStoreLookup.Metadata[ids.length];
            for(int from=0;from<ids.length;from+=512){
                int end=Math.min(ids.length,from+512);MediaStoreLookup.Metadata[] batch=metadataBatchNow(Arrays.copyOfRange(ids,from,end));
                if(batch==null){callback.complete(null);return;}System.arraycopy(batch,0,result,from,batch.length);
            }
            callback.complete(result);
        });return true;}catch(RejectedExecutionException ignored){return false;}
    }
    /** Suspend display until current visible IDs have been checked. GPS is reused if unchanged. */
    public void resumeChecked(final Callback<State> callback) {
        final long[] previousScope; final CancellationSignal signal; final long ticket;
        synchronized(this) {
            if (state==State.CLOSED) { callback.complete(state); return; }
            if(foreground && state==State.LOADING){callback.complete(state);return;}
            foreground=true;
            if (!deviceEnabled && (synthetic==null || !synthetic.fixtureEnabled())) { state=State.IDENTITY_UNVERIFIED; callback.complete(state); return; }
            if (state!=State.READY || cache==null || scope==null) {
                cancelAndRelease(State.IDLE);
                load();
                callback.complete(state());
                return;
            }
            previousScope=scope; signal=current; ticket=epoch; state=State.LOADING;
            if(querySignal!=null)querySignal.cancel(); querySignal=null;queryEpoch++;
            worker.getQueue().clear(); pendingQuery=null;
            try { worker.execute(()->{
                boolean same=false;
                try { same=backend.scopeUnchanged(previousScope,signal); }
                catch (RuntimeException ignored) { }
                synchronized(MapDataRepository.this) {
                    if(!current(ticket,signal)) return;
                    if(same) { querySignal=new CancellationSignal();queryEpoch++;state=State.READY; callback.complete(state); return; }
                    cancelAndRelease(State.IDLE); load(); callback.complete(state());
                }
            }); } catch(RejectedExecutionException e) { cancelAndRelease(State.FAILED); callback.complete(state()); }
        }
    }
    /** Preview must not show its snapshot until the exact scope is checked again. */
    public void verifyExistingScope(final Callback<Boolean> callback) {
        final long[] snapshot; final CancellationSignal signal; final long ticket;
        synchronized(this) {
            if((!deviceEnabled && (synthetic==null || !synthetic.fixtureEnabled()))||state!=State.READY||cache==null||scope==null){callback.complete(false);return;}
            foreground=true; snapshot=scope;signal=current;ticket=epoch;state=State.LOADING;
            try {worker.execute(()->{
                boolean same=false;try{same=backend.scopeUnchanged(snapshot,signal);}catch(RuntimeException ignored){}
                synchronized(MapDataRepository.this){if(!current(ticket,signal))return;if(same){querySignal=new CancellationSignal();queryEpoch++;state=State.READY;callback.complete(true);return;}
                    cancelAndRelease(State.SCOPE_CHANGED);callback.complete(false);}
            });} catch(RejectedExecutionException e){cancelAndRelease(State.FAILED);callback.complete(false);}
        }
    }
    public void suspend() {
        CancellationSignal old=null;
        synchronized(this){
            if(state==State.CLOSED)return;
            foreground=false;
            if(querySignal!=null)querySignal.cancel();querySignal=null;queryEpoch++;
            worker.getQueue().clear();pendingQuery=null;
            if(cache!=null && scope!=null && (state==State.READY || state==State.LOADING)){
                // Invalidate any running scope verification without invalidating the snapshot token.
                old=current;current=new CancellationSignal();state=State.READY;
            }else{cancelAndRelease(State.INACTIVE);}
        }
        if(old!=null)old.cancel();
    }
    public void stopAndClear(){background();}
    public void start(){foreground();}
    static MapDataRepository forSyntheticFixture(SyntheticBackend source){return new MapDataRepository(source,source,false);}
    public synchronized State state(){return state;}
    /** Returning to foreground invalidates old data and begins a fresh scope check. */
    void foreground(){
        State blocked;
        synchronized(this){if(state==State.CLOSED)throw new IllegalStateException();foreground=true;blocked=lastFailure;}
        cancelAndRelease(blocked==null?State.IDLE:blocked);
        if(blocked==null)load();
    }
    private void cancelAndRelease(State next) {
        CancellationSignal previous,previousQuery;PrimitiveLocationSet old;
        synchronized(this){
            if(state==State.CLOSED)return;
            epoch++;queryEpoch++;previous=current;current=null;previousQuery=querySignal;querySignal=null;old=cache;cache=null;scope=null;
            state=(next==State.IDLE&&!foreground)?State.INACTIVE:next;worker.getQueue().clear();pendingQuery=null;
        }
        if(previous!=null)previous.cancel();
        if(previousQuery!=null)previousQuery.cancel();
        if(old!=null)synchronized(old){old.close();}
    }
    void background(){synchronized(this){foreground=false;}cancelAndRelease(State.INACTIVE);}
    void clear(){State next;synchronized(this){next=foreground?State.IDLE:State.INACTIVE;}cancelAndRelease(next);}
    void cancel(){clear();}
    @Override public void close(){synchronized(this){foreground=false;}cancelAndRelease(State.CLOSED);worker.shutdownNow();}
    private synchronized boolean current(long expected,CancellationSignal signal){return foreground && state!=State.CLOSED && epoch==expected && current==signal && !signal.isCanceled();}
    private void failIfCurrent(long expected,CancellationSignal signal){
        CancellationSignal old,oldQuery;PrimitiveLocationSet previous;
        synchronized(this){
            if(!current(expected,signal))return;
            epoch++;queryEpoch++;old=current;oldQuery=querySignal;current=null;querySignal=null;
            previous=cache;cache=null;scope=null;state=State.FAILED;lastFailure=State.FAILED;worker.getQueue().clear();pendingQuery=null;
        }
        if(old!=null)old.cancel();if(oldQuery!=null)oldQuery.cancel();
        if(previous!=null)synchronized(previous){previous.close();}
    }
    private synchronized boolean currentQuery(long expected,CancellationSignal signal,long expectedQuery,CancellationSignal query){
        return current(expected,signal) && queryEpoch==expectedQuery && querySignal==query && !query.isCanceled();
    }
    /** Duplicate loads are coalesced. False means rejected or identity not verified. */
    boolean load() {
        final long ticket;final CancellationSignal signal,previous,previousQuery;final PrimitiveLocationSet old;
        synchronized(this){
            if(!foreground || state==State.CLOSED)return false;
            if(state==State.LOADING)return true;
            lastFailure=null;
            if(!deviceEnabled && (synthetic==null || !synthetic.fixtureEnabled())){
                // A fixture-only gate never enables the unverified vendor device backend.
                epoch++;queryEpoch++;previous=current;previousQuery=querySignal;current=null;querySignal=null;
                old=cache;cache=null;state=State.IDENTITY_UNVERIFIED;worker.getQueue().clear();pendingQuery=null;
                ticket=-1;signal=null;
            } else {
                epoch++;queryEpoch++;previous=current;previousQuery=querySignal;querySignal=null;
                old=cache;cache=null;worker.getQueue().clear();pendingQuery=null;
                ticket=epoch;signal=new CancellationSignal();current=signal;state=State.LOADING;loadStarted=android.os.SystemClock.elapsedRealtime();
            }
        }
        if(previous!=null)previous.cancel();if(previousQuery!=null)previousQuery.cancel();
        if(old!=null)synchronized(old){old.close();}
        if(signal==null)return false;
        synchronized(this){
            if(!current(ticket,signal))return false;
            try {worker.execute(new Runnable(){public void run(){loadWork(ticket,signal);}});return true;}
            catch(RejectedExecutionException ex){if(epoch==ticket){state=State.FAILED;lastFailure=State.FAILED;}return false;}
        }
    }
    private void loadWork(long ticket,CancellationSignal signal) {
        PrimitiveLocationSet candidate=null;
        try {
            if(backend instanceof DeviceBackend)((DeviceBackend)backend).source.resetCounts();
            long[] ids=backend.visibleIds(CAPACITY,signal);signal.throwIfCanceled();
            if(ids==null)throw new IllegalStateException("invalid scope");
            if(ids.length>CAPACITY)throw new PrimaryPhotoSource.CapacityException();
            for(int i=0;i<ids.length;i++)if(ids[i]<0 || (i>0 && ids[i]<=ids[i-1]))throw new IllegalStateException("invalid identities");
            if(deviceEnabled && !backend.scopeUnchanged(ids,signal)){synchronized(this){if(current(ticket,signal)){state=State.SCOPE_CHANGED;lastFailure=State.SCOPE_CHANGED;}}return;}
            candidate=new PrimitiveLocationSet(ids.length);
            for(int from=0;from<ids.length;from+=BATCH){
                signal.throwIfCanceled();int end=Math.min(ids.length,from+BATCH);
                backend.appendBatch(ids,from,end,candidate,signal);
                if(end<ids.length){
                    int delay=backend.batchPauseMillis();if(delay<0 || delay>5000)throw new IllegalStateException("invalid batch pause");
                    long until=android.os.SystemClock.elapsedRealtime()+delay;
                    while(true){
                        signal.throwIfCanceled();long remaining=until-android.os.SystemClock.elapsedRealtime();if(remaining<=0)break;
                        android.os.SystemClock.sleep(Math.min(250,remaining));
                    }
                }
            }
            signal.throwIfCanceled();
            if(!backend.scopeUnchanged(ids,signal)){synchronized(this){if(current(ticket,signal)){state=State.SCOPE_CHANGED;lastFailure=State.SCOPE_CHANGED;}}return;}
            candidate.seal();
            for(int i=0;i<candidate.size();i++){
                if((i&255)==0)signal.throwIfCanceled();
                if(Arrays.binarySearch(ids,candidate.idAt(i))<0)throw new IllegalStateException("location outside visible scope");
            }
            synchronized(this){if(current(ticket,signal)){cache=candidate;scope=ids;visibleCount=ids.length;validCount=candidate.size();loadMillis=android.os.SystemClock.elapsedRealtime()-loadStarted;candidate=null;state=State.READY;}}
        } catch(android.os.OperationCanceledException ignored) {
        } catch(PrimaryPhotoSource.UnsupportedVolumeException failure){synchronized(this){if(current(ticket,signal)){state=State.VOLUME_UNSUPPORTED;lastFailure=State.VOLUME_UNSUPPORTED;}}}
          catch(PrimaryPhotoSource.CapacityException failure){synchronized(this){if(current(ticket,signal)){state=State.CAPACITY_EXCEEDED;lastFailure=State.CAPACITY_EXCEEDED;}}}
          catch(SecurityException failure){synchronized(this){if(current(ticket,signal)){state=State.ACCESS_DENIED;lastFailure=State.ACCESS_DENIED;}}}
          catch(Exception failure){synchronized(this){if(current(ticket,signal)){state=State.FAILED;lastFailure=State.FAILED;}}}
        finally {if(candidate!=null)candidate.close();}
    }
    /** Results are experimental only; callers must discard them on every lifecycle/scope change. */
    public boolean query(final double south,final double north,final double west,final double east,final Callback<Window> callback) {
        if(callback==null)throw new IllegalArgumentException();
        final PrimitiveLocationSet data;final long ticket,queryTicket;final CancellationSignal signal,localQuery,previous;
        synchronized(this){
            if(!foreground||state!=State.READY||cache==null)return false;
            data=cache;ticket=epoch;signal=current;previous=querySignal;localQuery=new CancellationSignal();querySignal=localQuery;queryTicket=++queryEpoch;
            if(pendingQuery!=null)worker.remove(pendingQuery);pendingQuery=null;
        }
        if(previous!=null)previous.cancel();
        final Runnable task=new Runnable(){public void run(){
            synchronized(MapDataRepository.this){if(pendingQuery==this)pendingQuery=null;}
            long queryStarted=android.os.SystemClock.elapsedRealtime();
            Window result=null;
            try {
                synchronized(data){
                    localQuery.throwIfCanceled();int[] indexes=data.queryIndexes(south,north,west,east,localQuery);
                    localQuery.throwIfCanceled();
                    result=new Window(MapDataRepository.this,data,ticket,signal,queryTicket,localQuery,indexes,indexes.length);
                }
            } catch(android.os.OperationCanceledException ignored) {return;}
              catch(RuntimeException failure){if(currentQuery(ticket,signal,queryTicket,localQuery))callback.complete(null);return;}
            if(currentQuery(ticket,signal,queryTicket,localQuery)){synchronized(MapDataRepository.this){filterMillis=android.os.SystemClock.elapsedRealtime()-queryStarted;}callback.complete(result);}
        }};
        synchronized(this){
            if(!currentQuery(ticket,signal,queryTicket,localQuery))return false;
            pendingQuery=task;
            try {worker.execute(task);return true;}catch(RejectedExecutionException e){if(pendingQuery==task)pendingQuery=null;return false;}
        }
    }
    boolean metadata(final long id,final Callback<MediaStoreLookup.Metadata> callback){
        if(callback==null)throw new IllegalArgumentException();
        final long ticket;final CancellationSignal signal;final PrimitiveLocationSet data;
        synchronized(this){if(!foreground||state!=State.READY||cache==null)return false;ticket=epoch;signal=current;data=cache;}
        try {worker.execute(new Runnable(){public void run(){
            try {boolean present;synchronized(data){present=data.contains(id);}if(!present){if(current(ticket,signal))callback.complete(null);return;}MediaStoreLookup.Metadata result=backend.metadata(id,signal);if(result!=null && result.id!=id){failIfCurrent(ticket,signal);return;}if(current(ticket,signal))callback.complete(result);}
            catch(android.os.OperationCanceledException ignored) { }
            catch(RuntimeException failure){failIfCurrent(ticket,signal);}
        }});return true;}catch(RejectedExecutionException e){return false;}
    }
    boolean thumbnail(final long id,final Callback<Bitmap> callback){
        if(callback==null)throw new IllegalArgumentException();
        final long ticket;final CancellationSignal signal;final PrimitiveLocationSet data;
        synchronized(this){if(!foreground||state!=State.READY||cache==null)return false;ticket=epoch;signal=current;data=cache;}
        try {worker.execute(new Runnable(){public void run(){
            Bitmap result=null;
            try {boolean present;synchronized(data){present=data.contains(id);}if(!present){if(current(ticket,signal))callback.complete(null);return;}result=backend.thumbnail(id,signal);if(current(ticket,signal)){callback.complete(result);result=null;}}
            catch(android.os.OperationCanceledException ignored) { }
            catch(RuntimeException failure){failIfCurrent(ticket,signal);}
            finally {if(result!=null)result.recycle();}
        }});return true;}catch(RejectedExecutionException e){return false;}
    }
    private static final class DeviceBackend implements Backend {
        private final PrimaryPhotoSource source;private final MediaStoreLookup media;
        DeviceBackend(Context context){source=new PrimaryPhotoSource(context);media=new MediaStoreLookup(context);}
        // This backend is deliberately unreachable until a vendor identity contract is verified.
        String diagnostics(){return "\n无 GPS "+source.noGpsCount+" · 无效坐标 "+source.invalidCount+" · Provider 缺失 "+source.missingCount;}
        public int batchPauseMillis(){return 0;}
        public long[] visibleIds(int capacity,CancellationSignal signal){return source.visibleIds(capacity,signal);}
        public void appendBatch(long[] ids,int from,int to,PrimitiveLocationSet output,CancellationSignal signal){source.appendBatch(ids,from,to,output,signal);}
        public boolean scopeUnchanged(long[] ids,CancellationSignal signal){return source.primaryOnly() && Arrays.equals(ids,source.visibleIds(CAPACITY,signal));}
        public MediaStoreLookup.Metadata metadata(long id,CancellationSignal signal){return media.lookup(id,signal);}
        public Bitmap thumbnail(long id,CancellationSignal signal){return media.thumbnail(id,signal);}
    }
}
