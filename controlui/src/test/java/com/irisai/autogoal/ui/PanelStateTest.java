package com.irisai.autogoal.ui;
import org.junit.Test;
import static org.junit.Assert.*;
public final class PanelStateTest {
    @Test public void hiddenHudCanBeRecoveredWithoutRestart() {
        PanelState s = new PanelState(); s.hideAll();
        assertFalse(s.showHud()); assertTrue(s.canOpen()); s.toggleMenu();
        assertTrue(s.showMenu()); assertFalse(s.showHud());
        s.showAll(); s.toggleMenu(); assertTrue(s.showHud());
    }
    @Test public void autoHideSuppressesMenuHudAndCalibrationDuringRecording() {
        PanelState s = new PanelState(); s.streamer = true; s.menuOpen = true; s.calibrating = true;
        s.recording(true); assertFalse(s.showMenu()); assertFalse(s.showHud());
        assertFalse(s.showHint()); assertFalse(s.canOpen()); s.toggleMenu();
        s.recording(false); assertTrue(s.showMenu());
    }
    @Test public void manualHiddenChoiceSurvivesRecordingEnding() {
        PanelState s = new PanelState(); s.streamer = true; s.hideAll(); s.recording(true); s.recording(false);
        assertFalse(s.showHud()); assertTrue(s.canOpen());
    }
    @Test public void recordingDoesNotChangeUiUnlessModeEnabled() {
        PanelState s = new PanelState(); s.recording(true); assertTrue(s.showHud());
    }
    @Test public void backgroundNeverExposesUi() {
        PanelState s = new PanelState(); s.menuOpen = true; s.suspended = true;
        assertFalse(s.showMenu()); assertFalse(s.canOpen());
        s.suspended = false; assertTrue(s.showMenu());
    }
}
