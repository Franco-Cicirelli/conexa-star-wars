package com.conexa.starwars.telemetry;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

@Component
class SafeServerObservationConvention extends DefaultServerRequestObservationConvention {
    @Override
    protected KeyValue uri(ServerRequestObservationContext context) {
        return KeyValue.of("uri", TelemetryRoutes.server(context.getCarrier().getRequestURI()));
    }

    @Override
    public String getContextualName(ServerRequestObservationContext context) {
        return "http " + uri(context).getValue();
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(ServerRequestObservationContext context) {
        return KeyValues.empty();
    }
}
