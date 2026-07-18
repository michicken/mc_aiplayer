package io.github.zoyluo.aibot.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeBaseTest {
    @Test
    void storesTheGoalAndReasonSeparatelyWhileCountingRepeatedFailures() {
        KnowledgeBase.Lesson first = KnowledgeBase.nextLesson("采矿 x1\tno_reachable_ore", null, 40);
        KnowledgeBase.Lesson second = KnowledgeBase.nextLesson("采矿 x1\tno_reachable_ore", first, 80);

        assertEquals("采矿 x1", second.key());
        assertEquals("no_reachable_ore", second.reason());
        assertEquals(2, second.count());
        assertEquals(80, second.lastTick());
    }

    @Test
    void keepsLegacyGoalOnlyEventsReadable() {
        KnowledgeBase.Lesson lesson = KnowledgeBase.nextLesson("获取 铁镐 x1", null, 1);

        assertEquals("获取 铁镐 x1", lesson.key());
        assertEquals("", lesson.reason());
        assertEquals(1, lesson.count());
    }
}
