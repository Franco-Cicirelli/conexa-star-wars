package com.conexa.starwars.catalog.infrastructure.swapi;

import com.conexa.starwars.catalog.application.CatalogFailure;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
class SwapiCircuit {
    private final CircuitBreaker circuit;

    SwapiCircuit(CircuitBreaker circuit) {
        this.circuit = circuit;
    }

    <T> T call(Supplier<T> operation, Predicate<T> isAbsence) {
        if (!circuit.tryAcquirePermission()) {
            throw new CatalogFailure(CatalogFailure.Reason.CIRCUIT_OPEN, null);
        }
        long started = System.nanoTime();
        try {
            T result = operation.get();
            // Confirmed absence must not alter the failure ratio or recover a half-open circuit.
            if (isAbsence.test(result)) circuit.releasePermission();
            else circuit.onSuccess(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            return result;
        } catch (RuntimeException exception) {
            circuit.onError(System.nanoTime() - started, TimeUnit.NANOSECONDS, exception);
            throw exception;
        }
    }
}
