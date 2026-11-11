package com.conexa.starwars.catalog.infrastructure.swapi;

import static org.assertj.core.api.Assertions.*;

import com.conexa.starwars.catalog.application.CatalogFailure;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SwapiCircuitTest {
    private final CircuitBreaker breaker =
            new SwapiCircuitConfiguration()
                    .swapiCircuitBreaker(
                            new SimpleMeterRegistry(),
                            Duration.ofSeconds(10),
                            java.time.Clock.systemUTC());
    private final SwapiCircuit circuit = new SwapiCircuit(breaker);

    @Test
    void demoConfigurationIsExplicit() {
        var config = breaker.getCircuitBreakerConfig();
        assertThat(config.getSlidingWindowSize()).isEqualTo(10);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
        assertThat(config.getFailureRateThreshold()).isEqualTo(50);
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(2);
        assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(10_000);
        assertThat(config.isAutomaticTransitionFromOpenToHalfOpenEnabled()).isFalse();
    }

    @Test
    void onlyAvailabilityAndTimeoutFailuresAreRecorded() {
        fail(CatalogFailure.Reason.INVALID_RESPONSE);
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
        fail(CatalogFailure.Reason.UNAVAILABLE);
        fail(CatalogFailure.Reason.TIMEOUT);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void ignoredAbsenceDoesNotSatisfyHalfOpenProbes() {
        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();
        for (int i = 0; i < 5; i++) circuit.call(() -> "absent", result -> true);
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        circuit.call(() -> "valid", result -> false);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        circuit.call(() -> "valid", result -> false);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void halfOpenAllowsOnlyTwoConcurrentProbes() {
        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();
        assertThat(breaker.tryAcquirePermission()).isTrue();
        assertThat(breaker.tryAcquirePermission()).isTrue();
        assertThatThrownBy(() -> circuit.call(() -> "must not execute", result -> false))
                .isInstanceOfSatisfying(
                        CatalogFailure.class,
                        failure ->
                                assertThat(failure.reason())
                                        .isEqualTo(CatalogFailure.Reason.CIRCUIT_OPEN));
    }

    @Test
    void thresholdNeedsMinimumSampleAndUsesRollingWindow() {
        for (int i = 0; i < 4; i++) fail(CatalogFailure.Reason.UNAVAILABLE);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        circuit.call(() -> "valid", result -> false);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        breaker.reset();
        for (int i = 0; i < 10; i++) circuit.call(() -> "valid", result -> false);
        for (int i = 0; i < 4; i++) fail(CatalogFailure.Reason.UNAVAILABLE);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        fail(CatalogFailure.Reason.UNAVAILABLE);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    private void fail(CatalogFailure.Reason reason) {
        assertThatThrownBy(
                        () ->
                                circuit.call(
                                        () -> {
                                            throw new CatalogFailure(reason, null);
                                        },
                                        result -> false))
                .isInstanceOf(CatalogFailure.class);
    }
}
