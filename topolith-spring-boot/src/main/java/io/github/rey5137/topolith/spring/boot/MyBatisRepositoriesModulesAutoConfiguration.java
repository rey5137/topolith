package io.github.rey5137.topolith.spring.boot;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.mapper.ClassPathMapperScanner;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.PriorityOrdered;

/** MyBatis mappers for internal modules (D-113). */
@AutoConfiguration(beforeName = "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration")
@ConditionalOnClass(name = {"org.mybatis.spring.mapper.ClassPathMapperScanner", "org.apache.ibatis.annotations.Mapper"})
class MyBatisRepositoriesModulesAutoConfiguration {
    @Bean
    static Registrar myBatisModuleMappersRegistrar() {
        return new Registrar();
    }

    static final class Registrar extends MapperScannerConfigurer implements PriorityOrdered, BeanFactoryAware,
            BeanNameAware, EnvironmentAware, BeanClassLoaderAware, ResourceLoaderAware {
        private BeanFactory beanFactory;
        private Environment environment;
        private ClassLoader loader;
        private ResourceLoader resources;
        private String beanName;

        @Override
        public void setBeanName(String beanName) {
            this.beanName = beanName;
        }

        @Override
        public void afterPropertiesSet() {
            // This registrar selects packages itself.
        }

        @Override
        public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
            this.beanFactory = beanFactory;
        }

        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void setBeanClassLoader(ClassLoader classLoader) {
            this.loader = classLoader;
        }

        @Override
        public void setResourceLoader(ResourceLoader resourceLoader) {
            this.resources = resourceLoader;
        }

        @Override
        public int getOrder() {
            return LOWEST_PRECEDENCE;
        }

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
            ConfigurableListableBeanFactory factory = (ConfigurableListableBeanFactory) beanFactory;
            var resolution = TopologyResolver.resolve(beanFactory, environment, loader);
            String type = "org.apache.ibatis.session.SqlSessionFactory";
            List<String> hosts = ModuleBeans.hostBeans(beanFactory, loader, resolution, type);
            List<String> scanners = java.util.Arrays.stream(
                    factory.getBeanNamesForType(MapperScannerConfigurer.class, true, false))
                    .filter(name -> !name.equals(beanName)).toList();
            boolean ownScanner = scanners.stream().anyMatch(name -> {
                Object packages = factory.getBeanDefinition(name).getPropertyValues().get("basePackage");
                return packages == null || java.util.Arrays.stream(packages.toString().split("[,;\\s]+"))
                        .anyMatch(pkg -> resolution.topology().modulePackages().values().stream()
                                .noneMatch(module -> pkg.equals(module) || pkg.startsWith(module + ".")));
            });
            if (!ownScanner && AutoConfigurationPackages.has(beanFactory)) {
                ClassPathMapperScanner host = scanner(registry);
                if (hosts.size() == 1) {
                    host.setSqlSessionFactoryBeanName(hosts.get(0));
                }
                host.setExcludeFilters(List.of((reader, metadataFactory) -> resolution.topology().modulePackages()
                        .values().stream()
                        .anyMatch(pkg -> reader.getClassMetadata().getClassName().startsWith(pkg + "."))));
                host.registerFilters();
                host.scan(AutoConfigurationPackages.get(beanFactory).toArray(String[]::new));
            }
            if (hosts.size() != 1) {
                return;
            }
            List<String> owned = ModuleBeans.contributors(beanFactory, loader, resolution, type);
            Set<String> registered = ModuleBeans.registeredInterfaceNames(beanFactory, loader,
                    ModuleBeans.MAPPER_FACTORY_BEAN);
            for (String id : new TreeSet<>(resolution.topology().internal())) {
                String modulePackage = resolution.topology().modulePackages().get(id);
                if (modulePackage == null || owned.contains(id) || scanners.stream().anyMatch(name -> {
                    Object packages = factory.getBeanDefinition(name).getPropertyValues().get("basePackage");
                    return packages != null && java.util.Arrays.stream(packages.toString().split("[,;\\s]+"))
                            .anyMatch(pkg -> modulePackage.equals(pkg) || modulePackage.startsWith(pkg + "."));
                })) {
                    continue;
                }
                ClassPathMapperScanner scanner = scanner(registry);
                scanner.setBeanNameGenerator(new FullyQualifiedAnnotationBeanNameGenerator());
                scanner.setSqlSessionFactoryBeanName(hosts.get(0));
                scanner.setExcludeFilters(List.of((reader, metadataFactory) ->
                        registered.contains(reader.getClassMetadata().getClassName())));
                scanner.registerFilters();
                scanner.scan(modulePackage);
            }
        }

        private ClassPathMapperScanner scanner(BeanDefinitionRegistry registry) {
            ClassPathMapperScanner scanner = new ClassPathMapperScanner(registry, environment);
            scanner.setResourceLoader(resources);
            scanner.setAnnotationClass(Mapper.class);
            scanner.setPrintWarnLogIfNotFoundMappers(false);
            scanner.setLazyInitialization(environment.getProperty("mybatis.lazy-initialization", Boolean.class, false));
            scanner.setDefaultScope(environment.getProperty("mybatis.mapper-default-scope"));
            return scanner;
        }
    }
}
