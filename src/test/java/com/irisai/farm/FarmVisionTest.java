package com.irisai.farm;

import java.nio.file.*;
import org.junit.*;
import static org.junit.Assert.*;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

public class FarmVisionTest {
    private static FarmVision vision;
    @BeforeClass public static void load()throws Exception {
        nu.pattern.OpenCV.loadLocally();Path root=Path.of("src/main/assets/iris_farm");
        FarmPolicy.Brawler b=new FarmPolicy.Brawler();b.name="shelly";b.attackRange=250;b.safeRange=100;b.superRange=200;
        vision=new FarmVision(new FarmVision.Assets(){
            public byte[] read(String name)throws java.io.IOException{return Files.readAllBytes(root.resolve(name));}
            public String model(String name){return root.resolve("models").resolve(name).toAbsolutePath().toString();}
        },b);
    }
    @AfterClass public static void close(){if(vision!=null)vision.close();}
    private Mat fixture(String name){
        Mat image=Imgcodecs.imread("src/test/resources/frames/"+name+".jpg");assertFalse(name,image.empty());
        Imgproc.resize(image,image,new Size(960,540));Imgproc.cvtColor(image,image,Imgproc.COLOR_BGR2RGB);return image;
    }
    @Test public void blackFrameDoesNotAct()throws Exception {
        Mat black=Mat.zeros(540,960,CvType.CV_8UC3);try{FarmVision.Observation o=vision.observeRgb(black,0,true);assertNull(o.decision);assertFalse(o.tap);}finally{black.release();}
    }
    @Test public void originalRecordedBattleRunsRealModels()throws Exception {
        Mat image=fixture("held_turn");try{FarmVision.Observation o=vision.observeRgb(image,10,true);assertEquals("battle",o.state);assertNotNull(o.decision);assertTrue(Double.isFinite(o.decision.dx));assertFalse(o.tap);}finally{image.release();}
    }
    @Test public void highlightsAndGameOverDoNotAttackOrRequeue()throws Exception {
        for(String name:new String[]{"highlight","clock_expiry","last_goal_win"}){
            Mat image=fixture(name);try{FarmVision.Observation o=vision.observeRgb(image,20,true);assertEquals(name,"wait",o.state);assertNull(name,o.decision);assertFalse(name,o.tap);}finally{image.release();}
        }
    }
    @Test public void repeatCanBeDisabledOnResults()throws Exception {
        Mat image=fixture("result");try{
            FarmVision.Observation o=vision.observeRgb(image,30,true);assertTrue(o.state,o.state.startsWith("result_"));assertTrue(o.tap);assertNull(o.decision);
            assertFalse(vision.observeRgb(image,31,false).tap);
        }finally{image.release();}
    }
}
