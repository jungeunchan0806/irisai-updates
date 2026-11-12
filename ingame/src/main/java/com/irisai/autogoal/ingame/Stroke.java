package com.irisai.autogoal.ingame;

/** Main-thread, finite native input sequence. Never sends Android/system touch events. */
public final class Stroke {
    public interface Sink { void send(int type, int x, int y, int pointer) throws Exception; }
    public interface Scheduler { void later(Runnable action, long delayMs); }
    public interface Permit { boolean valid(); }
    private final Sink sink;
    private final Scheduler scheduler;
    private Object active;
    private boolean down;
    public Stroke(Sink sink, Scheduler scheduler) { this.sink = sink; this.scheduler = scheduler; }
    public boolean busy() { return active != null; }

    public boolean start(int x, int y, int endX, int endY, int durationMs, Permit permit, Runnable released) {
        if (active != null || durationMs < 50 || durationMs > 180 || !permit.valid()) return false;
        if (x < 0 || y < 0 || endX < 0 || endY < 0) return false;
        Object token = new Object(); active = token;
        try {
            // Mark before calling: if the native bridge throws after receiving DOWN, cancel it.
            down = true; sink.send(0, x, y, 9);
            scheduler.later(() -> {
                if (active != token) return;
                if (!permit.valid()) { cancel(); return; }
                try { sink.send(2, endX, endY, 9); }
                catch (Exception | LinkageError e) { cancel(); }
            }, 24);
            scheduler.later(() -> {
                if (active != token) return;
                if (!permit.valid()) { cancel(); return; }
                try {
                    sink.send(1, endX, endY, 9);
                    down = false; active = null; released.run();
                } catch (Exception | LinkageError e) { cancel(); }
            }, durationMs);
            return true;
        } catch (Exception | LinkageError e) { cancel(); return false; }
    }
    public void cancel() {
        active = null;
        if (down) {
            down = false;
            // Matches GL2JNISurfaceView's ACTION_CANCEL -> nOnTouchEvent(3,0,0,0).
            // Never use UP for cancellation: releasing an aimed shot would fire it.
            try { sink.send(3, 0, 0, 0); } catch (Exception | LinkageError ignored) {}
        }
    }
}
