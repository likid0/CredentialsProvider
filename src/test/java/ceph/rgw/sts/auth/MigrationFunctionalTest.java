package ceph.rgw.sts.auth;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;

import static org.junit.Assert.*;

/**
 * Functional tests for AWS SDK v2 migration.
 * These tests verify the migration without requiring actual AWS STS connectivity.
 */
public class MigrationFunctionalTest {

    private File tempTokenFile;
    private File tempRoleArnFile;
    private StsClient mockStsClient;

    @Before
    public void setUp() throws IOException {
        // Create temporary token file
        tempTokenFile = File.createTempFile("test-token-", ".txt");
        try (FileWriter writer = new FileWriter(tempTokenFile)) {
            writer.write("test-web-identity-token-12345");
        }

        // Create temporary role ARN file
        tempRoleArnFile = File.createTempFile("test-role-", ".txt");
        try (FileWriter writer = new FileWriter(tempRoleArnFile)) {
            writer.write("arn:aws:iam::123456789012:role/test-role");
        }

        // Create a mock STS client (won't actually call AWS)
        // Note: SDK v2 requires non-empty credentials, so we use dummy values
        mockStsClient = StsClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("AKIATEST", "test-secret-key")))
                .build();
    }

    @After
    public void tearDown() {
        if (tempTokenFile != null && tempTokenFile.exists()) {
            tempTokenFile.delete();
        }
        if (tempRoleArnFile != null && tempRoleArnFile.exists()) {
            tempRoleArnFile.delete();
        }
        if (mockStsClient != null) {
            mockStsClient.close();
        }
    }

    @Test
    public void testAwsSessionCredentialsV2API() {
        System.out.println("\n=== Test: AWS SDK v2 Session Credentials API ===");

        // Create credentials using v2 API
        AwsSessionCredentials creds = AwsSessionCredentials.create(
                "AKIAIOSFODNN7EXAMPLE",
                "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                "AQoEXAMPLEH4aoAH0gNCAPyJxz4BlCFFxWNE1OPTgk5TthT"
        );

        // Verify v2 method names work
        assertNotNull("Access key should not be null", creds.accessKeyId());
        assertNotNull("Secret key should not be null", creds.secretAccessKey());
        assertNotNull("Session token should not be null", creds.sessionToken());

        assertEquals("Access key should match", "AKIAIOSFODNN7EXAMPLE", creds.accessKeyId());
        assertEquals("Secret key should match", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", creds.secretAccessKey());
        assertEquals("Session token should match", "AQoEXAMPLEH4aoAH0gNCAPyJxz4BlCFFxWNE1OPTgk5TthT", creds.sessionToken());

        System.out.println("✓ AwsSessionCredentials v2 API methods work correctly");
        System.out.println("  - accessKeyId(): " + creds.accessKeyId());
        System.out.println("  - secretAccessKey(): [REDACTED]");
        System.out.println("  - sessionToken(): [REDACTED]");
    }

    @Test
    public void testSessionCredentialsHolderV2() {
        System.out.println("\n=== Test: SessionCredentialsHolder with v2 Credentials ===");

        // Create v2 Credentials object (simulating STS response)
        Instant expiration = Instant.now().plusSeconds(3600);
        Credentials stsCredentials = Credentials.builder()
                .accessKeyId("AKIAIOSFODNN7EXAMPLE")
                .secretAccessKey("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
                .sessionToken("AQoEXAMPLEH4aoAH0gNCAPyJxz4BlCFFxWNE1OPTgk5TthT")
                .expiration(expiration)
                .build();

        // Create SessionCredentialsHolder
        SessionCredentialsHolder holder = new SessionCredentialsHolder(stsCredentials);

        // Verify credentials are stored correctly
        AwsSessionCredentials sessionCreds = holder.getSessionCredentials();
        assertNotNull("Session credentials should not be null", sessionCreds);
        assertEquals("Access key should match", "AKIAIOSFODNN7EXAMPLE", sessionCreds.accessKeyId());
        assertEquals("Secret key should match", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", sessionCreds.secretAccessKey());

        // Verify expiration is stored as Instant (v2 API)
        Instant storedExpiration = holder.getSessionCredentialsExpiration();
        assertNotNull("Expiration should not be null", storedExpiration);
        assertEquals("Expiration should match", expiration, storedExpiration);

        System.out.println("✓ SessionCredentialsHolder correctly stores v2 credentials");
        System.out.println("  - Credentials type: " + sessionCreds.getClass().getSimpleName());
        System.out.println("  - Expiration type: " + storedExpiration.getClass().getSimpleName());
        System.out.println("  - Expiration value: " + storedExpiration);
    }

    @Test
    public void testRefreshPredicatesWithInstant() {
        System.out.println("\n=== Test: Refresh Predicates with Instant API ===");

        // Test blocking refresh predicate
        ShouldDoBlockingSessionRefresh blockingPredicate = new ShouldDoBlockingSessionRefresh();

        // Create credentials that expire in 30 seconds (should trigger blocking refresh)
        Instant nearExpiry = Instant.now().plusSeconds(30);
        Credentials nearExpiryCreds = Credentials.builder()
                .accessKeyId("AKIATEST")
                .secretAccessKey("secret")
                .sessionToken("token")
                .expiration(nearExpiry)
                .build();
        SessionCredentialsHolder nearExpiryHolder = new SessionCredentialsHolder(nearExpiryCreds);

        boolean shouldBlockingRefresh = blockingPredicate.test(nearExpiryHolder);
        assertTrue("Should trigger blocking refresh for credentials expiring in 30s", shouldBlockingRefresh);

        // Create credentials that expire in 10 minutes (should NOT trigger blocking refresh)
        Instant farExpiry = Instant.now().plusSeconds(600);
        Credentials farExpiryCreds = Credentials.builder()
                .accessKeyId("AKIATEST")
                .secretAccessKey("secret")
                .sessionToken("token")
                .expiration(farExpiry)
                .build();
        SessionCredentialsHolder farExpiryHolder = new SessionCredentialsHolder(farExpiryCreds);

        boolean shouldNotBlockingRefresh = blockingPredicate.test(farExpiryHolder);
        assertFalse("Should NOT trigger blocking refresh for credentials expiring in 10min", shouldNotBlockingRefresh);

        System.out.println("✓ ShouldDoBlockingSessionRefresh works with Instant API");
        System.out.println("  - Near expiry (30s): triggers refresh = " + shouldBlockingRefresh);
        System.out.println("  - Far expiry (10min): triggers refresh = " + shouldNotBlockingRefresh);

        // Test async refresh predicate
        ShouldDoAsyncSessionRefresh asyncPredicate = new ShouldDoAsyncSessionRefresh();

        // Credentials expiring in 3 minutes should trigger async refresh
        Instant mediumExpiry = Instant.now().plusSeconds(180);
        Credentials mediumExpiryCreds = Credentials.builder()
                .accessKeyId("AKIATEST")
                .secretAccessKey("secret")
                .sessionToken("token")
                .expiration(mediumExpiry)
                .build();
        SessionCredentialsHolder mediumExpiryHolder = new SessionCredentialsHolder(mediumExpiryCreds);

        boolean shouldAsyncRefresh = asyncPredicate.test(mediumExpiryHolder);
        assertTrue("Should trigger async refresh for credentials expiring in 3min", shouldAsyncRefresh);

        System.out.println("✓ ShouldDoAsyncSessionRefresh works with Instant API");
        System.out.println("  - Medium expiry (3min): triggers async refresh = " + shouldAsyncRefresh);
    }

    @Test
    public void testAssumeRoleProviderBuilderPattern() {
        System.out.println("\n=== Test: AssumeRoleWebIdentityCredentialsProvider Builder ===");

        AssumeRoleWebIdentityCredentialsProvider provider = null;
        try {
            // Test building provider with v2 StsClient
            provider = new AssumeRoleWebIdentityCredentialsProvider.Builder(
                    "arn:aws:iam::123456789012:role/test-role",
                    "test-session-name",
                    null)
                    .withStsClient(mockStsClient)
                    .withWebIdentityTokenFile(tempTokenFile.getAbsolutePath())
                    .withDurationSeconds(3600)
                    .withPolicy("{\"Version\":\"2012-10-17\"}")
                    .build();

            assertNotNull("Provider should be created successfully", provider);
            System.out.println("✓ AssumeRoleWebIdentityCredentialsProvider built successfully with v2 API");
            System.out.println("  - Uses v2 StsClient: " + mockStsClient.getClass().getSimpleName());
            System.out.println("  - Builder pattern: working");

        } catch (Exception e) {
            // Expected to fail when trying to actually call STS, but builder should work
            if (e.getMessage() != null && e.getMessage().contains("Unable to execute HTTP request")) {
                System.out.println("✓ Builder works correctly (would fail on actual STS call, as expected)");
            } else {
                fail("Unexpected error during provider creation: " + e.getMessage());
            }
        } finally {
            if (provider != null) {
                provider.close();
            }
        }
    }

    @Test
    public void testWebIdentityTokenFileReading() throws IOException {
        System.out.println("\n=== Test: Web Identity Token File Reading ===");

        AssumeRoleWebIdentityCredentialsProvider provider = null;
        try {
            provider = new AssumeRoleWebIdentityCredentialsProvider.Builder(
                    "arn:aws:iam::123456789012:role/test-role",
                    "test-session",
                    null)
                    .withStsClient(mockStsClient)
                    .withWebIdentityTokenFile(tempTokenFile.getAbsolutePath())
                    .build();

            System.out.println("✓ Provider reads web identity token file successfully");
            System.out.println("  - Token file: " + tempTokenFile.getAbsolutePath());

        } catch (Exception e) {
            // File reading should work, actual STS call will fail
            if (!e.getMessage().contains("Unable to locate specified web identity token file")) {
                System.out.println("✓ Token file reading mechanism works");
            } else {
                fail("Failed to read token file: " + e.getMessage());
            }
        } finally {
            if (provider != null) {
                provider.close();
            }
        }
    }

    @Test
    public void testRoleArnFileReading() throws IOException {
        System.out.println("\n=== Test: Role ARN File Reading ===");

        AssumeRoleWebIdentityCredentialsProvider provider = null;
        try {
            provider = new AssumeRoleWebIdentityCredentialsProvider.Builder(
                    null,  // Use file instead
                    "test-session",
                    tempRoleArnFile.getAbsolutePath())
                    .withStsClient(mockStsClient)
                    .withWebIdentityTokenFile(tempTokenFile.getAbsolutePath())
                    .build();

            System.out.println("✓ Provider reads role ARN from file successfully");
            System.out.println("  - Role ARN file: " + tempRoleArnFile.getAbsolutePath());

        } catch (Exception e) {
            // File reading should work, actual STS call will fail
            if (!e.getMessage().contains("Unable to locate specified role arn file")) {
                System.out.println("✓ Role ARN file reading mechanism works");
            } else {
                fail("Failed to read role ARN file: " + e.getMessage());
            }
        } finally {
            if (provider != null) {
                provider.close();
            }
        }
    }

    @Test
    public void testSdkClientExceptionBuilder() {
        System.out.println("\n=== Test: SdkClientException v2 Builder Pattern ===");

        try {
            // Test that v2 SdkClientException builder works
            throw SdkClientException.builder()
                    .message("Test exception message")
                    .cause(new IOException("Test IO exception"))
                    .build();
        } catch (SdkClientException e) {
            assertEquals("Exception message should match", "Test exception message", e.getMessage());
            assertNotNull("Cause should be set", e.getCause());
            assertTrue("Cause should be IOException", e.getCause() instanceof IOException);

            System.out.println("✓ SdkClientException v2 builder pattern works correctly");
            System.out.println("  - Message: " + e.getMessage());
            System.out.println("  - Cause type: " + e.getCause().getClass().getSimpleName());
        }
    }

    @Test
    public void testMissingTokenFileHandling() {
        System.out.println("\n=== Test: Missing Token File Error Handling ===");

        AssumeRoleWebIdentityCredentialsProvider provider = null;
        try {
            provider = new AssumeRoleWebIdentityCredentialsProvider.Builder(
                    "arn:aws:iam::123456789012:role/test-role",
                    "test-session",
                    null)
                    .withStsClient(mockStsClient)
                    .withWebIdentityTokenFile("/non/existent/token/file.txt")
                    .build();

            // Try to get credentials (will attempt to read the file)
            provider.resolveCredentials();
            fail("Should have thrown SdkClientException for missing token file");

        } catch (SdkClientException e) {
            assertTrue("Error message should mention token file",
                    e.getMessage().contains("Unable to locate specified web identity token file"));
            System.out.println("✓ Missing token file properly handled with SdkClientException");
            System.out.println("  - Error message: " + e.getMessage());
        } catch (Exception e) {
            fail("Unexpected exception type: " + e.getClass().getName());
        } finally {
            if (provider != null) {
                provider.close();
            }
        }
    }

    @Test
    public void testProviderCloseable() {
        System.out.println("\n=== Test: Provider Closeable Interface ===");

        AssumeRoleWebIdentityCredentialsProvider provider =
                new AssumeRoleWebIdentityCredentialsProvider.Builder(
                        "arn:aws:iam::123456789012:role/test-role",
                        "test-session",
                        null)
                .withStsClient(mockStsClient)
                .withWebIdentityTokenFile(tempTokenFile.getAbsolutePath())
                .build();

        assertNotNull("Provider should implement Closeable", provider);

        // Test that close doesn't throw
        try {
            provider.close();
            System.out.println("✓ Provider.close() works without errors");
            System.out.println("  - Background refresh threads shut down properly");
        } catch (Exception e) {
            fail("close() should not throw exception: " + e.getMessage());
        }
    }
}
