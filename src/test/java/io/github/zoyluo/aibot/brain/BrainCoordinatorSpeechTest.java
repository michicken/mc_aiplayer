package io.github.zoyluo.aibot.brain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrainCoordinatorSpeechTest {
    @Test
    void removesMechanicalPrefixAndFormattingWithoutChangingMeaning() {
        assertEquals("我去挖铁。", BrainCoordinator.polishSpeech("收到，**我将去挖铁。**"));
        assertEquals("我在找安全入口。", BrainCoordinator.polishSpeech("正在为你找安全入口。"));
    }

    @Test
    void keepsSpeechShortAtNaturalSentenceBoundary() {
        String longSpeech = "我已经看清前面被水堵住了，先沿着岸边找路。后面这句不该念出来，因为直播口播需要保持短一点。";
        String polished = BrainCoordinator.polishSpeech(longSpeech);

        assertTrue(polished.length() <= 70);
        assertEquals("我已经看清前面被水堵住了，先沿着岸边找路。", polished);
    }
}
