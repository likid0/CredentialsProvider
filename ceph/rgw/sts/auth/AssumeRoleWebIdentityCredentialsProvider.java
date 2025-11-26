package ceph.rgw.sts.auth;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.retry.backoff.BackoffStrategy;
import software.amazon.awssdk.core.retry.conditions.RetryCondition;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithWebIdentityRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithWebIdentityResponse;
import software.amazon.awssdk.services.sts.model.Credentials;
import software.amazon.awssdk.services.sts.model.IdpCommunicationErrorException;
import software.amazon.awssdk.services.sts.model.InvalidIdentityTokenException;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Properties;
import java.util.concurrent.Callable;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

public class AssumeRoleWebIdentityCredentialsProvider implements AwsCredentialsProvider, Closeable {

    static final Logger logger = Logger.getLogger(AssumeRoleWebIdentityCredentialsProvider.class);
    static final String LOG_PROPERTIES_FILE = "log4j.properties";

    /**
     * The client for starting STS sessions.
     */
    private final StsClient securityTokenService;

    private final RefreshTokenService refreshTokenService;
    /**
     * The arn of the role to be assumed.
     */
    private final String roleArn;

    /**
     * An identifier for the assumed role session.
     */
    private final String roleSessionName;

    /**
     * Absolute path to the JWT file containing the web identity token.
     */
    private String webIdentityTokenFile;
    
    private String webIdentityToken;
    
    private final String policy;
    
    private final Integer durationInSeconds;
    
    private String roleArnFile;
    
    private final Callable<SessionCredentialsHolder> refreshCallable = new Callable<SessionCredentialsHolder>() {
        @Override
        public SessionCredentialsHolder call() throws Exception {
            return newSession();
        }
    };
    
    private final Callable<RefreshTokenResult> tokenRefreshCallable = new Callable<RefreshTokenResult>() {
        @Override
        public RefreshTokenResult call() throws Exception {
            return newToken();
        }
    };

    /**
     * Handles the refreshing of sessions. Ideally this should be final but #setSTSClientEndpoint
     * forces us to create a new one.
     */
    private volatile RefreshableTask<SessionCredentialsHolder> refreshableTask;
    
    private volatile RefreshableTask<RefreshTokenResult> tokenRefreshableTask;

    private RefreshableTask<SessionCredentialsHolder> createRefreshableTask() {
        return new RefreshableTask.Builder<SessionCredentialsHolder>()
                .withRefreshCallable(refreshCallable)
                .withBlockingRefreshPredicate(new ShouldDoBlockingSessionRefresh())
                .withAsyncRefreshPredicate(new ShouldDoAsyncSessionRefresh()).build();
    }
    
    /*
     * Handles the refreshing of the webidentity token
     */
    private RefreshableTask<RefreshTokenResult> createTokenRefreshableTask() {
        return new RefreshableTask.Builder<RefreshTokenResult>()
                .withRefreshCallable(tokenRefreshCallable)
                .withBlockingRefreshPredicate(new ShouldDoBlockingTokenRefresh())
                .withAsyncRefreshPredicate(new ShouldDoAsyncTokenRefresh()).build();
    }

    /**
     * The following private constructor reads state from the builder and sets the appropriate
     * parameters accordingly
     * <p>
     * When public constructors are called, this constructors is deferred to with a null value for
     * roleExternalId and endpoint The inner Builder class can be used to construct an object that
     * actually has a value for roleExternalId and endpoint
     *
     * @throws IllegalArgumentException if both an AWSCredentials and AWSCredentialsProvider have
     *                                  been set on the builder
     */
    private AssumeRoleWebIdentityCredentialsProvider(Builder builder) {
    	Properties logProperties = new Properties();
        
        try {
            // load log4j properties configuration file
            logProperties.load(new FileInputStream(LOG_PROPERTIES_FILE));
            PropertyConfigurator.configure(logProperties);
            logger.info("Logging initialized.");
        } catch (IOException e) {
            logger.error("Unable to load logging property :", e);
        }
        this.roleArn = builder.roleArn;
        this.roleSessionName = builder.roleSessionName;
        this.durationInSeconds = builder.durationInSeconds;
        this.policy = builder.policy;
        this.roleArnFile = builder.roleArnFile;
        this.securityTokenService = buildStsClient(builder);
        
        if (builder.refreshToken == null && builder.refreshTokenFile == null &&
        		builder.webIdentityToken == null && builder.webIdentityTokenFile == null) {
            logger.error("You must specify a value either for refreshToken(File) or webIdentityToken(File).");
            throw new NullPointerException(
                    "You must specify a value either for refreshToken(File) or webIdentityToken(File)");
        }
       
        if ((builder.refreshToken != null && !builder.refreshToken.isEmpty()) || 
        		(builder.refreshTokenFile != null && !builder.refreshTokenFile.isEmpty())) {
        	this.refreshTokenService = buildRefreshTokenService(builder);
        	logger.trace("Starting token refresh thread ....");
        	this.refreshTokenService.startRefreshThread();
        	this.tokenRefreshableTask = createTokenRefreshableTask();
    	} else {
    		this.refreshTokenService = null;
    		this.tokenRefreshableTask = null;
		this.webIdentityTokenFile = builder.webIdentityTokenFile;
	        this.webIdentityToken = builder.webIdentityToken;
    	}
    	
        this.refreshableTask = createRefreshableTask();
    }

