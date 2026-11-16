package com.stonewu.fusion.service.ai.run;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.stonewu.fusion.repository.ai.AgentEventRepository;
import com.stonewu.fusion.repository.ai.AgentEventRepository.ReplaySnapshot;
import com.stonewu.fusion.service.ai.AiStreamRedisService;
import com.stonewu.fusion.service.ai.agentscope.runtime.AgentRuntimeSchedulers;
import com.stonewu.fusion.service.ai.run.model.AgentEventEnvelope;
import com.stonewu.fusion.service.ai.run.model.CommittedAgentEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRealtimeDeliveryTests {
    @Test
    void redisSubscriptionHandshakeCannotHoldBackCommittedHistory() {
        var repository = mock(AgentEventRepository.class);
        var signals = mock(AgentRunRedisSignalService.class);
        var schedulers = mock(AgentRuntimeSchedulers.class);
        when(schedulers.journal()).thenReturn(Schedulers.immediate());
        when(signals.wakeupsWhenSubscribed("run")).thenReturn(Mono.never());
        when(repository.loadReplaySnapshot("run")).thenReturn(new ReplaySnapshot(1, 1L));
        var envelope = new AgentEventEnvelope("terminal", "AGENT_END", "main", null, null, null, null, null,
                "DONE", JsonNodeFactory.instance.objectNode().put("outputType", "DONE"), Instant.now());
        var terminal = new CommittedAgentEvent(1, "run", 1, envelope, Instant.now());
        when(repository.loadReplayPage("run", 0, 1, 256)).thenReturn(List.of(terminal));
        StepVerifier.create(new AgentRunReplayService(repository, signals, schedulers).replayThenLive("run", 0))
                .expectNext(terminal).expectComplete().verify(Duration.ofSeconds(1));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void longLiveReplayDeliversTerminalWithBoundedDemandAndCancellation(boolean childReplay) {
        int liveCycles = 4096;
        var repository = mock(AgentEventRepository.class);
        var signals = mock(AgentRunRedisSignalService.class);
        var schedulers = mock(AgentRuntimeSchedulers.class);
        var wakeups = Sinks.many().unicast().<Long>onBackpressureBuffer();
        var snapshotReads = new AtomicInteger();
        var stack = StackWalker.getInstance();
        when(schedulers.journal()).thenReturn(Schedulers.immediate());
        when(signals.wakeupsWhenSubscribed("run")).thenReturn(Mono.just(wakeups.asFlux()));
        when(repository.loadReplaySnapshot("run")).thenAnswer(invocation -> {
            int read = snapshotReads.incrementAndGet();
            if (read > liveCycles) {
                return new ReplaySnapshot(2, 2L);
            }
            if (read % 16 == 0 && stack.walk(frames -> frames.limit(512).count()) == 512) {
                throw new IllegalStateException("Replay retained subscriptions from earlier cycles");
            }
            wakeups.emitNext(1L, Sinks.EmitFailureHandler.FAIL_FAST);
            return new ReplaySnapshot(1, null);
        });
        var initial = event(1, "CONTENT");
        var terminal = event(2, "DONE");
        when(repository.loadReplayPage("run", 0, 1, AgentRunReplayService.REPLAY_PAGE_SIZE))
                .thenReturn(List.of(initial));
        when(repository.loadReplayPage("run", 1, 2, AgentRunReplayService.REPLAY_PAGE_SIZE))
                .thenReturn(List.of(terminal));

        var replay = new AgentRunReplayService(repository, signals, schedulers).replayThenLive("run", 0);
        StepVerifier.create(childReplay ? replay.takeUntil(terminal::equals) : replay, 1)
                .expectNext(initial)
                .thenRequest(1)
                .expectNext(terminal)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void timerCanWakeConsecutiveReplayCyclesWithoutRedisSignals() {
        var repository = mock(AgentEventRepository.class);
        var signals = mock(AgentRunRedisSignalService.class);
        var schedulers = mock(AgentRuntimeSchedulers.class);
        when(schedulers.journal()).thenReturn(Schedulers.immediate());
        when(signals.wakeupsWhenSubscribed("run")).thenReturn(Mono.never());
        var waiting = new ReplaySnapshot(0, null);
        when(repository.loadReplaySnapshot("run")).thenReturn(waiting, waiting, new ReplaySnapshot(1, 1L));
        var terminal = event(1, "DONE");
        when(repository.loadReplayPage("run", 0, 1, AgentRunReplayService.REPLAY_PAGE_SIZE))
                .thenReturn(List.of(terminal));

        var replay = new AgentRunReplayService(repository, signals, schedulers);
        StepVerifier.withVirtualTime(() -> replay.replayThenLive("run", 0))
                .thenAwait(AgentRunReplayService.POLL_INTERVAL.multipliedBy(2))
                .expectNext(terminal)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    private CommittedAgentEvent event(long sequence, String outputType) {
        var now = Instant.now();
        var envelope = new AgentEventEnvelope("event-" + sequence, "event", "main", null, null, null, null, null,
                outputType, JsonNodeFactory.instance.objectNode().put("outputType", outputType), now);
        return new CommittedAgentEvent(sequence, "run", sequence, envelope, now);
    }

    @Test
    void notificationReceiptDoesNotWaitForRedisAndCoalescesByRun() {
        var redis = mock(AiStreamRedisService.class);
        when(redis.publishRunWakeup(anyString(), anyLong())).thenReturn(Mono.never());
        var signals = new AgentRunRedisSignalService(redis);
        try {
            signals.notifyCommitted("one", 1);
            signals.notifyCommitted("one", 5);
            signals.notifyCommitted("two", 2);
            verify(redis, never()).publishRunWakeup(anyString(), anyLong());
            signals.flushCommitted();
            verify(redis).publishRunWakeup("one", 5);
            verify(redis).publishRunWakeup("two", 2);
            // 上一批 Redis 永不响应，生产者仍可继续接收新的已提交序号。
            signals.notifyCommitted("one", 6);
            signals.notifyCommitted("three", 3);
        } finally {
            signals.closeNotifications();
        }
    }
}
