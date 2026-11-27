package io.github.rey5137.topolith.spring.boot.mybatisfixture.host;

import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

public final class MapperHosts {
    private MapperHosts() {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    public static class Empty {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    public static class Host {
        @Bean
        SqlSessionFactory sqlSessionFactory() {
            var environment = new org.apache.ibatis.mapping.Environment("test",
                    new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),
                    new org.springframework.jdbc.datasource.DriverManagerDataSource());
            return new SqlSessionFactoryBuilder().build(new org.apache.ibatis.session.Configuration(environment));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.AutoConfigurationPackage(
            basePackages = "io.github.rey5137.topolith.spring.boot.mybatisfixture.hostscan")
    public static class Starter {
        @Bean
        javax.sql.DataSource dataSource() {
            return new org.springframework.jdbc.datasource.DriverManagerDataSource();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @MapperScan("io.github.rey5137.topolith.spring.boot.mybatisfixture.hostscan")
    public static class OwnHostScan extends Starter {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    public static class FactoryBeanHost {
        @Bean
        org.mybatis.spring.SqlSessionFactoryBean sqlSessionFactory() {
            var factory = new org.mybatis.spring.SqlSessionFactoryBean();
            factory.setDataSource(new org.springframework.jdbc.datasource.DriverManagerDataSource());
            return factory;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    public static class Multiple extends Host {
        @Bean
        SqlSessionFactory secondFactory() {
            var environment = new org.apache.ibatis.mapping.Environment("test",
                    new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),
                    new org.springframework.jdbc.datasource.DriverManagerDataSource());
            return new SqlSessionFactoryBuilder().build(new org.apache.ibatis.session.Configuration(environment));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @MapperScan("io.github.rey5137.topolith.spring.boot.mybatisfixture.mapper")
    public static class Own extends Host {
    }
}