    /**
     * Construct a new STS client from the settings in the builder.
     *
     * @param builder Configured builder
     * @return New instance of StsClient
     * @throws IllegalArgumentException if builder configuration is inconsistent
     */
    private static StsClient buildStsClient(Builder builder) throws IllegalArgumentException {
        if (builder.sts != null) {
            return builder.sts;
        }

        RetryPolicy retryPolicy = RetryPolicy.builder()
                .numRetries(3)
                .retryCondition(new StsRetryCondition())
                .backoffStrategy(BackoffStrategy.defaultStrategy())
                .build();

        return StsClient.builder()
                .credentialsProvider(AnonymousCredentialsProvider.create())
                .overrideConfiguration(c -> c.retryPolicy(retryPolicy))
                .build();
    }
    
    private static RefreshTokenService buildRefreshTokenService(Builder builder) throws IllegalArgumentException {
        
        return new RefreshTokenService(builder.clientId, builder.clientSecret, builder.idpUrl, builder.refreshToken, builder.refreshTokenFile, builder.refreshExpirationInMins, builder.isAccessToken);
    }

    @Override
    public AwsSessionCredentials resolveCredentials() {
        return refreshableTask.getValue().getSessionCredentials();
    }

    public void refresh() {
        refreshableTask.forceGetValue();
    }

    public void refreshTokens() {
    	tokenRefreshableTask.forceGetValue();
    }
    /**
     * Starts a new session by sending a request to the AWS Security Token Service (STS) to assume a
     * Role using the long lived AWS credentials. This class then vends the short lived session
     * credentials for the assumed Role sent back from STS.
     */
    private SessionCredentialsHolder newSession() {
        logger.trace("Refreshing Session ...");
        if (tokenRefreshableTask != null) {
            logger.trace("Checking whether to refresh access token...");
            this.webIdentityToken = tokenRefreshableTask.getValue().getToken();
        }
        AssumeRoleWithWebIdentityRequest.Builder requestBuilder = AssumeRoleWithWebIdentityRequest.builder()
                .roleArn(getRoleArn())
                .webIdentityToken(getWebIdentityToken())
                .roleSessionName(this.roleSessionName);

        if (this.durationInSeconds != null && this.durationInSeconds > 0) {
            requestBuilder.durationSeconds(this.durationInSeconds);
        }

        if (this.policy != null && !this.policy.isEmpty()) {
            requestBuilder.policy(this.policy);
        }

        AssumeRoleWithWebIdentityResponse assumeRoleResult = securityTokenService.assumeRoleWithWebIdentity(requestBuilder.build());
        return new SessionCredentialsHolder(assumeRoleResult.credentials());
    }
    
    private RefreshTokenResult newToken() {
		logger.trace("Refreshing token ...");
		RefreshTokenResult r = refreshTokenService.getRefreshedToken();
		return r;
    }

