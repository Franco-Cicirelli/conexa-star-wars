package com.conexa.starwars.telemetry;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class HttpObservationLoggingHandler implements ObservationHandler<Observation.Context> {
    private static final Logger LOG = LoggerFactory.getLogger(HttpObservationLoggingHandler.class);

    private record Start(long nanos) {}

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ServerRequestObservationContext
                || context instanceof ClientRequestObservationContext;
    }

    @Override
    public void onStart(Observation.Context context) {
        context.put(Start.class, new Start(System.nanoTime()));
    }

    @Override
    public void onStop(Observation.Context context) {
        Start start = context.get(Start.class);
        TracingContext tracing = context.get(TracingContext.class);
        var span = tracing == null ? null : tracing.getSpan();
        var previousMdc = MDC.getCopyOfContextMap();
        try {
            MDC.put("traceId", span == null ? "" : span.context().traceId());
            MDC.put("spanId", span == null ? "" : span.context().spanId());
            LOG.atInfo()
                    .addKeyValue(
                            "event",
                            context instanceof ServerRequestObservationContext
                                    ? "http.server.completed"
                                    : "swapi.client.completed")
                    .addKeyValue("route", value(context, "uri"))
                    .addKeyValue("method", value(context, "method"))
                    .addKeyValue("status", value(context, "status"))
                    .addKeyValue("outcome", value(context, "outcome"))
                    .addKeyValue(
                            "durationMs",
                            start == null ? 0 : (System.nanoTime() - start.nanos()) / 1_000_000.0)
                    .log("HTTP operation completed");
        } finally {
            MDC.setContextMap(previousMdc == null ? Map.of() : previousMdc);
        }
    }

    private String value(Observation.Context context, String key) {
        var value = context.getLowCardinalityKeyValue(key);
        return value == null ? "UNKNOWN" : value.getValue();
    }
}
