package com.conexa.starwars.ratelimit;

import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class RequestQuotas {
    private final IdentityQuota loginIdentities;
    private final IdentityQuota loginOrigins;
    private final IdentityQuota catalogUsers;

    public RequestQuotas(
            @Value("${quota.login.permits:5}") int loginPermits,
            @Value("${quota.catalog.permits:60}") int catalogPermits,
            @Value("${quota.refresh-period:1m}") Duration period,
            @Value("${quota.maximum-identities:10000}") int maximumIdentities) {
        loginIdentities =
                new IdentityQuota(loginPermits, period, maximumIdentities, System::nanoTime);
        loginOrigins = new IdentityQuota(loginPermits, period, maximumIdentities, System::nanoTime);
        catalogUsers =
                new IdentityQuota(catalogPermits, period, maximumIdentities, System::nanoTime);
    }

    public void login(String username, String remoteAddress) {
        loginOrigins.acquire(remoteAddress);
        loginIdentities.acquire(username.strip().toLowerCase(Locale.ROOT));
    }

    public void catalog(String subject) {
        catalogUsers.acquire(subject);
    }

    /** Resets instance-local quotas; no HTTP endpoint exposes this operation. */
    public void clear() {
        loginIdentities.clear();
        loginOrigins.clear();
        catalogUsers.clear();
    }
}
