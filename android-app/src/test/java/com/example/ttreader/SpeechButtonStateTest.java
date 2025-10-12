package com.example.ttreader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpeechButtonStateTest {

    @Test
    public void calculatesIdleStateWhenVoiceUnavailable() {
        MainActivity.SpeechButtonState state =
                MainActivity.calculateSpeechButtonState(false, false, false);
        assertEquals(R.drawable.ic_radio_point, state.toggleIconRes);
        assertEquals(R.string.speech_toggle_content_start, state.toggleDescriptionRes);
        assertFalse(state.toggleEnabled);
        assertFalse(state.stopVisible);
        assertFalse(state.stopEnabled);
    }

    @Test
    public void enablesToggleWhenVoiceAvailable() {
        MainActivity.SpeechButtonState state =
                MainActivity.calculateSpeechButtonState(true, false, false);
        assertEquals(R.drawable.ic_radio_point, state.toggleIconRes);
        assertEquals(R.string.speech_toggle_content_start, state.toggleDescriptionRes);
        assertTrue(state.toggleEnabled);
        assertFalse(state.stopVisible);
        assertFalse(state.stopEnabled);
    }

    @Test
    public void reportsPauseStateWhileSpeaking() {
        MainActivity.SpeechButtonState state =
                MainActivity.calculateSpeechButtonState(true, true, true);
        assertEquals(R.drawable.ic_pause, state.toggleIconRes);
        assertEquals(R.string.speech_toggle_content_pause, state.toggleDescriptionRes);
        assertTrue(state.toggleEnabled);
        assertTrue(state.stopVisible);
        assertTrue(state.stopEnabled);
    }

    @Test
    public void reportsResumeStateWhenSessionActiveButNotSpeaking() {
        MainActivity.SpeechButtonState state =
                MainActivity.calculateSpeechButtonState(true, true, false);
        assertEquals(R.drawable.ic_play, state.toggleIconRes);
        assertEquals(R.string.speech_toggle_content_resume, state.toggleDescriptionRes);
        assertTrue(state.toggleEnabled);
        assertTrue(state.stopVisible);
        assertTrue(state.stopEnabled);
    }
}
