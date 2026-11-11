package com.conexa.starwars.telemetry;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TelemetryPrivacyHandler implements ObservationHandler<Observation.Context> {
    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ServerRequestObservationContext
                || context instanceof ClientRequestObservationContext;
    }

    @Override
    public void onError(Observation.Context context) {
        // Observation errors may embed URLs, response bodies or rejected password values.
        // Sanitize telemetry only; the original exception still follows the normal error handlers.
        context.setError(new SanitizedHttpFailure());
    }

    private static class SanitizedHttpFailure extends RuntimeException {
        SanitizedHttpFailure() {
            super("HTTP operation failed", null, false, false);
        }
    }
}
