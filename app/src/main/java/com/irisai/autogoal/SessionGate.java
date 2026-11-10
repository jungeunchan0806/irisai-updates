package com.irisai.autogoal;

/** Every permission/foreground/toggle change invalidates in-flight frame decisions. */
public final class SessionGate {
    private boolean enabled, game, capture, access;
    private long generation;
    public synchronized long generation() { return generation; }
    public synchronized boolean enabled() { return enabled; }
    public synchronized void setEnabled(boolean value) { if(enabled!=value) {enabled=value;generation++;} }
    public synchronized void setGame(boolean value) { if(game!=value) {game=value;generation++;} }
    public synchronized void setCapture(boolean value) { if(capture!=value) {capture=value;generation++;} }
    public synchronized void setAccess(boolean value) { if(access!=value) {access=value;generation++;} }
    public synchronized void invalidate() { generation++; }
    public synchronized boolean ready() { return enabled && game && capture && access; }
    public synchronized boolean allows(long frameGeneration, long frameAt, long now) {
        return ready() && frameGeneration==generation && now>=frameAt && now-frameAt<=700;
    }
}
