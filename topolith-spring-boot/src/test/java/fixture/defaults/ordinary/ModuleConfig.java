package fixture.defaults.ordinary;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ModuleConfig {
    @Bean public StringBuilder moduleBuilder() { return new StringBuilder(); }
}
