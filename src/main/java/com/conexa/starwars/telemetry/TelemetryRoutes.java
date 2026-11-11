package com.conexa.starwars.telemetry;

import java.util.Set;

final class TelemetryRoutes {
    private static final Set<String> API_ROUTES =
            Set.of(
                    "/api/v1",
                    "/api/v1/people",
                    "/api/v1/films",
                    "/api/v1/starships",
                    "/api/v1/vehicles",
                    "/api/v1/auth/login",
                    "/api/v1/auth/register",
                    "/actuator/health",
                    "/actuator/health/liveness",
                    "/actuator/health/readiness",
                    "/actuator/prometheus",
                    "/actuator/metrics",
                    "/livez",
                    "/readyz");

    private TelemetryRoutes() {}

    static String server(String path) {
        if (API_ROUTES.contains(path)) return path;
        if (path.startsWith("/actuator/metrics/")) return "/actuator/metrics/{name}";
        return "UNKNOWN";
    }

    static String client(String path) {
        if (path == null) return "UNKNOWN";
        String[] segments = path.split("/");
        for (int index = 0; index < segments.length; index++) {
            if (Set.of("people", "films", "starships", "vehicles").contains(segments[index])) {
                if (index == segments.length - 1) return "/" + segments[index];
                if (index == segments.length - 2) return "/" + segments[index] + "/{id}";
            }
        }
        return "UNKNOWN";
    }
}
