package com.conexa.starwars.telemetry;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;
import org.springframework.stereotype.Component;

@Component
class SafeClientObservationConvention extends DefaultClientRequestObservationConvention {
    @Override
    protected KeyValue uri(ClientRequestObservationContext context) {
        return KeyValue.of("uri", TelemetryRoutes.client(context.getCarrier().getURI().getPath()));
    }

    @Override
    public String getContextualName(ClientRequestObservationContext context) {
        return "swapi " + uri(context).getValue();
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(ClientRequestObservationContext context) {
        return KeyValues.empty();
    }
}
