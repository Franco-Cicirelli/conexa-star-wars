package com.conexa.starwars.telemetry;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationHandlerGroup;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TelemetryConfiguration {
    @Bean
    RestClientCustomizer safeClientObservations(
            ObservationRegistry registry, SafeClientObservationConvention convention) {
        return builder -> builder.observationRegistry(registry).observationConvention(convention);
    }

    @Bean
    ObservationHandlerGroup privacyBeforeTracing() {
        // Boot registers tracing as a group before ungrouped handlers, regardless of @Order.
        return new ObservationHandlerGroup() {
            @Override
            public Class<?> handlerType() {
                return TelemetryPrivacyHandler.class;
            }

            @Override
            public int compareTo(ObservationHandlerGroup other) {
                return other.handlerType() == TelemetryPrivacyHandler.class ? 0 : -1;
            }
        };
    }
}
