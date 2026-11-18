package com.irisai.bshack.update;

import android.app.Application;
import java.io.*;
import java.security.MessageDigest;

/** Match the exact game file even after BRF has installed in-memory hooks. */
public final class GameFileIdentity {
    private static final String GAME_SHA="ec549efe54b89a06c11a11e48f89bae109c954aa4871aad8611d5a850102ca74";
    public static boolean verify(Application app){return matches(new File(app.getApplicationInfo().nativeLibraryDir,"libg.so"),GAME_SHA);}
    private static boolean matches(File file,String expected){
        try(InputStream input=new FileInputStream(file)){
            MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int n;
            while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);
            StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            return expected.equals(hex.toString());
        }catch(Exception e){return false;}
    }
    private GameFileIdentity(){}
}
