package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

import io.github.rey5137.topolith.core.Topology;
import io.github.rey5137.topolith.spring.boot.fixture.host.Hosts.Host;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.endpoint.InvocationContext;
import org.springframework.boot.actuate.endpoint.OperationType;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.invoke.convert.ConversionServiceParameterValueMapper;
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes;
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpointDiscoverer;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ApplicationAvailabilityBean;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class TopologyStatusTest {
    @TempDir
    Path root;

    private void descriptors(String apiVersion) throws Exception {
        Fixtures.module(root, "user", Fixtures.USER, List.of(Fixtures.USER_API));
        Fixtures.resource(root, "META-INF/topolith/apis/user.properties", "format-version=1\nid=user\n"
                + "api-packages=" + Fixtures.USER_API + "\napi-version=" + apiVersion + "\n");
    }

    private void runCloudDetection(boolean cloud, boolean integration) throws Exception {
        Fixtures.module(root, "user", Fixtures.USER, List.of(Fixtures.USER_API));
        Path classRoot = root.resolve("detection");
        Path cloudSource = classRoot.resolve(
                "org/springframework/cloud/context/environment/EnvironmentChangeEvent.java");
        Path integrationSource = classRoot.resolve(
                "io/github/rey5137/topolith/spring/cloud/TopolithRefreshListener.java");
        java.nio.file.Files.createDirectories(cloudSource.getParent());
        java.nio.file.Files.createDirectories(integrationSource.getParent());
        java.nio.file.Files.writeString(cloudSource,
                "package org.springframework.cloud.context.environment; public class EnvironmentChangeEvent {}\n");
        java.nio.file.Files.writeString(integrationSource,
                "package io.github.rey5137.topolith.spring.cloud; public class TopolithRefreshListener {}\n");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                cloudSource.toString(), integrationSource.toString()));
        try (var parent = new java.net.URLClassLoader(new URL[] {classRoot.toUri().toURL()},
                getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("org.springframework.cloud.context.environment.EnvironmentChangeEvent")) {
                    if (!cloud) {
                        throw new ClassNotFoundException(name);
                    }
                }
                if (name.equals("io.github.rey5137.topolith.spring.cloud.TopolithRefreshListener")) {
                    if (!integration) {
                        throw new ClassNotFoundException(name);
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
                Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(parent, root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(Host.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.user.mode=none")
                    .run(context -> assertNull(context.getStartupFailure()));
        }
    }

    @Test
    void ac_mode_18_cloud_without_integration_warns(CapturedOutput output) throws Exception {
        runCloudDetection(true, false);
        assertTrue(output.getAll().contains("TL0014"));
        assertTrue(output.getAll().contains("Add topolith-spring-cloud"));
    }

    @Test
    void ac_mode_19_no_cloud_does_not_warn(CapturedOutput output) throws Exception {
        runCloudDetection(false, false);
        assertFalse(output.getAll().contains("TL0014"));
    }

    @Test
    void ac_mode_20_cloud_with_integration_does_not_warn(CapturedOutput output) throws Exception {
        runCloudDetection(true, true);
        assertFalse(output.getAll().contains("TL0014"));
    }

    @Test
    void ac_val_05_warns_once_at_readiness_without_changing_state(CapturedOutput output) throws Exception {
        descriptors("2.0");
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(Host.class)
                    .withBean(ApplicationAvailability.class, ApplicationAvailabilityBean::new)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.user.mode=internal")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        assertFalse(output.getAll().contains("TL0023"));
                        var source = context.getSourceApplicationContext();
                        AvailabilityChangeEvent.publish(source, ReadinessState.ACCEPTING_TRAFFIC);
                        ApplicationReadyEvent event = new ApplicationReadyEvent(new SpringApplication(), new String[0],
                                source, Duration.ZERO);
                        source.publishEvent(event);
                        source.publishEvent(event);
                        assertEquals(1, output.getAll().split("TL0023", -1).length - 1);
                        assertTrue(output.getAll().contains("module 'user'"));
                        assertTrue(output.getAll().contains("Align the host API JAR and the implementation JAR"));
                        assertEquals(ReadinessState.ACCEPTING_TRAFFIC,
                                context.getBean(ApplicationAvailability.class).getReadinessState());
                    });
        }
    }

    @Test
    void ac_val_05_matching_major_and_non_internal_modes_do_not_warn(CapturedOutput output) throws Exception {
        descriptors("01-rc1");
        for (String mode : List.of("internal", "none", "external")) {
            if (!mode.equals("internal")) {
                descriptors("2.0");
            }
            try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
                new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(Host.class)
                        .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.user.mode=" + mode,
                                "topolith.modules.user.remote.url=http://localhost")
                        .run(context -> {
                            assertNull(context.getStartupFailure());
                            var source = context.getSourceApplicationContext();
                            source.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0],
                                    source, Duration.ZERO));
                        });
            }
        }
        assertFalse(output.getAll().contains("TL0023"));
    }

    @Test
    void ac_val_08_endpoint_reports_sorted_known_modes() throws Exception {
        Fixtures.module(root, "user", Fixtures.USER, List.of(Fixtures.USER_API));
        Fixtures.module(root, "payment", Fixtures.PAYMENT, List.of(Fixtures.PAYMENT + ".api"));
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(Host.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.user.mode=internal",
                            "topolith.modules.payment.mode=none", "management.endpoints.web.exposure.include=topolith")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        TopolithEndpoint endpoint = context.getBean(TopolithEndpoint.class);
                        assertEquals(Map.of("restartPending", false, "modules", Map.of("payment", Map.of("mode", "none"),
                                "user", Map.of("mode", "internal"))), endpoint.modules());
                        assertEquals(List.of("payment", "user"), ((Map<?, ?>) endpoint.modules().get("modules"))
                                .keySet().stream().toList());
                        assertSame(context.getBean("topolithTopology"), context.getBean(Topology.class));
                        var discoverer = new WebEndpointDiscoverer(context.getSourceApplicationContext(),
                                new ConversionServiceParameterValueMapper(), EndpointMediaTypes.DEFAULT, List.of(),
                                List.of(), List.of(), List.of(), List.of());
                        var exposed = discoverer.getEndpoints().stream()
                                .filter(item -> item.getEndpointId().toString().equals("topolith"))
                                .findFirst().orElseThrow();
                        assertEquals(1, exposed.getOperations().size());
                        var operation = exposed.getOperations().iterator().next();
                        assertEquals(OperationType.READ, operation.getType());
                        assertEquals(endpoint.modules(),
                                operation.invoke(new InvocationContext(SecurityContext.NONE, Map.of())));
                    });
        }
    }

    @Test
    void ac_val_08_host_without_actuator_starts() throws Exception {
        Fixtures.module(root, "user", Fixtures.USER, List.of(Fixtures.USER_API));
        try (FilteredClassLoader filtered =
                withoutActuatorAutoConfigurationResources("org.springframework.boot.actuate");
                Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(filtered, root)) {
            new ApplicationContextRunner().withClassLoader(loader)
                    .withUserConfiguration(Host.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.user.mode=none")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        assertEquals(0, context.getBeanNamesForType(TopolithEndpoint.class).length);
                    });
        }
    }
    @Test
    void ac_val_08_host_without_actuator_autoconfigure_does_not_register_endpoint() throws Exception {
        Fixtures.module(root, "user", Fixtures.USER, List.of(Fixtures.USER_API));
        try (FilteredClassLoader filtered =
                withoutActuatorAutoConfigurationResources("org.springframework.boot.actuate.autoconfigure");
                Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(filtered, root)) {
            new ApplicationContextRunner().withClassLoader(loader)
                    .withUserConfiguration(Host.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.user.mode=none",
                            "management.endpoints.web.exposure.include=topolith")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        assertEquals(0, context.getBeanNamesForType(TopolithEndpoint.class).length);
                    });
        }
    }

    private FilteredClassLoader withoutActuatorAutoConfigurationResources(String hiddenPackage) {
        return new FilteredClassLoader(hiddenPackage) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                return Collections.enumeration(Collections.list(super.getResources(name)).stream()
                        .filter(url -> !url.toString().contains("spring-boot-actuator-autoconfigure"))
                        .toList());
            }
        };
    }

}
