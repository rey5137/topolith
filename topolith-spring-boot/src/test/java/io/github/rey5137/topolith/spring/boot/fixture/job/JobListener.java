package io.github.rey5137.topolith.spring.boot.fixture.job;

import io.github.rey5137.topolith.spring.boot.fixture.shared.FixtureEvent;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** A listener of a module without contracts. */
@Component
public class JobListener {
    @EventListener
    void on(FixtureEvent event) {
        FixtureEvent.RECEIVED.add("job");
    }
}
