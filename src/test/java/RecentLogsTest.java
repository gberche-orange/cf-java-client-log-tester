import org.cloudfoundry.client.CloudFoundryClient;
import org.cloudfoundry.client.v2.applications.GetApplicationRequest;
import org.cloudfoundry.client.v2.applications.GetApplicationResponse;
import org.cloudfoundry.doppler.DopplerClient;
import org.cloudfoundry.doppler.Envelope;
import org.cloudfoundry.doppler.RecentLogsRequest;
import org.cloudfoundry.reactor.ConnectionContext;
import org.cloudfoundry.reactor.DefaultConnectionContext;
import org.cloudfoundry.reactor.TokenProvider;
import org.cloudfoundry.reactor.client.ReactorCloudFoundryClient;
import org.cloudfoundry.reactor.doppler.ReactorDopplerClient;
import org.cloudfoundry.reactor.tokenprovider.PasswordGrantTokenProvider;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;


public class RecentLogsTest {

    private static final Logger log = LoggerFactory.getLogger(RecentLogsTest.class);


    private static final Duration FIRST_TIMEOUT = Duration.ofSeconds(120);

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

        @Test
    public void reproduce_recent_log_issue() {

        String apiHost = "api.run.pivotal.io";
        String username = System.getProperty("username");
        String password = System.getProperty("password");
        final ConnectionContext connectionContext = DefaultConnectionContext.builder()
            .apiHost(apiHost)
            .skipSslValidation(true)
            .build();
        final TokenProvider tokenProvider = PasswordGrantTokenProvider.builder()
            .username(username)
            .password(password)
            .build();
        final CloudFoundryClient client = ReactorCloudFoundryClient.builder()
            .connectionContext(connectionContext)
            .tokenProvider(tokenProvider)
            .build();
        final DopplerClient dopplerClient = ReactorDopplerClient.builder()
            .connectionContext(connectionContext)
            .tokenProvider(tokenProvider)
            .build();
        final Duration timeOfSleep = Duration.ofSeconds(5);
        String applicationId = "43dc8103-6d62-46c7-8456-6dcebfa2f2d1"; //on pws API endpoint:   https://api.run.pivotal.io (API version: 2.59.0)   app=hello-cf-java-client  (static buildpack app)

        GetApplicationResponse response = client.applicationsV2().get(GetApplicationRequest.builder()
            .applicationId(applicationId)
            .build())
            .block(FIRST_TIMEOUT);
        log.debug("Application {} exists with name {}", applicationId, response.getEntity().getName());

        try {
            while (true) {
                try {
                    getRemoteLogs(dopplerClient, applicationId);
                } catch (Throwable t) {
                    log.error("Un-managed error", t);
                }
                sleep(timeOfSleep);
            }
        } catch (Throwable t) {
            log.error("Process ended unexpectedly", t);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException i) {
            log.error("Interrupted while sleeping");
        }
    }

    private static void getRemoteLogs(DopplerClient dopplerClient, String applicationId) throws Throwable {
        Throwable dopplerException;

        int i=0;
        do {
            dopplerException = fetchLogsOrReturnError(dopplerClient, applicationId, i);
            i++;
        } while (i < 100 && dopplerException != null);
        if (dopplerException != null) {
            log.debug("Failed " + i + " retries");
            throw dopplerException;
        }

    }

    private static Throwable fetchLogsOrReturnError(DopplerClient dopplerClient, String applicationId, int retry) {
        Throwable dopplerException = null;
        Disposable subscribedLogs = null;
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicLong count = new AtomicLong();
            final AtomicReference<Throwable> errorReference = new AtomicReference<>();
            final AtomicReference<Long> lastTimeStamp = new AtomicReference<>(0L);
            final AtomicReference<Long> firstTimeStamp = new AtomicReference<>(-1L);
            subscribedLogs = dopplerClient.recentLogs(RecentLogsRequest.builder()
                    .applicationId(applicationId)
                    .build())
                    .subscribe(envelope -> {
                                logEnvelope(envelope);
                                count.incrementAndGet();
                                Long timestamp = envelope.getTimestamp();
                                if (timestamp != null && timestamp > lastTimeStamp.get()) {
                                    lastTimeStamp.set(timestamp);
                                }
                                if (timestamp != null && timestamp > 0 && firstTimeStamp.get() == -1L) {
                                    firstTimeStamp.set(timestamp);
                                }
                                if (envelope.getLogMessage() == null) {
                                    log.error("another type than LogMessage ?");
                                }
                            },
                            throwable -> {
                                errorReference.set(throwable);
                                latch.countDown();
                            },
                            latch::countDown);

            if (!latch.await(TIMEOUT.getSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("Subscriber timed out");
            } else {
                Instant lastTsInst = Instant.ofEpochSecond(0, lastTimeStamp.get());
                Instant firstTsInst = Instant.ofEpochSecond(0, firstTimeStamp.get() == null ? 0L: firstTimeStamp.get());
                if (errorReference.get() != null) {
                    dopplerException = errorReference.get();
                    log.debug("caught exception. retry #{}. got {} envelopes. 1st_ts={} last_ts={} Exception={}", retry, count.get(), firstTsInst, lastTsInst, dopplerException.toString());
                } else {
                    log.debug("got {} envelopes 1st_ts={} last_ts={}", count.get(), firstTsInst, lastTsInst);
                }
            }
        } catch (InterruptedException i) {
            log.error("Interrupted while waiting for call result", i);
        } finally {
            if (subscribedLogs != null) {
                subscribedLogs.dispose();
            }
        }
        return dopplerException;
    }

    private static void logEnvelope(Envelope envelope) {
        log.trace("envelop fist 20 bytes:" + envelope.toString().substring(0, 20));
    }

}