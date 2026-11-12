package com.irisai.diagnostic;
import org.junit.Test;
import static org.junit.Assert.*;

public class CrashSanitizerTest {
    @Test public void preservesFaultAndFramesButDropsOtherTags() {
        String raw="E/AndroidRuntime( 1): FATAL EXCEPTION: main\nE/AndroidRuntime( 1): java.lang.NoClassDefFoundError: org.opencv.X\nE/AndroidRuntime( 1): \tat com.irisai.X.start(X.java:3)\nD/Game( 1): unrelated account data";
        String result=CrashSanitizer.crashLines(raw);
        assertTrue(result.contains("NoClassDefFoundError: org.opencv.X"));
        assertTrue(result.contains("com.irisai.X.start"));assertFalse(result.contains("account data"));
    }
    @Test public void opaqueGameReportsDoNotLeaveTheDevice() {
        String result=CrashSanitizer.crashLines("E/AndroidRuntime( 1): nv.Q: !ENCRYPTEDPAYLOAD\nE/AndroidRuntime( 1): \tat nv.aJ.b(Unknown Source:46)");
        assertFalse(result.contains("ENCRYPTEDPAYLOAD"));assertTrue(result.contains("nv.Q"));assertTrue(result.contains("nv.aJ.b"));
    }
    @Test public void redactsCredentialsUrlsAndEmail() {
        String result=CrashSanitizer.redact("token=abc password=def https://example.com/private?a=x someone@example.com");
        for(String secret:new String[]{"abc","def","example.com","someone"})assertFalse(result.contains(secret));
    }
    @Test public void outputIsBounded() {
        String result=CrashSanitizer.crashLines(("E/AndroidRuntime( 1): \tat a.b.X.run(X.java:2)\n").repeat(5000));
        assertEquals(70,result.split("\n").length);
    }
}
