package io.github.rey5137.topolith.spring.boot.fixture.shared;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** An application event, and the record of which fixture listeners received it. */
public final class FixtureEvent {
    public static final List<String> RECEIVED = new CopyOnWriteArrayList<>();

}
