package io.github.zoyluo.aibot.brain;

/** Allows at most one model-authored TTS sentence until the conversation turn is reset. */
final class TurnSpeechGate {
    private boolean reserved;

    synchronized boolean reserve() {
        if (reserved) {
            return false;
        }
        reserved = true;
        return true;
    }

    synchronized void reset() {
        reserved = false;
    }

    synchronized boolean isReserved() {
        return reserved;
    }
}
