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
        long sumX,sumY; int count, first, version;
        Node left,right,best; boolean alive=true;
        Set<Node> followers=new HashSet<>();
        Node(int x,int y,int i){sumX=x;sumY=y;count=1;first=i;}
        Node(Node a,Node b){sumX=a.sumX+b.sumX;sumY=a.sumY+b.sumY;count=a.count+b.count;first=Math.min(a.first,b.first);left=a;right=b;}
        int x(){return (int)Math.round((double)sumX/count);}
        int y(){return (int)Math.round((double)sumY/count);}
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
        final int width, gap, cell, fineCell; final BooleanSupplier cancelled;
        final Map<Cell,Set<Node>> grid=new HashMap<>();
        final Map<Cell,Set<Node>> fineGrid=new HashMap<>();
        final PriorityQueue<Pair> queue=new PriorityQueue<>();
        int steps, live;
        Engine(int width,int gap,BooleanSupplier cancelled){this.width=width;this.gap=gap;this.cell=width+gap;this.fineCell=Math.max(1,width/4);this.cancelled=cancelled;}
        void check(){if((++steps&1023)==0 && cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();}
        Cell key(Node n){return new Cell(Math.floorDiv((long)n.x(),cell),Math.floorDiv((long)n.y(),cell));}
        Cell fineKey(Node n){return new Cell(Math.floorDiv((long)n.x(),fineCell),Math.floorDiv((long)n.y(),fineCell));}
        void add(Node n){grid.computeIfAbsent(key(n),k->new HashSet<>()).add(n);fineGrid.computeIfAbsent(fineKey(n),k->new HashSet<>()).add(n);live++;}
        void removeFrom(Map<Cell,Set<Node>> index,Cell k,Node n){Set<Node> bucket=index.get(k);bucket.remove(n);if(bucket.isEmpty())index.remove(k);}
        void remove(Node n){removeFrom(grid,key(n),n);removeFrom(fineGrid,fineKey(n),n);live--;}
        List<Node> neighbours(Node n){
            List<Node> result=new ArrayList<>(); Cell k=key(n);
            for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++){
                Set<Node> bucket=grid.get(new Cell(k.x+dx,k.y+dy));
                if(bucket!=null)for(Node other:bucket){check();if(other!=n && other.alive)result.add(other);}
            } return result;
        }
        Pair candidate(Node a,Node b){Pair p=new Pair(a,b,width);return p.gap<=(double)gap*gap?p:null;}
        void setBest(Node n,Node b){
            if(n.best!=null)n.best.followers.remove(n);
            n.best=b;n.version++;
            if(b!=null){b.followers.add(n);queue.add(new Pair(n,b,width));}
        }
        void recompute(Node n){
            Pair best=null;Cell k=fineKey(n);int maxRadius=(int)(((long)cell+fineCell-1)/fineCell)+1;
            for(int radius=0;radius<=maxRadius;radius++){
                for(int dx=-radius;dx<=radius;dx++)for(int dy=-radius;dy<=radius;dy++){
                    if(radius>0&&Math.abs(dx)!=radius&&Math.abs(dy)!=radius)continue;
                    Set<Node> bucket=fineGrid.get(new Cell(k.x+dx,k.y+dy));
                    if(bucket!=null)for(Node other:bucket){check();if(other==n||!other.alive)continue;
                        Pair p=candidate(n,other);if(p!=null&&(best==null||p.compareTo(best)<0))best=p;}
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
            Pair p=candidate(n,other);if(p==null)return;
            Pair old=n.best==null?null:new Pair(n,n.best,width);
            if(old==null||p.compareTo(old)<0)setBest(n,other);
        }
        List<Cluster> run(int[] xs,int[] ys){
            // Coordinate coalescing happens before neighbour search, including very large piles.
            Map<Long,Node> exact=new LinkedHashMap<>();
            for(int i=0;i<xs.length;i++){
                check(); long key=((long)xs[i]<<32) ^ (ys[i]&0xffffffffL);Node prior=exact.get(key);
                Node item=new Node(xs[i],ys[i],i);exact.put(key,prior==null?item:new Node(prior,item));
            }
            for(Node n:exact.values())add(n);
            for(Node n:exact.values())recompute(n);
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
            List<Node> nodes=new ArrayList<>();for(Set<Node> bucket:grid.values())nodes.addAll(bucket);
            nodes.sort(Comparator.comparingInt(n->n.first));List<Cluster> out=new ArrayList<>();
            for(Node n:nodes){int[] members=new int[n.count];int at=0;ArrayDeque<Node> stack=new ArrayDeque<>();stack.push(n);
                while(!stack.isEmpty()){Node v=stack.pop();if(v.left==null)members[at++]=v.first;else{stack.push(v.left);stack.push(v.right);}}
                Arrays.sort(members);out.add(new Cluster(n.x(),n.y(),members));}
            return out;
        }
    }
    static List<Cluster> group(int[] xs,int[] ys,int markerPixels,int[] gaps,int softLimit,BooleanSupplier cancelled){
        if(xs==null||ys==null||xs.length!=ys.length||markerPixels<1||gaps==null||gaps.length==0||softLimit<1||cancelled==null)throw new IllegalArgumentException();
        for(int i=0;i<xs.length;i++)if(xs[i]<0||ys[i]<0)throw new IllegalArgumentException("points must be in viewport");
        List<Cluster> result=null;
        for(int gap:gaps){if(gap<0||markerPixels>Integer.MAX_VALUE-gap)throw new IllegalArgumentException();
            if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
            result=new Engine(markerPixels,gap,cancelled).run(xs,ys);if(result.size()<=softLimit)break;}
        return result;
    }
    private ScreenClusters(){}
}
