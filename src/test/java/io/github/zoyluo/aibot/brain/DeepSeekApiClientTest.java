package io.github.zoyluo.aibot.brain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekApiClientTest {
    @Test
    void parsesStandardOpenAiToolCall() throws DeepSeekApiException {
        ChatResponse response = DeepSeekApiClient.parseResponse("""
                {"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"smart_navigate","arguments":"{\\"mode\\":\\"go\\"}"}}]}}]}
                """);

        assertTrue(response.wantsToolCalls());
        assertEquals("tool_calls", response.finishReason());
        assertEquals(1, response.toolCalls().size());
        assertEquals("call-1", response.toolCalls().getFirst().id());
        assertEquals("smart_navigate", response.toolCalls().getFirst().name());
        assertEquals("go", response.toolCalls().getFirst().parsedArguments().get("mode").getAsString());
    }

    @Test
    void acceptsFlatToolCallWithObjectArgumentsAndMissingId() throws DeepSeekApiException {
        ChatResponse response = DeepSeekApiClient.parseResponse("""
                {"choices":[{"message":{"tool_calls":[{"name":"finish","arguments":{"summary":"我先去忙"}}]}}]}
                """);

        assertTrue(response.wantsToolCalls());
        assertEquals("tool_calls", response.finishReason());
        assertEquals("step_call_0", response.toolCalls().getFirst().id());
        assertEquals("finish", response.toolCalls().getFirst().name());
        assertEquals("我先去忙", response.toolCalls().getFirst().parsedArguments().get("summary").getAsString());
    }
}
