package com.irisai.autogoal;

import java.util.*;

public final class Geometry {
    private Geometry() {}
    public static final class P {
        public final double x,y;
        public P(double x,double y) {this.x=x;this.y=y;}
        public double distance(P p) {return Math.hypot(x-p.x,y-p.y);}
    }
    public static final class Box {
        public final double x1,y1,x2,y2;
        public Box(double x1,double y1,double x2,double y2) {this.x1=x1;this.y1=y1;this.x2=x2;this.y2=y2;}
        public P feet() {return new P((x1+x2)/2,y2-26.5);}
        public Box footprint() {return new Box(x1,Math.max(y1,y2-(x2-x1)*.72),x2,y2);}
        public boolean contains(P p,double radius) {return p.x>=x1-radius&&p.x<=x2+radius&&p.y>=y1-radius&&p.y<=y2+radius;}
    }
    public static boolean clear(P a,P b,List<Box> boxes,double radius) {
        for(Box box:boxes) {
            double lo=0,hi=1,dx=b.x-a.x,dy=b.y-a.y;
            double[] p={-dx,dx,-dy,dy},q={a.x-box.x1+radius,box.x2+radius-a.x,a.y-box.y1+radius,box.y2+radius-a.y};
            boolean hit=true;
            for(int i=0;i<4;i++) {
                if(Math.abs(p[i])<1e-9) {if(q[i]<0){hit=false;break;}}
                else {double t=q[i]/p[i]; if(p[i]<0)lo=Math.max(lo,t);else hi=Math.min(hi,t); if(lo>hi){hit=false;break;}}
            }
            if(hit) return false;
        }
        return true;
    }
    public static P approach(P player,P goal,List<Box> walls) {
        P best=null;double score=Double.POSITIVE_INFINITY;
        for(int forward:new int[]{120,160,200}) for(int sideways:new int[]{0,-48,48,-96,96}) {
            P p=new P(goal.x+sideways,goal.y+forward);
            if(p.x>135&&p.x<825&&p.y>70&&p.y<450&&p.distance(goal)<230&&clear(p,p,walls,17)&&clear(p,goal,walls,6)) {
                double s=player.distance(p)+Math.abs(sideways)*.1;
                if(s<score) {best=p;score=s;}
            }
        }
        return best!=null?best:new P(goal.x,Math.min(440,goal.y+200));
    }
    private static int gridX(double x) {return Math.max(7,Math.min(52,(int)(x/16)));}
    private static int gridY(double y) {return Math.max(0,Math.min(33,(int)(y/16)));}
    private static P center(int i) {return new P((i%60+.5)*16,(i/60+.5)*16);}
    /** Port of Iris A*: inflated walls and no diagonal corner cutting. */
    public static P waypoint(P player,P target,List<Box> walls) {
        boolean[] blocked=new boolean[60*34];
        for(int y=0;y<34;y++) for(int x=0;x<60;x++) blocked[y*60+x]=x<7||x>=53;
        for(Box b:walls) {
            int l=Math.max(7,(int)Math.floor((b.x1-17)/16)),r=Math.min(53,(int)Math.floor((b.x2+17)/16)+1);
            int t=Math.max(0,(int)Math.floor((b.y1-17)/16)),d=Math.min(34,(int)Math.floor((b.y2+17)/16)+1);
            for(int y=t;y<d;y++)for(int x=l;x<r;x++)blocked[y*60+x]=true;
        }
        int start=gridY(player.y)*60+gridX(player.x),end=gridY(target.y)*60+gridX(target.x);
        if(blocked[start]) {
            int best=-1;double score=Double.POSITIVE_INFINITY;
            for(int i=0;i<blocked.length;i++)if(!blocked[i]) {
                double v=Math.hypot(i%60-start%60,i/60-start/60)+.02*Math.hypot(i%60-end%60,i/60-end/60);
                if(v<score){score=v;best=i;}
            }
            return best>=0?center(best):player;
        }
        double[] cost=new double[blocked.length];Arrays.fill(cost,Double.POSITIVE_INFINITY);cost[start]=0;
        int[] parent=new int[blocked.length];Arrays.fill(parent,-1);
        PriorityQueue<double[]> queue=new PriorityQueue<>(Comparator.comparingDouble(a->a[0]));
        queue.add(new double[]{0,start});int closest=start;double closestDistance=Double.POSITIVE_INFINITY;
        int[][] steps={{0,-1},{-1,0},{1,0},{0,1},{-1,-1},{1,-1},{-1,1},{1,1}};
        while(!queue.isEmpty()) {
            int n=(int)queue.remove()[1],x=n%60,y=n/60;
            double d=Math.hypot(x-end%60,y-end/60);
            if(d<closestDistance){closest=n;closestDistance=d;} if(n==end)break;
            for(int[] step:steps) {
                int nx=x+step[0],ny=y+step[1],next=ny*60+nx;
                if(nx<7||nx>=53||ny<0||ny>=34||blocked[next])continue;
                if(step[0]!=0&&step[1]!=0&&(blocked[y*60+nx]||blocked[ny*60+x]))continue;
                double c=cost[n]+Math.hypot(step[0],step[1]);
                if(c<cost[next]){cost[next]=c;parent[next]=n;queue.add(new double[]{c+Math.hypot(nx-end%60,ny-end/60),next});}
            }
        }
        if(closest==start)return player;
        ArrayList<Integer> path=new ArrayList<>();
        for(int n=closest;n!=start;n=parent[n]) {if(n<0)return player;path.add(n);}
        Collections.reverse(path);P result=center(path.get(0));
        for(int i=1;i<Math.min(3,path.size());i++) {P p=center(path.get(i));if(!clear(player,p,walls,17))break;result=p;}
        return result;
    }
}
