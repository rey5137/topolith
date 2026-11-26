package io.github.rey5137.topolith.spring.cloud;

import io.github.rey5137.topolith.core.Incubating;
import io.github.rey5137.topolith.spring.boot.TopolithAutoConfiguration;
import io.github.rey5137.topolith.spring.boot.TopolithRefresh;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.annotation.Bean;

/** Refresh handling, only with Spring Cloud's context classes (D-20); without them Topolith has none. */
@Incubating
@AutoConfiguration(after = TopolithAutoConfiguration.class)
@ConditionalOnClass(EnvironmentChangeEvent.class)
public class TopolithCloudAutoConfiguration {
    @Bean
    @ConditionalOnBean(TopolithRefresh.class)
    TopolithRefreshListener topolithRefreshListener(TopolithRefresh refresh) {
        return new TopolithRefreshListener(refresh);
    }
}
