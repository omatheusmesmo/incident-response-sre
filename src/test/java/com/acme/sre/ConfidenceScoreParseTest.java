package com.acme.sre;

import com.acme.sre.domain.diagnosis.ConfidenceScore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The confidence scorer agent is instructed to return a bare number, but LLMs frequently
 * wrap it in prose ("Confidence: 0.85 because ..."). {@link ConfidenceScore#parse} must be
 * lenient so the @LoopAgent exit condition and the persisted score stay correct.
 */
class ConfidenceScoreParseTest {

    @Test
    void parsesBareNumber() {
        ConfidenceScore high = ConfidenceScore.parse("0.85");
        assertEquals(0.85, high.value(), 1e-9);
        assertTrue(high.isSufficient());

        ConfidenceScore low = ConfidenceScore.parse("0.62");
        assertEquals(0.62, low.value(), 1e-9);
        assertFalse(low.isSufficient());
    }

    @Test
    void extractsNumberEmbeddedInProse() {
        ConfidenceScore score = ConfidenceScore.parse("Confidence is 0.92 given the clear root cause");
        assertEquals(0.92, score.value(), 1e-9);
        assertTrue(score.isSufficient());
        assertTrue(score.reasoning().contains("Confidence is 0.92"));
    }

    @Test
    void blankOrNullFallsBackToNeutralScore() {
        assertEquals(0.5, ConfidenceScore.parse(null).value(), 1e-9);
        assertEquals(0.5, ConfidenceScore.parse("   ").value(), 1e-9);
    }

    @Test
    void nonNumericGarbageFallsBackToNeutralScore() {
        ConfidenceScore score = ConfidenceScore.parse("high confidence");
        assertEquals(0.5, score.value(), 1e-9);
        assertEquals("high confidence", score.reasoning());
    }
}
