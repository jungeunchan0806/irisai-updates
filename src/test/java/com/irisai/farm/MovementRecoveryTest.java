package com.irisai.farm;
import com.irisai.autogoal.Geometry.*;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

public class MovementRecoveryTest {
    @BeforeClass public static void load(){nu.pattern.OpenCV.loadLocally();}
    @Test public void stuckMovementEscapesAndResetClearsHistory(){
        Mat rgb=Mat.zeros(540,960,CvType.CV_8UC3);MovementRecovery r=new MovementRecovery();
        try{P player=new P(480,270),input=new P(0,-75),out=input;
            for(int i=0;i<9;i++)out=r.adjust(rgb,player,player,List.of(),List.of(),input,i*.2);
            assertEquals(1,r.recoveries);assertTrue(Math.abs(out.x)>50);
            r.reset();assertEquals(0,r.recoveries);
            out=r.adjust(rgb,player,player,List.of(),List.of(),input,2);assertEquals(-75,out.y,0);
        }finally{r.close();rgb.release();}
    }
    @Test public void cameraFollowingPlayerIsNotMistakenForStuck(){
        Mat base=new Mat(540,960,CvType.CV_8UC3);Core.randu(base,0,255);MovementRecovery r=new MovementRecovery();
        try{for(int i=0;i<10;i++){
            Mat transform=Mat.eye(2,3,CvType.CV_64F),frame=new Mat();transform.put(1,2,i*5.0);
            try{Imgproc.warpAffine(base,frame,transform,new Size(960,540));r.adjust(frame,new P(480,270),new P(480,250),List.of(),List.of(),new P(0,-75),i*.2);}finally{transform.release();frame.release();}
        }assertEquals(0,r.recoveries);}finally{r.close();base.release();}
    }
}
