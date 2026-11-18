/* Isolated Android JNI load boundary, not a game/BRF implementation. */
#include <jni.h>
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm,void *reserved) {
    JNIEnv *env;
    if((*vm)->GetEnv(vm,(void **)&env,JNI_VERSION_1_6)!=JNI_OK)return JNI_ERR;
    jclass game=(*env)->FindClass(env,"com/supercell/titan/GameApp");
    if(!game)return JNI_ERR;
    jclass system=(*env)->FindClass(env,"java/lang/System");
    jmethodID set=(*env)->GetStaticMethodID(env,system,"setProperty","(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    if(!set)return JNI_ERR;
    jstring key=(*env)->NewStringUTF(env,"iris.fixture.brf.jni"),value=(*env)->NewStringUTF(env,"yes");
    (*env)->CallStaticObjectMethod(env,system,set,key,value);
    return (*env)->ExceptionCheck(env)?JNI_ERR:JNI_VERSION_1_6;
}
