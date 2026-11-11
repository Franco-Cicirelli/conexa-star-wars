package com.conexa.starwars.catalog.infrastructure.swapi;

import com.conexa.starwars.catalog.application.CatalogFailure;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class SwapiCircuitConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(SwapiCircuitConfiguration.class);

    @Bean
    CircuitBreaker swapiCircuitBreaker(
            MeterRegistry meters,
            @Value("${swapi.circuit.open-wait:10s}") Duration openWait,
            Clock clock) {
        var configuration =
                CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(openWait)
                        .permittedNumberOfCallsInHalfOpenState(2)
                        .automaticTransitionFromOpenToHalfOpenEnabled(false)
                        .clock(clock)
                        .ignoreException(error -> !isAvailabilityFailure(error))
                        .build();
        var registry = CircuitBreakerRegistry.of(configuration);
        var circuit = registry.circuitBreaker("swapi");
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        circuit.getEventPublisher()
                .onStateTransition(
                        event ->
                                LOG.atInfo()
                                        .addKeyValue("event", "swapi.circuit.transition")
                                        .addKeyValue("circuit", "swapi")
                                        .addKeyValue(
                                                "fromState",
                                                event.getStateTransition().getFromState().name())
                                        .addKeyValue(
                                                "toState",
                                                event.getStateTransition().getToState().name())
                                        .log("SWAPI circuit state changed"));
        return circuit;
    }

    private static boolean isAvailabilityFailure(Throwable error) {
        return error instanceof CatalogFailure failure
                && (failure.reason() == CatalogFailure.Reason.UNAVAILABLE
                        || failure.reason() == CatalogFailure.Reason.TIMEOUT);
    }
}
