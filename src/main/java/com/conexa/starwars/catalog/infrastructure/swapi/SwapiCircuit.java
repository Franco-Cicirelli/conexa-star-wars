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

    /**
     * Guards one physical provider operation, including envelope validation. Confirmed absence
     * releases the permit instead of recording success, which would incorrectly complete HALF_OPEN
     * recovery. Error eligibility is configured by SwapiCircuitConfiguration; ignored errors still
     * propagate to the catalog's HTTP error mapping. Cache hits and local rejections never call
     * here.
     *
     * @param operation external work with transport translation and contract validation
     * @param isAbsence predicate for verified absence only, not arbitrary empty/error envelopes
     * @return the provider result without changing its absence semantics
     * @throws CatalogFailure if admission is denied or the provider operation fails
     */
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
