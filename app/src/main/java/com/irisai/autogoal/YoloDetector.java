package com.irisai.autogoal;

import ai.onnxruntime.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.*;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import com.irisai.autogoal.Geometry.Box;

/** CPU ONNX detector using the original Iris RGB/top-left letterbox convention. */
public final class YoloDetector implements AutoCloseable {
    public static final class Detection {public final Box box;public final int kind;public final float score;Detection(Box b,int k,float s){box=b;kind=k;score=s;}}
    private final OrtEnvironment env=OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final String input;
    // One worker owns each detector. Keep the 640x640 tensor and image buffers
    // between frames instead of allocating/copying a new native tensor each time.
    private final float[] values = new float[3 * 640 * 640];
    private final FloatBuffer inputBuffer = ByteBuffer.allocateDirect(values.length * Float.BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final OnnxTensor inputTensor;
    private final Mat resized = new Mat();
    private byte[] pixels = new byte[0];
    private boolean closed;
    public YoloDetector(String model) throws OrtException {
        try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){
            options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1);
            session=env.createSession(model,options);input=session.getInputNames().iterator().next();
        }
        try { inputTensor=OnnxTensor.createTensor(env,inputBuffer,new long[]{1,3,640,640}); }
        catch (OrtException | RuntimeException e) { session.close(); resized.release(); throw e; }
    }
    public List<Detection> detect(Mat rgb,float threshold,int max) throws OrtException {
        if(closed)throw new IllegalStateException("Detector is closed");
        if(rgb.empty()||rgb.channels()!=3||max<1)throw new IllegalArgumentException("Expected a nonempty RGB frame and positive limit");
        int w=rgb.cols(),h=rgb.rows(),nw=(int)(w*Math.min(640./w,640./h)),nh=(int)(h*Math.min(640./w,640./h));
        Imgproc.resize(rgb,resized,new Size(nw,nh));
        if(pixels.length!=nw*nh*3)pixels=new byte[nw*nh*3];
        resized.get(0,0,pixels);Arrays.fill(values,128f/255f);
        for(int y=0;y<nh;y++)for(int x=0;x<nw;x++)for(int c=0;c<3;c++)values[c*640*640+y*640+x]=(pixels[(y*nw+x)*3+c]&255)/255f;
        inputBuffer.clear(); inputBuffer.put(values); inputBuffer.rewind();
        ArrayList<Detection> found=new ArrayList<>();
        try(OrtSession.Result result=session.run(Collections.singletonMap(input,inputTensor))){
            float[][] out=((float[][][])result.get(0).getValue())[0];
            if(out.length<5||out.length>256)throw new IllegalStateException("Unexpected model output");
            for(int i=0;i<out[0].length;i++){int kind=0;float score=0;for(int c=4;c<out.length;c++)if(out[c][i]>score){score=out[c][i];kind=c-4;}
                if(score<threshold)continue;double x=out[0][i],y=out[1][i],bw=out[2][i],bh=out[3][i];
                found.add(new Detection(new Box((x-bw/2)*w/nw,(y-bh/2)*h/nh,(x+bw/2)*w/nw,(y+bh/2)*h/nh),kind,score));}
        }
        found.sort((a,b)->Float.compare(b.score,a.score));ArrayList<Detection> kept=new ArrayList<>();
        for(Detection d:found){boolean overlap=false;for(Detection k:kept)if(iou(d.box,k.box)>.6){overlap=true;break;}if(!overlap)kept.add(d);if(kept.size()>=max)break;}return kept;
    }
    private static double iou(Box a,Box b){double inter=Math.max(0,Math.min(a.x2,b.x2)-Math.max(a.x1,b.x1))*Math.max(0,Math.min(a.y2,b.y2)-Math.max(a.y1,b.y1));double union=(a.x2-a.x1)*(a.y2-a.y1)+(b.x2-b.x1)*(b.y2-b.y1)-inter;return inter/Math.max(1e-6,union);}
    public void close() throws OrtException {
        if(closed)return;closed=true;
        try{session.close();}finally{inputTensor.close();resized.release();pixels=new byte[0];}
    }
}
