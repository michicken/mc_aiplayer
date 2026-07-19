package io.github.zoyluo.aibot.brain;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.observe.BotProfiler;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public final class AsyncDecisionExecutor {
    private final DeepSeekApiClient apiClient;
    // A single livestream turn may fan out to five read-only Step advisors plus the tool-writing
    // main lane. The provider permits high concurrency, so do not serialize them locally.
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public AsyncDecisionExecutor(DeepSeekApiClient apiClient) {
        this.apiClient = apiClient;
    }

    public void submit(AIPlayerEntity bot,
                       List<ChatMessage> historySnapshot,
                       List<ToolDefinition> tools,
                       BiConsumer<AIPlayerEntity, ChatResponse> onResponse,
                       BiConsumer<AIPlayerEntity, Throwable> onError) {
        executor.submit(() -> {
            long started = System.nanoTime();
            try {
                ChatResponse response = apiClient.chat(historySnapshot, tools);
                long elapsed = System.nanoTime() - started;
                bot.getServer().execute(() -> onResponse.accept(bot, response));
                BotProfiler.INSTANCE.record(bot.getUuid(), bot.getGameProfile().getName(), "brain_latency", elapsed);
            } catch (Exception exception) {
                long elapsed = System.nanoTime() - started;
                BotProfiler.INSTANCE.record(bot.getUuid(), bot.getGameProfile().getName(), "brain_latency_error", elapsed);
                bot.getServer().execute(() -> onError.accept(bot, exception));
            }
        });
    }

    /** Runs read-only Step lanes concurrently and returns partial results even when one lane fails. */
    public void submitAdvisors(AIPlayerEntity bot,
                               List<ParallelBrainAdvisors.LaneRequest> requests,
                               BiConsumer<AIPlayerEntity, List<ParallelBrainAdvisors.LaneResult>> onComplete) {
        if (requests == null || requests.isEmpty()) {
            bot.getServer().execute(() -> onComplete.accept(bot, List.of()));
            return;
        }
        List<CompletableFuture<ParallelBrainAdvisors.LaneResult>> futures = new ArrayList<>(requests.size());
        for (ParallelBrainAdvisors.LaneRequest request : requests) {
            CompletableFuture<ParallelBrainAdvisors.LaneResult> future = CompletableFuture.supplyAsync(() -> {
                long started = System.nanoTime();
                try {
                    ChatResponse response = apiClient.chat(
                            request.history(),
                            List.of(),
                            ParallelBrainAdvisors.ADVISOR_MAX_TOKENS,
                            0.2D,
                            "advisor_" + request.lane());
                    BotProfiler.INSTANCE.record(bot.getUuid(), bot.getGameProfile().getName(),
                            "brain_advisor_" + request.lane(), System.nanoTime() - started);
                    return new ParallelBrainAdvisors.LaneResult(
                            request.lane(),
                            response.content() == null ? "" : response.content(),
                            "",
                            response.promptTokens(),
                            response.completionTokens());
                } catch (Exception exception) {
                    BotProfiler.INSTANCE.record(bot.getUuid(), bot.getGameProfile().getName(),
                            "brain_advisor_" + request.lane() + "_error", System.nanoTime() - started);
                    return ParallelBrainAdvisors.LaneResult.failure(request.lane(), exception);
                }
            }, executor).completeOnTimeout(
                    ParallelBrainAdvisors.LaneResult.timeout(request.lane()),
                    ParallelBrainAdvisors.ADVISOR_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
            futures.add(future);
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenRun(() -> {
            List<ParallelBrainAdvisors.LaneResult> results = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();
            bot.getServer().execute(() -> onComplete.accept(bot, results));
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
