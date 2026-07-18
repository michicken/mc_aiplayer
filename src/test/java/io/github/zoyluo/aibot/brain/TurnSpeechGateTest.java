package io.github.zoyluo.aibot.brain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnSpeechGateTest {
    @Test
    void permitsOnlyOneModelSentenceUntilReset() {
        TurnSpeechGate gate = new TurnSpeechGate();

        assertTrue(gate.reserve());
        assertFalse(gate.reserve());
        assertFalse(gate.reserve());

        gate.reset();

        assertTrue(gate.reserve());
        assertFalse(gate.reserve());
    }
}
