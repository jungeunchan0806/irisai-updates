package com.irisai.farm;

import android.content.Context;
import android.graphics.Bitmap;
import com.irisai.autogoal.YoloDetector;
import com.irisai.autogoal.Geometry.*;
import org.opencv.android.Utils;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The game's own surface feeds the original Iris 0.0.9 ONNX models. */
final class FarmVision implements AutoCloseable {
    interface Assets { byte[] read(String name) throws IOException; String model(String name) throws IOException; }
    static final class Observation {
        String state="unknown";
        FarmPolicy.Decision decision;
        double tapX,tapY;
        boolean tap;
    }
    private static final class Template {String state;Mat image;Rect area;double threshold;Template(String s,Mat m,Rect r,double t){state=s;image=m;area=r;threshold=t;}}
    private final ArrayList<Template> templates=new ArrayList<>();
    private final FarmPolicy policy=new FarmPolicy();
    private final MovementRecovery recovery=new MovementRecovery();
    private final FarmPolicy.Brawler brawler;
    private YoloDetector entities,tiles;
    private Mat resultReport,resultReplay;
    private double seen=-1e9;
    private P lastPlayer;
    FarmVision(Context context,FarmPolicy.Brawler brawler) throws Exception {
        this(androidAssets(context),brawler);
    }
    private static Assets androidAssets(Context context) {
        System.loadLibrary("opencv_java4");
        return new Assets(){public byte[] read(String name)throws IOException{return FarmVision.read(context,name);}public String model(String name)throws IOException{return FarmVision.model(context,name);}};
    }
    FarmVision(Assets assets,FarmPolicy.Brawler brawler) throws Exception {
        this.brawler=brawler;Core.setNumThreads(2);
        try {
            entities=new YoloDetector(assets.model("mainInGameModel.onnx"));tiles=new YoloDetector(assets.model("tileDetector.onnx"));
            JSONObject regions=new JSONObject(new String(assets.read("regions.json"),StandardCharsets.UTF_8)).getJSONObject("template_matching");
            for(String result:new String[]{"victory","defeat","draw","1st","2nd","3rd","3rd_alt","4th"})add(assets,"results/"+result+".png","result_"+result,regions.getJSONArray("match_result"),.88);
            add(assets,"states/game_highlight.png","wait",new JSONArray("[0,0,1800,460]"),.82);
            add(assets,"states/brawler_menu_heart.png","selection",new JSONArray("[900,0,1000,140]"),.8);
            add(assets,"states/lobby_menu.png","lobby",regions.getJSONArray("lobby_menu"),.84);
            add(assets,"states/exit_match_making.png","wait",regions.getJSONArray("exit_match_making"),.8);
            add(assets,"states/brawl_loading_logo.png","wait",regions.getJSONArray("brawl_loading"),.8);
            add(assets,"states/match_intro_vs.png","wait",regions.getJSONArray("match_intro"),.8);
            add(assets,"states/spectating_following.png","wait",regions.getJSONArray("spectating"),.8);
            add(assets,"states/idle_disconnect.png","reconnect",regions.getJSONArray("idle_disconnect"),.84);
            // A field's match-over banner must wait for the actual results page.
            Mat banner=decode(assets.read("match_over.png"),false);
            templates.add(0,new Template("wait",banner,new Rect(220,80,520,250),.82));
            resultReport=decode(assets.read("results/report_icon.png"),true);
            resultReplay=decode(assets.read("results/replay_icon.png"),true);
            Imgproc.resize(resultReport,resultReport,new Size(resultReport.cols()/2,resultReport.rows()/2));
            Imgproc.resize(resultReplay,resultReplay,new Size(resultReplay.cols()/2,resultReplay.rows()/2));
        }catch(Exception|LinkageError e){close();throw e;}
    }
    private void add(Assets assets,String file,String state,JSONArray region,double threshold) throws Exception {
        Mat original=decode(assets.read(file),true),resized=new Mat();
        Imgproc.resize(original,resized,new Size(Math.max(1,original.cols()/2),Math.max(1,original.rows()/2)));original.release();
        templates.add(new Template(state,resized,new Rect(region.getInt(0)/2,region.getInt(1)/2,region.getInt(2)/2,region.getInt(3)/2),threshold));
    }
    private static Mat decode(byte[] data,boolean color)throws IOException{
        MatOfByte bytes=new MatOfByte(data);Mat m=Imgcodecs.imdecode(bytes,color?Imgcodecs.IMREAD_COLOR:Imgcodecs.IMREAD_GRAYSCALE);bytes.release();
        if(m.empty()){m.release();throw new IOException("Invalid Iris image");}if(color)Imgproc.cvtColor(m,m,Imgproc.COLOR_BGR2RGB);return m;
    }
    Observation observe(Bitmap bitmap,double now,boolean repeat) throws Exception {
        Mat rgba=new Mat(),rgb=new Mat();try{Utils.bitmapToMat(bitmap,rgba);Imgproc.cvtColor(rgba,rgb,Imgproc.COLOR_RGBA2RGB);return observeRgb(rgb,now,repeat);}finally{rgba.release();rgb.release();}
    }
    Observation observeRgb(Mat rgb,double now,boolean repeat) throws Exception {
        Observation o=new Observation();Mat gray=new Mat();Imgproc.cvtColor(rgb,gray,Imgproc.COLOR_RGB2GRAY);
        try {
            // The statistics page has no victory/defeat title. Require both
            // independent, non-text result icons and the blue proceed button.
            if(matches(rgb,resultReport,new Rect(570,0,380,60),.86)&&matches(rgb,resultReplay,new Rect(820,0,130,60),.86)){
                Mat hsv=new Mat();try{Imgproc.cvtColor(rgb,hsv,Imgproc.COLOR_RGB2HSV);
                    if(count(hsv,new Rect(760,475,170,35),new Scalar(95,150,120),new Scalar(120,255,255))>1600){
                        o.state="result_statistics";o.tap=repeat;o.tapX=830;o.tapY=490;policy.reset();recovery.reset();lastPlayer=null;return o;
                    }
                }finally{hsv.release();}
            }
            for(Template t:templates){Mat image=t.image.channels()==1?gray:rgb;
                if(t.area.x+t.area.width>image.cols()||t.area.y+t.area.height>image.rows()||t.image.cols()>t.area.width||t.image.rows()>t.area.height)continue;
                Mat region=image.submat(t.area),scores=new Mat();double score;
                try{Imgproc.matchTemplate(region,t.image,scores,Imgproc.TM_CCOEFF_NORMED);score=Core.minMaxLoc(scores).maxVal;}finally{region.release();scores.release();}
                if(score>=t.threshold){o.state=t.state;policy.reset();recovery.reset();lastPlayer=null;
                    if(t.state.equals("lobby")){o.tap=repeat;o.tapX=830;o.tapY=490;}
                    else if(t.state.startsWith("result_")){o.tap=repeat;o.tapX=t.state.equals("result_victory")?680:830;o.tapY=t.state.equals("result_victory")?460:490;}
                    else if(t.state.equals("reconnect")){o.tap=repeat;o.tapX=270;o.tapY=315;}
                    return o;}
            }
            Box player=null;float confidence=0;ArrayList<P> enemies=new ArrayList<>();ArrayList<Box> all=new ArrayList<>(),walls=new ArrayList<>();
            for(YoloDetector.Detection d:entities.detect(rgb,.5f,20)){all.add(d.box);if(d.kind==2&&d.score>confidence){confidence=d.score;player=d.box;}else if(d.kind==0)enemies.add(d.box.feet());}
            if(player==null){o.state="player_missing";return o;}
            P feet=player.feet();
            if(now-seen>.5||(lastPlayer!=null&&lastPlayer.distance(feet)>172.8)){policy.reset();recovery.reset();}
            seen=now;lastPlayer=feet;
            for(YoloDetector.Detection d:tiles.detect(rgb,.55f,100))if(d.kind==0)walls.add(d.box.footprint());
            FarmPolicy.Frame f=new FarmPolicy.Frame();f.player=feet;f.enemies=enemies;f.walls=walls;
            Mat hsv=new Mat();try{Imgproc.cvtColor(rgb,hsv,Imgproc.COLOR_RGB2HSV);
                f.superReady=count(hsv,new Rect(730,415,50,50),new Scalar(17,170,200),new Scalar(27,255,255))>450;
                f.gadgetReady=count(hsv,new Rect(790,465,60,60),new Scalar(57,219,165),new Scalar(62,255,255))>325;
                f.hyperReady=count(hsv,new Rect(675,470,50,55),new Scalar(137,158,159),new Scalar(179,255,255))>450;
            }finally{hsv.release();}
            o.decision=policy.decide(f,brawler);
            P direction=recovery.adjust(rgb,feet,new P((player.x1+player.x2)/2,player.y1),all,walls,new P(o.decision.dx,o.decision.dy),now);
            o.decision.dx=direction.x;o.decision.dy=direction.y;if(recovery.recoveries>0)o.decision.phase+=" · 경로 복구 "+recovery.recoveries;
            o.state="battle";return o;
        }finally{gray.release();}
    }
    private static int count(Mat hsv,Rect region,Scalar low,Scalar high){Mat crop=hsv.submat(region),mask=new Mat();try{Core.inRange(crop,low,high,mask);return Core.countNonZero(mask);}finally{crop.release();mask.release();}}
    private static boolean matches(Mat image,Mat template,Rect area,double threshold){Mat crop=image.submat(area),scores=new Mat();try{Imgproc.matchTemplate(crop,template,scores,Imgproc.TM_CCOEFF_NORMED);return Core.minMaxLoc(scores).maxVal>=threshold;}finally{crop.release();scores.release();}}
    static byte[] read(Context c,String name)throws IOException{try(InputStream in=c.getAssets().open("iris_farm/"+name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);return out.toByteArray();}}
    private static String model(Context c,String name)throws IOException {
        File dir=new File(c.getNoBackupFilesDir(),"iris-farm-models");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Model directory unavailable");
        File target=new File(dir,name);byte[] data=read(c,"models/"+name);
        // Refresh atomically from the APK; never reuse a partly written model.
        File temp=new File(dir,name+".tmp");try(OutputStream out=new FileOutputStream(temp)){out.write(data);}if(!temp.renameTo(target))throw new IOException("Could not install model");return target.getAbsolutePath();
    }
    public void close(){for(Template t:templates)t.image.release();templates.clear();if(resultReport!=null){resultReport.release();resultReport=null;}if(resultReplay!=null){resultReplay.release();resultReplay=null;}recovery.close();if(entities!=null){try{entities.close();}catch(Exception ignored){}entities=null;}if(tiles!=null){try{tiles.close();}catch(Exception ignored){}tiles=null;}}
}
