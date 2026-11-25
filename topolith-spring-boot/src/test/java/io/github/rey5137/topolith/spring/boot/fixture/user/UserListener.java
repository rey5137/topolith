package io.github.rey5137.topolith.spring.boot.fixture.user;

import io.github.rey5137.topolith.spring.boot.fixture.shared.FixtureEvent;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class UserListener {
    @EventListener
    void on(FixtureEvent event) {
        FixtureEvent.RECEIVED.add("user");
    }
}
