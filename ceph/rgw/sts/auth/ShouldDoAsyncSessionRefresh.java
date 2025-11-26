/*
 * Copyright 2011-2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package ceph.rgw.sts.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;

/**
 * Predicate to determine when it is sufficient to do an async refresh of session credentials and
 * return existing credentials to the caller. This is done within a comfortable margin of session
 * expiration so we can optimistically fetch new credentials from STS and never have to block the
 * caller.
 */
class ShouldDoAsyncSessionRefresh implements Predicate<SessionCredentialsHolder> {

    /**
     * Time before expiry within which session credentials will be asynchronously refreshed.
     */
    private static final Duration ASYNC_REFRESH_EXPIRATION = Duration.ofMinutes(5);

    @Override
    public boolean test(SessionCredentialsHolder sessionCredentialsHolder) {
        Instant expiryTime = sessionCredentialsHolder.getSessionCredentialsExpiration();
        if (expiryTime != null) {
            Duration timeRemaining = Duration.between(Instant.now(), expiryTime);
            return timeRemaining.compareTo(ASYNC_REFRESH_EXPIRATION) < 0;
        }
        return false;
    }
}

