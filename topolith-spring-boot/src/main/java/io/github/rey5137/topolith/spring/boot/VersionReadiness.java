package io.github.rey5137.topolith.spring.boot;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.spring.boot.internal.VersionChecks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

/** Runs version checks after readiness and tracks remote checks across refresh and context close. */
final class VersionReadiness implements ApplicationListener<ApplicationEvent>, VersionChecks {
    private static final Logger LOG = LoggerFactory.getLogger(TopolithAutoConfiguration.class);

    private final BeanFactory beanFactory;
    private final AtomicBoolean logged = new AtomicBoolean();
    private final Set<Thread> checks = new HashSet<>();
    private boolean closed;

    VersionReadiness(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof ContextClosedEvent close
                && close.getApplicationContext().getAutowireCapableBeanFactory() == beanFactory) {
            synchronized (checks) {
                closed = true;
                checks.forEach(Thread::interrupt);
            }
        } else if (event instanceof ApplicationReadyEvent && logged.compareAndSet(false, true)) {
            TopologyResolver.Resolution resolution = beanFactory.getBean(TopologyResolver.RESOLUTION_BEAN,
                    TopologyResolver.Resolution.class);
            resolution.versionWarnings().forEach(LOG::warn);
            if (beanFactory.containsBean(ExternalErrorsConfiguration.CLIENTS_BEAN)) {
                startRemote(resolution, resolution.topology().idsOf(Mode.EXTERNAL));
            }
        }
    }

    /** Checks the given external modules on a daemon thread; refresh re-checks only its own module. */
    void startRemote(TopologyResolver.Resolution resolution, Set<String> ids) {
        synchronized (checks) {
            if (closed || ids.isEmpty()) {
                return;
            }
            Thread thread = new Thread(() -> {
                try {
                    ExternalVersionCheck.check(beanFactory, resolution, ids, this::warn);
                } catch (RuntimeException e) {
                    warn("the remote API version check failed: " + e.getClass().getSimpleName());
                } finally {
                    synchronized (checks) {
                        checks.remove(Thread.currentThread());
                        checks.notifyAll();
                    }
                }
            }, "topolith-version-check");
            thread.setDaemon(true);
            checks.add(thread);
            thread.start();
        }
    }

    private void warn(String message) {
        synchronized (checks) {
            if (!closed) {
                LOG.warn(message);
            }
        }
    }

    @Override
    public boolean await(Duration timeout) {
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException e) {
            nanos = Long.MAX_VALUE;
        }
        long start = System.nanoTime();
        synchronized (checks) {
            while (!checks.isEmpty()) {
                if (nanos <= 0) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(checks, nanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                nanos -= System.nanoTime() - start;
                start = System.nanoTime();
            }
            return true;
        }
    }
}
