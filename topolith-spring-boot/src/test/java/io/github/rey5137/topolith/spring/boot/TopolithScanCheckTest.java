package io.github.rey5137.topolith.spring.boot;

import java.nio.file.Path;
import java.util.List;

import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.server.servlet.context.ServletComponentScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.*;

class TopolithScanCheckTest {
    @TempDir Path root;

    @Configuration(proxyBeanMethods = false)
    @Import(TopolithAutoConfiguration.class)
    @ServletComponentScan(basePackages = Fixtures.USER)
    static class ServletHost { }

    @Configuration(proxyBeanMethods = false)
    @Import(TopolithAutoConfiguration.class)
    @ConfigurationPropertiesScan(basePackages = Fixtures.USER)
    static class PropertiesHost { }

    @Configuration(proxyBeanMethods = false)
    @Import(TopolithAutoConfiguration.class)
    @ComponentScan(basePackages = Fixtures.USER)
    static class ComponentHost { }

    @Configuration(proxyBeanMethods = false)
    @Import(TopolithAutoConfiguration.class)
    @ComponentScan(basePackages = "fixture.missing")
    @ComponentScan(basePackages = Fixtures.USER)
    static class RepeatedHost { }

    @Configuration(proxyBeanMethods = false)
    @Import(TopolithAutoConfiguration.class)
    @ComponentScan(basePackages = "fixture.defaults.ordinary",
            excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = "fixture\\.defaults\\.ordinary\\..*"))
    static class ExcludingHost { }

    @Test void ac_arch_23_repeated_component_scan_internal_fails() throws Exception {
        rejects(RepeatedHost.class, "internal", TopolithCode.TL0018);
    }

    @Test void ac_mode_24_repeated_component_scan_none_fails() throws Exception {
        rejects(RepeatedHost.class, "none", TopolithCode.TL0007);
    }

    @Test void ac_arch_23_excluded_module_scan_passes() throws Exception {
        Fixtures.module(root, "user", "fixture.defaults.ordinary", List.of("fixture.defaults.ordinary.api"));
        try (var loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ExcludingHost.class)
                    .withPropertyValues("topolith.modules.user.mode=internal")
                    .run(context -> assertNull(context.getStartupFailure()));
        }
    }

    private void rejects(Class<?> host, String mode, TopolithCode code) throws Exception {
        Fixtures.module(root, "user", Fixtures.USER, List.of(Fixtures.USER_API));
        try (var loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(host)
                    .withPropertyValues("topolith.modules.user.mode=" + mode).run(context -> {
                        Throwable error = context.getStartupFailure();
                        while (error != null && !(error instanceof TopolithException)) {
                            error = error.getCause();
                        }
                        assertNotNull(error);
                        assertEquals(code, ((TopolithException) error).code());
                    });
        }
    }

    @Test void ac_arch_21_none_servlet_scan_fails() throws Exception {
        rejects(ServletHost.class, "none", TopolithCode.TL0007);
    }
    @Test void ac_arch_22_none_properties_scan_fails() throws Exception {
        rejects(PropertiesHost.class, "none", TopolithCode.TL0007);
    }
    @Test void ac_arch_23_internal_component_scan_fails() throws Exception {
        rejects(ComponentHost.class, "internal", TopolithCode.TL0018);
    }
    @Test void ac_arch_24_internal_servlet_scan_fails() throws Exception {
        rejects(ServletHost.class, "internal", TopolithCode.TL0018);
    }
    @Test void ac_arch_25_internal_properties_scan_fails() throws Exception {
        rejects(PropertiesHost.class, "internal", TopolithCode.TL0018);
    }
}
