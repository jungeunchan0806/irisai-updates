package com.irisai.bshack;
import android.app.Application;
import java.io.File;

/** Run BRF's JNI initialization before installing the Iris engine hooks. */
public final class NativeFeatures {
    private static boolean attempted;
    private static String error="";
    public static synchronized String load(Application app){
        if(!attempted){attempted=true;
            try{System.load(new File(app.getApplicationInfo().nativeLibraryDir,"libbrf.so").getAbsolutePath());}
            catch(LinkageError|RuntimeException e){error=e.toString();}
        }
        return error;
    }
    private NativeFeatures(){}
}
