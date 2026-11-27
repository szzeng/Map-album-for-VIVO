package io.github.szzeng.mapalbum;

import java.util.*;

public final class ScreenClustersTest {
    private static void require(boolean b){if(!b)throw new AssertionError();}
    private static List<ScreenClusters.Cluster> group(int[] x,int[] y){return ScreenClusters.group(x,y,48,new int[]{8},100,()->false);}
    private static final class Ref {
        long x,y;int n;List<Integer> members=new ArrayList<>();
        Ref(int x,int y,int i){this.x=x;this.y=y;n=1;members.add(i);}
        int cx(){return (int)Math.round((double)x/n);} int cy(){return (int)Math.round((double)y/n);}
        int first(){return Collections.min(members);}
        void add(Ref b){x+=b.x;y+=b.y;n+=b.n;members.addAll(b.members);}
    }
    private static List<Ref> reference(int[] x,int[] y){
        List<Ref> nodes=new ArrayList<>();
        for(int i=0;i<x.length;i++){Ref found=null;for(Ref n:nodes)if(n.cx()==x[i]&&n.cy()==y[i]){found=n;break;}
            if(found==null)nodes.add(new Ref(x[i],y[i],i));else found.add(new Ref(x[i],y[i],i));}
        while(true){int ai=-1,bi=-1;long bestGap=Long.MAX_VALUE,bestCenter=Long.MAX_VALUE;int bestFirst=Integer.MAX_VALUE,bestSecond=Integer.MAX_VALUE;
            for(int i=0;i<nodes.size();i++)for(int j=0;j<i;j++){
                Ref a=nodes.get(i),b=nodes.get(j);long dx=Math.abs((long)a.cx()-b.cx()),dy=Math.abs((long)a.cy()-b.cy());
                long gx=Math.max(0,dx-48),gy=Math.max(0,dy-48),gap=gx*gx+gy*gy,center=dx*dx+dy*dy;
                int first=Math.min(a.first(),b.first()),second=Math.max(a.first(),b.first());
                if(gap<=64&&(gap<bestGap||gap==bestGap&&(center<bestCenter||center==bestCenter&&(first<bestFirst||first==bestFirst&&second<bestSecond)))){
                    ai=i;bi=j;bestGap=gap;bestCenter=center;bestFirst=first;bestSecond=second;
                }
            }
            if(ai<0)break;Ref a=nodes.remove(ai),b=nodes.remove(bi);a.add(b);nodes.add(a);
        }
        nodes.sort(Comparator.comparingInt(Ref::first));for(Ref n:nodes)Collections.sort(n.members);return nodes;
    }
    private static void complete(List<ScreenClusters.Cluster> cs,int n,int gap){
        boolean[] seen=new boolean[n];int total=0;
        for(ScreenClusters.Cluster c:cs){int previous=-1;for(int m:c.members){require(m>previous&&!seen[m]);previous=m;seen[m]=true;total++;}}
        require(total==n);
        for(int i=0;i<cs.size();i++)for(int j=0;j<i;j++){
            double x=Math.max(0,Math.abs(cs.get(i).x-cs.get(j).x)-48),y=Math.max(0,Math.abs(cs.get(i).y-cs.get(j).y)-48);
            require(x*x+y*y>gap*gap);
        }
    }
    public static void main(String[] args){
        List<ScreenClusters.Cluster> same=group(new int[]{7,7,7},new int[]{9,9,9});
        require(same.size()==1&&Arrays.equals(same.get(0).members,new int[]{0,1,2}));
        for(int d:new int[]{10,40,48,55,56})require(group(new int[]{100,100+d},new int[]{100,100}).size()==1);
        require(group(new int[]{100,157},new int[]{100,100}).size()==2);
        require(group(new int[]{100,153},new int[]{100,153}).size()==1);
        require(group(new int[]{100,154},new int[]{100,154}).size()==2);
        List<ScreenClusters.Cluster> close=group(new int[]{10,20,120},new int[]{10,20,20});
        require(close.size()==2&&close.get(0).x==15&&close.get(0).y==15);
        require(group(new int[]{10,60,110},new int[]{10,10,10}).size()==2);
        List<ScreenClusters.Cluster> weighted=group(new int[]{10,10,10,60},new int[]{10,10,10,10});
        require(weighted.size()==1&&weighted.get(0).x==23&&weighted.get(0).members.length==4);
        require(ScreenClusters.group(new int[]{0,1000},new int[]{0,1000},48,new int[]{8,12,16,24},1,()->false).size()==2);
        require(group(new int[]{10,110,220},new int[]{10,110,220}).size()==3);
        require(group(new int[0],new int[0]).isEmpty());
        require(group(new int[]{0,Integer.MAX_VALUE},new int[]{0,Integer.MAX_VALUE}).size()==2);
        try{group(new int[]{-1},new int[]{0});throw new AssertionError();}catch(IllegalArgumentException expected){}
        int[] x={110,120,210},y={210,220,220};List<ScreenClusters.Cluster> a=group(x,y),b=group(new int[]{147,157,247},new int[]{183,193,193});
        require(a.size()==b.size());for(int i=0;i<a.size();i++){require(Arrays.equals(a.get(i).members,b.get(i).members));require(b.get(i).x==a.get(i).x+37&&b.get(i).y==a.get(i).y-27);}
        require(group(new int[]{10,40},new int[]{10,10}).size()==1);
        require(group(new int[]{10,70},new int[]{10,10}).size()==2);
        int[] xx=new int[1000],yy=new int[1000];for(int i=0;i<xx.length;i++){xx[i]=i%40*20;yy[i]=i/40*20;}
        complete(group(xx,yy),xx.length,8);
        Random random=new Random(6006);
        for(int trial=0;trial<200;trial++){
            int[] rx=new int[80],ry=new int[80];
            for(int i=0;i<rx.length;i++){rx[i]=random.nextInt(500);ry[i]=random.nextInt(500);}
            List<ScreenClusters.Cluster> one=group(rx,ry),two=group(rx,ry);
            complete(one,rx.length,8);require(one.size()==two.size());
            for(int i=0;i<one.size();i++)require(Arrays.equals(one.get(i).members,two.get(i).members));
        }
        for(int trial=0;trial<150;trial++){
            int[] rx=new int[24],ry=new int[24];for(int i=0;i<rx.length;i++){rx[i]=random.nextInt(180);ry[i]=random.nextInt(180);}
            List<ScreenClusters.Cluster> actual=group(rx,ry);List<Ref> expected=reference(rx,ry);require(actual.size()==expected.size());
            for(int i=0;i<actual.size();i++){Ref e=expected.get(i);ScreenClusters.Cluster a1=actual.get(i);require(a1.x==e.cx()&&a1.y==e.cy()&&a1.members.length==e.members.size());
                for(int j=0;j<a1.members.length;j++)require(a1.members[j]==e.members.get(j));}
        }
        try{ScreenClusters.group(xx,yy,48,new int[]{8},100,()->true);throw new AssertionError();}catch(java.util.concurrent.CancellationException expected){}
        System.out.println("ScreenClusters: passed");
    }
}
