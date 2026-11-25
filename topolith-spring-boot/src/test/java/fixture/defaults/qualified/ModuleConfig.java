package fixture.defaults.qualified;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ModuleConfig {
    @Bean(defaultCandidate = false) public StringBuilder moduleBuilder() { return new StringBuilder(); }
}
