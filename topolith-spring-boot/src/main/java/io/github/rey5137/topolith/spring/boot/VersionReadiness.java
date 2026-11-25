package io.github.rey5137.topolith.spring.boot;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * The version checks of R-VAL-04, once per application, when it is ready. The internal ones read descriptors and run
 * on the event thread; the external ones call the providers and run on one daemon thread, so the timeouts of an
 * unreachable provider never delay readiness (D-24, D-34).
 */
final class VersionReadiness implements ApplicationListener<ApplicationReadyEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(TopolithAutoConfiguration.class);

    private final BeanFactory beanFactory;
    private final AtomicBoolean logged = new AtomicBoolean();
    private volatile CompletableFuture<Void> remote = CompletableFuture.completedFuture(null);

    VersionReadiness(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!logged.compareAndSet(false, true)) {
            return;
        }
        TopologyResolver.Resolution resolution = beanFactory.getBean(TopologyResolver.RESOLUTION_BEAN,
                TopologyResolver.Resolution.class);
        resolution.versionWarnings().forEach(LOG::warn);
        if (beanFactory.containsBean(ExternalErrorsConfiguration.CLIENTS_BEAN)) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            remote = done;
            Thread thread = new Thread(() -> {
                try {
                    ExternalVersionCheck.check(beanFactory, resolution, LOG::warn);
                } catch (RuntimeException e) {
                    LOG.warn("the remote API version check failed: {}", e.getClass().getSimpleName());
                } finally {
                    done.complete(null);
                }
            }, "topolith-version-check");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** Waits for the remote checks started by the ready event; for tests and shutdown hooks. */
    boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        try {
            remote.get(timeout, unit);
            return true;
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
            return false;
        }
    }
}
