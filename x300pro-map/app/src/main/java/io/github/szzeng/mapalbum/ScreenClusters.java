package io.github.szzeng.mapalbum;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Transient screen-space clustering. The grid is only a neighbour index. */
final class ScreenClusters {
    static final class Cluster {
        final int x, y;
        final int[] members;
        Cluster(int x, int y, int[] members) { this.x=x; this.y=y; this.members=members; }
    }
    private static final class Cell {
        final long x,y;
        Cell(long x,long y){this.x=x;this.y=y;}
        public int hashCode(){return 31*Long.hashCode(x)+Long.hashCode(y);}
        public boolean equals(Object o){return o instanceof Cell && x==((Cell)o).x && y==((Cell)o).y;}
    }
    private static final class Node {
        long sumX,sumY; int count, first, version; final int centerX,centerY;
        Node left,right,best; Pair bestPair; final int[] initialMembers; boolean alive=true;
        Set<Node> followers=new HashSet<>();
        Node(Seed s){sumX=s.sumX;sumY=s.sumY;count=s.size;first=s.members[0];initialMembers=Arrays.copyOf(s.members,s.size);
            centerX=(int)Math.round((double)sumX/count);centerY=(int)Math.round((double)sumY/count);}
        Node(Node a,Node b){sumX=a.sumX+b.sumX;sumY=a.sumY+b.sumY;count=a.count+b.count;first=Math.min(a.first,b.first);left=a;right=b;
            initialMembers=null;centerX=(int)Math.round((double)sumX/count);centerY=(int)Math.round((double)sumY/count);}
        int x(){return centerX;}
        int y(){return centerY;}
    }
    private static final class Seed {
        long sumX,sumY; int size; int[] members=new int[4];
        void add(int x,int y,int index){sumX+=x;sumY+=y;if(size==members.length)members=Arrays.copyOf(members,size*2);members[size++]=index;}
    }
    private static final class Pair implements Comparable<Pair> {
        final Node a,b; final int version; final double gap,center;
        Pair(Node a,Node b,int width){this.a=a;this.b=b;version=a.version;
            long dx=Math.abs((long)a.x()-b.x()),dy=Math.abs((long)a.y()-b.y());
            double gx=Math.max(0L,dx-width),gy=Math.max(0L,dy-width);
            gap=gx*gx+gy*gy;center=(double)dx*dx+(double)dy*dy;
        }
        public int compareTo(Pair p){int c=Double.compare(gap,p.gap);if(c!=0)return c;
            c=Double.compare(center,p.center);if(c!=0)return c;
            c=Integer.compare(Math.min(a.first,b.first),Math.min(p.a.first,p.b.first));
            return c!=0?c:Integer.compare(Math.max(a.first,b.first),Math.max(p.a.first,p.b.first));}
    }
    private static final class Engine {
        final int width, cell, fineCell; int gap; final BooleanSupplier cancelled;
        final Map<Cell,Set<Node>> grid=new HashMap<>();
        final Map<Cell,Set<Node>> fineGrid=new HashMap<>();
        final PriorityQueue<Pair> queue=new PriorityQueue<>();
        int steps, live;
        Engine(int width,int initialGap,BooleanSupplier cancelled){this.width=width;this.cell=width+initialGap;this.fineCell=Math.max(1,width/4);this.cancelled=cancelled;}
        void check(){if((++steps&1023)==0 && cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();}
        Cell key(Node n){return new Cell(Math.floorDiv((long)n.x(),cell),Math.floorDiv((long)n.y(),cell));}
        Cell fineKey(Node n){return new Cell(Math.floorDiv((long)n.x(),fineCell),Math.floorDiv((long)n.y(),fineCell));}
        void add(Node n){grid.computeIfAbsent(key(n),k->new HashSet<>()).add(n);fineGrid.computeIfAbsent(fineKey(n),k->new HashSet<>()).add(n);live++;}
        void removeFrom(Map<Cell,Set<Node>> index,Cell k,Node n){Set<Node> bucket=index.get(k);bucket.remove(n);if(bucket.isEmpty())index.remove(k);}
        void remove(Node n){removeFrom(grid,key(n),n);removeFrom(fineGrid,fineKey(n),n);live--;}
        List<Node> neighbours(Node n){
            List<Node> result=new ArrayList<>(); Cell k=key(n);
            if(live>1000){
                long reach=(long)width+gap;
                long minX=Math.floorDiv((long)n.x()-reach,fineCell),maxX=Math.floorDiv((long)n.x()+reach,fineCell);
                long minY=Math.floorDiv((long)n.y()-reach,fineCell),maxY=Math.floorDiv((long)n.y()+reach,fineCell);
                for(long cx=minX;cx<=maxX;cx++)for(long cy=minY;cy<=maxY;cy++){
                    Set<Node> bucket=fineGrid.get(new Cell(cx,cy));
                    if(bucket!=null)for(Node other:bucket){check();if(other!=n&&other.alive&&Math.abs((long)n.x()-other.x())<=reach&&Math.abs((long)n.y()-other.y())<=reach)result.add(other);}
                }
                return result;
            }
            int radius=(int)(((long)width+gap+cell-1)/cell);
            for(int dx=-radius;dx<=radius;dx++)for(int dy=-radius;dy<=radius;dy++){
                Set<Node> bucket=grid.get(new Cell(k.x+dx,k.y+dy));
                if(bucket!=null)for(Node other:bucket){check();if(other!=n && other.alive)result.add(other);}
            } return result;
        }
        Pair betterCandidate(Node a,Node b,Pair best){
            long dx=Math.abs((long)a.x()-b.x()),dy=Math.abs((long)a.y()-b.y());
            double gx=Math.max(0L,dx-width),gy=Math.max(0L,dy-width),distance=gx*gx+gy*gy;
            if(distance>(double)gap*gap)return best;
            double center=(double)dx*dx+(double)dy*dy;
            if(best!=null){int c=Double.compare(distance,best.gap);if(c>0)return best;
                if(c==0){c=Double.compare(center,best.center);if(c>0)return best;
                    if(c==0){int first=Math.min(a.first,b.first),second=Math.max(a.first,b.first);
                        int oldFirst=Math.min(best.a.first,best.b.first),oldSecond=Math.max(best.a.first,best.b.first);
                        if(first>oldFirst||first==oldFirst&&second>=oldSecond)return best;}}}
            return new Pair(a,b,width);
        }
        void setBest(Node n,Node b){
            if(n.best!=null)n.best.followers.remove(n);
            n.best=b;n.version++;n.bestPair=null;
            if(b!=null){b.followers.add(n);n.bestPair=new Pair(n,b,width);queue.add(n.bestPair);}
        }
        void recompute(Node n){
            Pair best=null;Cell k=fineKey(n);int maxRadius=(int)(((long)width+gap+fineCell-1)/fineCell)+1;
            for(int radius=0;radius<=maxRadius;radius++){
                for(int dx=-radius;dx<=radius;dx++)for(int dy=-radius;dy<=radius;dy++){
                    if(radius>0&&Math.abs(dx)!=radius&&Math.abs(dy)!=radius)continue;
                    Set<Node> bucket=fineGrid.get(new Cell(k.x+dx,k.y+dy));
                    if(bucket!=null)for(Node other:bucket){check();if(other==n||!other.alive)continue;
                        best=betterCandidate(n,other,best);}
                }
                // With zero rectangle gap, only centre distance can improve the winner.
                // Points outside this square are at least 'outside' pixels away.
                if(best!=null&&best.gap==0){long left=(k.x-radius)*fineCell,right=(k.x+radius+1)*fineCell-1;
                    long top=(k.y-radius)*fineCell,bottom=(k.y+radius+1)*fineCell-1;
                    long outside=Math.min(Math.min((long)n.x()-left+1,right-n.x()+1),Math.min((long)n.y()-top+1,bottom-n.y()+1));
                    if((double)outside*outside>best.center)break;
                }
            }
            setBest(n,best==null?null:best.b);
        }
        void consider(Node n,Node other){
            Pair old=n.bestPair;
            if(betterCandidate(n,other,old)!=old)setBest(n,other);
        }
        List<Cluster> run(int[] xs,int[] ys,int[] gaps,int softLimit){
            // Large views seed tiny, overlapping screen regions before collision merging.
            // The grid is not the final grouping rule: all resulting centres still go
            // through the same neighbour search, including across cell boundaries.
            Map<Long,Seed> exact=new LinkedHashMap<>();
            // Every pair in a seed cell overlaps: its side is only one quarter
            // of the marker width. Cross-cell collision checks remain mandatory.
            int seedCell=xs.length>=10000?Math.max(1,width/4):1;
            for(int i=0;i<xs.length;i++){
                check(); long key=((long)(xs[i]/seedCell)<<32) ^ ((ys[i]/seedCell)&0xffffffffL);
                Seed seed=exact.get(key);if(seed==null){seed=new Seed();exact.put(key,seed);}seed.add(xs[i],ys[i],i);
            }
            for(Seed seed:exact.values()){check();add(new Node(seed));}
            exact.clear();
            for(int stage=0;stage<gaps.length;stage++){
            gap=gaps[stage];
            for(Set<Node> bucket:grid.values())for(Node n:bucket)recompute(n);
            while(!queue.isEmpty()){
                check();Pair p=queue.poll();Node a=p.a,b=p.b;
                if(!a.alive||!b.alive||a.version!=p.version||a.best!=b)continue;
                Set<Node> affected=new HashSet<>();affected.addAll(a.followers);affected.addAll(b.followers);
                if(a.best!=null)a.best.followers.remove(a);if(b.best!=null)b.best.followers.remove(b);
                remove(a);remove(b);a.alive=false;b.alive=false;
                Node merged=new Node(a,b);add(merged);
                affected.remove(a);affected.remove(b);
                for(Node n:affected)if(n.alive)recompute(n);
                recompute(merged);
                for(Node n:neighbours(merged))consider(n,merged);
                // Stale queue entries are bounded without changing the selected pair.
                if(queue.size()>Math.max(1024,live*8L)){
                    queue.clear();for(Set<Node> bucket:grid.values())for(Node n:bucket)if(n.best!=null)queue.add(new Pair(n,n.best,width));
                }
            }
            if(live<=softLimit)break;
            }
            List<Node> nodes=new ArrayList<>();for(Set<Node> bucket:grid.values())nodes.addAll(bucket);
            nodes.sort(Comparator.comparingInt(n->n.first));List<Cluster> out=new ArrayList<>();
            for(Node n:nodes){int[] members=new int[n.count];int at=0;ArrayDeque<Node> stack=new ArrayDeque<>();stack.push(n);
                while(!stack.isEmpty()){check();Node v=stack.pop();if(v.left==null){System.arraycopy(v.initialMembers,0,members,at,v.initialMembers.length);at+=v.initialMembers.length;}else{stack.push(v.left);stack.push(v.right);}}
                Arrays.sort(members);out.add(new Cluster(n.x(),n.y(),members));}
            return out;
        }
    }
    /** Returns a uniform translation only when every projected source still matches. */
    static long[] translation(int[] oldX,int[] oldY,int[] oldSources,int[] x,int[] y,int[] sources,int size){
        if(oldX.length!=size||oldY.length!=size||oldSources.length!=size||x.length<size||y.length<size||sources.length<size)return null;
        long dx=size==0?0:(long)x[0]-oldX[0],dy=size==0?0:(long)y[0]-oldY[0];
        for(int i=0;i<size;i++)if(oldSources[i]!=sources[i]||(long)x[i]-oldX[i]!=dx||(long)y[i]-oldY[i]!=dy)return null;
        return new long[]{dx,dy};
    }
    static boolean canReusePan(int width,int height,int previousWidth,int previousHeight,
            float zoom,float previousZoom,float rotate,float previousRotate,float tilt,float previousTilt,
            int anchorX,int anchorY,int originalX,int originalY,int marginX,int marginY,int clearance){
        return width==previousWidth&&height==previousHeight&&Float.compare(zoom,previousZoom)==0
            &&Float.compare(rotate,previousRotate)==0&&Float.compare(tilt,previousTilt)==0&&tilt==0f
            &&marginX>=clearance&&marginY>=clearance
            &&Math.abs((long)anchorX-originalX)<=marginX-clearance
            &&Math.abs((long)anchorY-originalY)<=marginY-clearance;
    }
    static List<Cluster> group(int[] xs,int[] ys,int markerPixels,int[] gaps,int softLimit,BooleanSupplier cancelled){
        if(xs==null||ys==null||xs.length!=ys.length||markerPixels<1||gaps==null||gaps.length==0||softLimit<1||cancelled==null)throw new IllegalArgumentException();
        for(int i=0;i<xs.length;i++)if(xs[i]<0||ys[i]<0)throw new IllegalArgumentException("points must be in viewport");
        int previous=-1;
        for(int gap:gaps){if(gap<0||gap<previous||markerPixels>Integer.MAX_VALUE-gap)throw new IllegalArgumentException();previous=gap;}
        if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
        return new Engine(markerPixels,gaps[0],cancelled).run(xs,ys,gaps,softLimit);
    }
    private ScreenClusters(){}
}
