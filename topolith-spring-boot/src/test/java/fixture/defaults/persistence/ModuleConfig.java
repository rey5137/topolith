package fixture.defaults.persistence;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration(proxyBeanMethods = false)
public class ModuleConfig {
    @Bean public DataSource moduleDataSource() { return new DriverManagerDataSource(); }
}
