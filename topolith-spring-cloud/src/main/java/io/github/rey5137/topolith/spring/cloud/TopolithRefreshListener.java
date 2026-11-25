package io.github.rey5137.topolith.spring.cloud;

import io.github.rey5137.topolith.spring.boot.TopolithRefresh;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ApplicationListener;

/** Hands the changed keys of an {@link EnvironmentChangeEvent} to {@link TopolithRefresh} (D-20, D-50). */
final class TopolithRefreshListener implements ApplicationListener<EnvironmentChangeEvent> {
    private final TopolithRefresh refresh;

    TopolithRefreshListener(TopolithRefresh refresh) {
        this.refresh = refresh;
    }

    @Override
    public void onApplicationEvent(EnvironmentChangeEvent event) {
        refresh.apply(event.getKeys());
    }
}
