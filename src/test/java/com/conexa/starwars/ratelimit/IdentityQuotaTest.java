package com.conexa.starwars.ratelimit;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class IdentityQuotaTest {
    @Test
    void exhaustsImmediatelyAndSeparatesIdentities() {
        var quota = new IdentityQuota(2, Duration.ofMinutes(1), 10, System::nanoTime);
        quota.acquire("alice");
        quota.acquire("alice");
        assertThatThrownBy(() -> quota.acquire("alice"))
                .isInstanceOfSatisfying(
                        QuotaExceededException.class,
                        exception -> assertThat(exception.retryAfterSeconds()).isEqualTo(60));
        quota.acquire("bob");
    }

    @Test
    void refreshesPermissionsOnExistingIdentity() throws Exception {
        var quota = new IdentityQuota(1, Duration.ofMillis(100), 10, () -> 0L);
        quota.acquire("alice");
        assertThatThrownBy(() -> quota.acquire("alice")).isInstanceOf(QuotaExceededException.class);
        // The registry ticker is frozen, so this exercises Resilience4j's own refresh.
        Thread.sleep(150);
        quota.acquire("alice");
    }

    @Test
    void saturationDoesNotEvictActiveIdentitiesAndIdleEntriesAreReclaimed() {
        var nanos = new AtomicLong();
        var quota = new IdentityQuota(1, Duration.ofMinutes(1), 1, nanos::get);
        quota.acquire("alice");
        assertThatThrownBy(() -> quota.acquire("bob")).isInstanceOf(QuotaExceededException.class);
        assertThatThrownBy(() -> quota.acquire("alice")).isInstanceOf(QuotaExceededException.class);
        nanos.set(Duration.ofMinutes(1).toNanos());
        quota.acquire("bob");
    }

    @Test
    void loginLimitsOriginAndNormalizedIdentityIndependently() {
        var quotas = new RequestQuotas(1, 60, Duration.ofMinutes(1), 10);
        quotas.login("Alice", "192.0.2.1");
        assertThatThrownBy(() -> quotas.login("alice", "192.0.2.2"))
                .isInstanceOf(QuotaExceededException.class);
        assertThatThrownBy(() -> quotas.login("bob", "192.0.2.1"))
                .isInstanceOf(QuotaExceededException.class);
        quotas.login("charlie", "192.0.2.3");
        quotas.catalog("Alice");
    }
}
