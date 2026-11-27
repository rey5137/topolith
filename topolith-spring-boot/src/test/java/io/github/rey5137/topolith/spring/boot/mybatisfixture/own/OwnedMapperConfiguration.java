package io.github.rey5137.topolith.spring.boot.mybatisfixture.own;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@MapperScan(basePackageClasses = OwnedMapper.class, sqlSessionFactoryRef = "ownedFactory")
public class OwnedMapperConfiguration {
    @org.springframework.context.annotation.Bean
    org.apache.ibatis.session.SqlSessionFactory ownedFactory() {
        var environment = new org.apache.ibatis.mapping.Environment("owned",
                new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),
                new org.springframework.jdbc.datasource.DriverManagerDataSource());
        return new org.apache.ibatis.session.SqlSessionFactoryBuilder()
                .build(new org.apache.ibatis.session.Configuration(environment));
    }
}
