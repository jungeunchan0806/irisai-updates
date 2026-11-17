package com.irisai.autogoal.ui;

/** Visibility policy is independent of game/model state. */
public final class PanelState {
    public boolean english, hidden, menuOpen, streamer, recording, suspended, calibrating;
    public boolean blocked() { return suspended || (streamer && recording); }
    public boolean showMenu() { return menuOpen && !blocked() && !calibrating; }
    public boolean showHud() { return !hidden && !menuOpen && !calibrating && !blocked(); }
    public boolean showHint() { return calibrating && !hidden && !blocked(); }
    public boolean canOpen() { return !blocked() && !calibrating; }
    public void toggleMenu() { if (canOpen()) menuOpen = !menuOpen; }
    public void hideAll() { hidden = true; menuOpen = false; calibrating = false; }
    public void showAll() { hidden = false; }
    public void recording(boolean active) { recording = active; if (streamer && active) calibrating = false; }
}
