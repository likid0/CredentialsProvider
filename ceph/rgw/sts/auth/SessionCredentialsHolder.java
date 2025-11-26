package ceph.rgw.sts.auth;

import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.time.Instant;

/**
 * Holder class used to atomically store a session with its expiration time.
 */
final class SessionCredentialsHolder {

    private final AwsSessionCredentials sessionCredentials;
    private final Instant sessionCredentialsExpiration;

    SessionCredentialsHolder(Credentials credentials) {
        this.sessionCredentials = AwsSessionCredentials.create(
                credentials.accessKeyId(),
                credentials.secretAccessKey(),
                credentials.sessionToken());
        this.sessionCredentialsExpiration = credentials.expiration();
    }

    public AwsSessionCredentials getSessionCredentials() {
        return sessionCredentials;
    }

    public Instant getSessionCredentialsExpiration() {
        return sessionCredentialsExpiration;
    }
}
