package com.irisai.autogoal;
import org.junit.*;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import com.irisai.autogoal.Geometry.*;

public class BallVisionTest {
    private BallVision vision;
    @BeforeClass public static void nativeRuntime(){nu.pattern.OpenCV.loadLocally();}
    @Before public void setup()throws Exception{vision=new BallVision(name->Files.readAllBytes(Paths.get(System.getProperty("iris.assets"),name)));}
    @After public void teardown(){vision.close();}
    private Mat frame(String name)throws Exception{byte[] bytes;try(java.io.InputStream stream=getClass().getResourceAsStream("/fixtures/"+name+".jpg")){assertNotNull(stream);bytes=stream.readAllBytes();}MatOfByte encoded=new MatOfByte(bytes);Mat bgr=Imgcodecs.imdecode(encoded,Imgcodecs.IMREAD_COLOR),rgb=new Mat();encoded.release();Imgproc.cvtColor(bgr,rgb,Imgproc.COLOR_BGR2RGB);bgr.release();Imgproc.resize(rgb,rgb,new Size(960,540));return rgb;}
    private GoalPlanner.Observation observe(Mat rgb){return vision.observe(rgb,null,new ArrayList<>(),new ArrayList<>(),false,1640./1920,990./1080);}
    @Test public void menusAndBlankFramesDoNotAct()throws Exception{Mat blank=Mat.zeros(540,960,CvType.CV_8UC3);try{assertFalse(observe(blank).mode);}finally{blank.release();}Mat result=frame("result");try{assertFalse(observe(result).mode);}finally{result.release();}}
    @Test public void realKickoffFindsPlayerAndMode()throws Exception{Mat m=frame("kickoff");try{GoalPlanner.Observation o=observe(m);assertTrue(o.mode);assertNotNull(o.player);}finally{m.release();}}
    @Test public void visibleBallsAndGoalRecognized()throws Exception{for(String n:new String[]{"rolling","near_player","lit_ball"}){Mat m=frame(n);try{assertNotNull(n,observe(m).ball);}finally{m.release();}}Mat m=frame("opponent_goal");try{GoalPlanner.Observation o=observe(m);assertNotNull(o.goal);assertTrue(o.goal.y<200);}finally{m.release();}}
    @Test public void whiteCostumeIsNotBall()throws Exception{Mat m=frame("white_hat");try{assertNull(observe(m).ball);}finally{m.release();}}
    @Test public void matchEndBannerStopsEvenWithNetworkWarning()throws Exception{for(String n:new String[]{"last_goal","clock_expiry","last_goal_win","clock_expiry_loss"}){Mat m=frame(n);try{GoalPlanner.Observation o=observe(m);assertTrue(n,o.finished||!o.mode);}finally{m.release();}}}
    @Test public void matchHighlightDoesNotGenerateActions()throws Exception{Mat m=frame("highlight");try{GoalPlanner.Observation o=observe(m);assertTrue(o.finished||!o.mode);}finally{m.release();}}
}
