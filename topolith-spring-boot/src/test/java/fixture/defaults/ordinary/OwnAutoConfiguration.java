package fixture.defaults.ordinary;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class OwnAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    StringBuilder ownDefaultBuilder() {
        return new StringBuilder();
    }
}
