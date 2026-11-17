package com.irisai.autogoal;

import org.junit.*;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.io.InputStream;
import java.util.*;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/** Real supplied ONNX weights + recorded Iris frames on host CPU, not a live game. */
public class DetectorIntegrationTest {
    @BeforeClass public static void loadOpenCv(){nu.pattern.OpenCV.loadLocally();}
    private Mat frame(String name)throws Exception{
        byte[] data;try(InputStream in=getClass().getResourceAsStream("/fixtures/"+name+".jpg")){assertNotNull(in);data=in.readAllBytes();}
        MatOfByte encoded=new MatOfByte(data); Mat rgb=Imgcodecs.imdecode(encoded,Imgcodecs.IMREAD_COLOR);encoded.release();
        Imgproc.cvtColor(rgb,rgb,Imgproc.COLOR_BGR2RGB);Imgproc.resize(rgb,rgb,new Size(960,540));return rgb;
    }
    private String model(String name){return Paths.get(System.getProperty("iris.assets")).resolveSibling("models").resolve(name).toString();}
    @Test public void suppliedEntityModelFindsPlayerInRecordedKickoff()throws Exception{
        Mat image=frame("kickoff");
        try(YoloDetector detector=new YoloDetector(model("mainInGameModel.onnx"))){
            List<YoloDetector.Detection> detections=detector.detect(image,.4f,12);
            assertTrue("Player class absent",detections.stream().anyMatch(d->d.kind==2));
            for(YoloDetector.Detection d:detections){
                assertTrue(d.kind>=0&&d.kind<=2);assertTrue(d.box.x2>d.box.x1);assertTrue(d.box.y2>d.box.y1);
                assertTrue(d.box.x1<960&&d.box.x2>0&&d.box.y1<540&&d.box.y2>0);
            }
        }finally{image.release();}
    }
    @Test public void suppliedWallModelSeesRecordedGoalObstacles()throws Exception{
        Mat image=frame("opponent_goal");
        try(YoloDetector detector=new YoloDetector(model("tileDetector.onnx"))){
            List<YoloDetector.Detection> detections=detector.detect(image,.55f,100);
            long walls=detections.stream().filter(d->d.kind==0).count();
            assertTrue("Expected several visible walls, found "+walls,walls>=5);
        }finally{image.release();}
    }
    @Test public void reusedTensorUpdatesPixelsAndResetsLetterboxAfterDifferentFrame()throws Exception{
        Mat game=frame("kickoff"),blank=new Mat(640,320,CvType.CV_8UC3,new Scalar(0,0,0));
        try(YoloDetector detector=new YoloDetector(model("mainInGameModel.onnx"))){
            List<YoloDetector.Detection> first=detector.detect(game,.4f,12);
            assertTrue(first.stream().anyMatch(d->d.kind==2));
            assertTrue("A reused tensor must see the new blank pixels",detector.detect(blank,.4f,12).isEmpty());
            List<YoloDetector.Detection> again=detector.detect(game,.4f,12);
            assertEquals(first.size(),again.size());
            for(int i=0;i<first.size();i++){
                assertEquals(first.get(i).kind,again.get(i).kind);
                assertEquals(first.get(i).score,again.get(i).score,1e-5);
                assertEquals(first.get(i).box.x1,again.get(i).box.x1,1e-4);
                assertEquals(first.get(i).box.y1,again.get(i).box.y1,1e-4);
            }
        }finally{game.release();blank.release();}
    }
}