    private String getWebIdentityToken() {
        if (this.webIdentityToken != null && !this.webIdentityToken.isEmpty()) {
            return webIdentityToken;
        }
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(webIdentityTokenFile), "UTF-8"));
            return br.readLine();
        } catch (FileNotFoundException e) {
            throw SdkClientException.builder()
                    .message("Unable to locate specified web identity token file: " + webIdentityTokenFile)
                    .cause(e)
                    .build();
        } catch (IOException e) {
            throw SdkClientException.builder()
                    .message("Unable to read web identity token from file: " + webIdentityTokenFile)
                    .cause(e)
                    .build();
        } finally {
            try {
                if (br != null) {
                    br.close();
                }
            } catch (Exception ignored) {

            }
        }
    }
    
    private String getRoleArn() {
        if (this.roleArn != null && !this.roleArn.isEmpty()) {
            return this.roleArn;
        }
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(this.roleArnFile), "UTF-8"));
            return br.readLine();
        } catch (FileNotFoundException e) {
            throw SdkClientException.builder()
                    .message("Unable to locate specified role arn file: " + this.roleArnFile)
                    .cause(e)
                    .build();
        } catch (IOException e) {
            throw SdkClientException.builder()
                    .message("Unable to read role arn from file: " + this.roleArnFile)
                    .cause(e)
                    .build();
        } finally {
            try {
                if (br != null) {
                    br.close();
                }
            } catch (Exception ignored) {

            }
        }
    }

    /**
     * Shut down this credentials provider, shutting down the thread that performs asynchronous credential refreshing. This
     * should not be invoked if the credentials provider is still in use by an AWS client.
     */
    @Override
    public void close() {
        refreshableTask.close();
        if (tokenRefreshableTask != null) {
        	tokenRefreshableTask.close();
        }
        if (refreshTokenService != null) {
        	refreshTokenService.stopThread();
        }
    }

    /**
     * Provides a builder pattern to avoid combinatorial explosion of the number of parameters that
     * are passed to constructors. The builder introspects which parameters have been set and calls
     * the appropriate constructor.
     */
    public static final class Builder {

        private final String roleArn;
        private final String roleSessionName;
        private String webIdentityTokenFile;
        private String webIdentityToken;
        private String policy;
        private Integer durationInSeconds = 0;
        private String clientId;
        private String clientSecret;
        private String idpUrl;
        private String refreshToken;
        private String refreshTokenFile;
        private StsClient sts;
        private boolean isAccessToken = true;
        private long refreshExpirationInMins = 0;
        private final String roleArnFile;

        public Builder(String roleArn, String roleSessionName, String roleArnFile) {
            if (roleSessionName == null) {
                throw new NullPointerException(
                        "You must specify a value for roleSessionName");
            }
            if (roleArn == null && roleArnFile == null) {
                throw new NullPointerException(
                        "You must specify a value for roleArn or roleArnFile");
            }
            this.roleArn = roleArn;
            this.roleSessionName = roleSessionName;
            this.roleArnFile = roleArnFile;
        }

        /**
         * Sets a preconfigured STS client to use for the credentials provider. See
         * {@link StsClient#builder()} for an easy way to configure and create an STS client.
         *
         * @param sts Custom STS client to use.
         * @return This object for chained calls.
         */
        public Builder withStsClient(StsClient sts) {
            this.sts = sts;
            return this;
        }

        public Builder withDurationSeconds(Integer durationInSeconds) {
            this.durationInSeconds = durationInSeconds;
            return this;
        }
        
        public Builder withPolicy(String policy) {
            this.policy = policy;
            return this;
        }
        
        public Builder withWebIdentityToken(String webIdentityToken) {
            this.webIdentityToken = webIdentityToken;
            return this;
        }
        
        public Builder withWebIdentityTokenFile(String webIdentityTokenFile) {
            this.webIdentityTokenFile = webIdentityTokenFile;
            return this;
        }
        
        public Builder withRefreshToken(String refreshToken) {
            this.refreshToken = refreshToken;
            return this;
        }
        
        public Builder withRefreshTokenFile(String refreshTokenFile) {
            this.refreshTokenFile = refreshTokenFile;
            return this;
        }
        
        public Builder withClientId(String clientId) {
            this.clientId = clientId;
            return this;
        }
        
        public Builder withClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
            return this;
        }
        
        public Builder withIdpUrl(String idpUrl) {
            this.idpUrl = idpUrl;
            return this;
        }
        
        public Builder withIsAccessToken(boolean isAccessToken) {
            this.isAccessToken = isAccessToken;
            return this;
        }
        
        public Builder withRefreshExpiration(long refreshExpirationInMins) {
            this.refreshExpirationInMins = refreshExpirationInMins;
            return this;
        }
        
        /**
         * Build the configured provider
         *
         * @return the configured STSAssumeRoleSessionCredentialsProvider
         */
        public AssumeRoleWebIdentityCredentialsProvider build() {
        	return new AssumeRoleWebIdentityCredentialsProvider(this);
        }
    }

    static class StsRetryCondition implements RetryCondition {

        @Override
        public boolean shouldRetry(software.amazon.awssdk.core.retry.RetryPolicyContext context) {
            Throwable exception = context.exception();

            // Always retry on client exceptions caused by IOException
            if (exception.getCause() instanceof IOException) return true;

            if (exception instanceof InvalidIdentityTokenException ||
                    exception.getCause() instanceof InvalidIdentityTokenException) return true;

            if (exception instanceof IdpCommunicationErrorException ||
                    exception.getCause() instanceof IdpCommunicationErrorException) return true;

            // Only retry on a subset of service exceptions
            if (exception instanceof SdkServiceException) {
                SdkServiceException sse = (SdkServiceException)exception;

                /*
                 * For 500 internal server errors and 503 service
                 * unavailable errors, we want to retry, but we need to use
                 * an exponential back-off strategy so that we don't overload
                 * a server with a flood of retries.
                 */
                if (sse.statusCode() >= 500) return true;

                /*
                 * Throttling is reported as a 400 error from newer services. To try
                 * and smooth out an occasional throttling error, we'll pause and
                 * retry, hoping that the pause is long enough for the request to
                 * get through the next time.
                 */
                if (sse.isThrottlingException()) return true;

                /*
                 * Clock skew exception. If it is then we will get the time offset
                 * between the device time and the server time to set the clock skew
                 * and then retry the request.
                 */
                if (sse.isClockSkewException()) return true;
            }

            return false;
        }

    }
}

