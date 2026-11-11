package com.conexa.starwars.auth.security;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

@Component
public class AuthRateLimiter {
    private static final int MAX_ATTEMPTS = 10;
    private static final int MAX_ADDRESSES = 10_000;
    private static final long WINDOW_NANOS = Duration.ofMinutes(1).toNanos();
    private final Map<String, Window> windows = new HashMap<>();
    private final LongSupplier ticker;

    public AuthRateLimiter() {
        this(System::nanoTime);
    }

    AuthRateLimiter(LongSupplier ticker) {
        this.ticker = ticker;
    }

    public synchronized boolean allow(String address) {
        long now = ticker.getAsLong();
        windows.entrySet().removeIf(entry -> now - entry.getValue().started >= WINDOW_NANOS);
        var window = windows.get(address);
        if (window == null) {
            if (windows.size() >= MAX_ADDRESSES) {
                return false;
            }
            window = new Window(now);
            windows.put(address, window);
        }
        if (window.attempts >= MAX_ATTEMPTS) {
            return false;
        }
        window.attempts++;
        return true;
    }

    private static class Window {
        private final long started;
        private int attempts;

        Window(long started) {
            this.started = started;
        }
    }
}
