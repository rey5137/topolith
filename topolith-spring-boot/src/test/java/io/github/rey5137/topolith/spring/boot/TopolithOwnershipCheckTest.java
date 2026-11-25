package io.github.rey5137.topolith.spring.boot;

import java.nio.file.Path;
import java.util.List;

import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.spring.boot.fixture.user.UserComponent;
import io.github.rey5137.topolith.spring.boot.fixture.user.UserConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportResource;

import static org.junit.jupiter.api.Assertions.*;

class TopolithOwnershipCheckTest {
    @TempDir
    Path root;

    @Configuration(proxyBeanMethods = false)
    @Import({TopolithAutoConfiguration.class, UserConfiguration.class})
    static class ModuleOwnedHost {
    }

    @Configuration(proxyBeanMethods = false)
    @Import(TopolithAutoConfiguration.class)
    static class HostOwnedBean {
        @Bean
        UserComponent hostBean() {
            return new UserComponent();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(TopolithAutoConfiguration.class)
    @ImportResource("classpath:module-beans.xml")
    static class ResourceHost {
    }

    @Configuration(proxyBeanMethods = false)
    @Import(TopolithAutoConfiguration.class)
    @ImportResource("classpath:io/github/rey5137/topolith/spring/boot/fixture/user/module-beans.xml")
    static class ModuleResourceHost { }

    @Test
    void ac_arch_18_resource_owned_bean_fails() throws Exception {
        Fixtures.module(root, "user", Fixtures.USER, List.of(Fixtures.USER_API));
        Fixtures.resource(root, Fixtures.USER.replace('.', '/') + "/module-beans.xml", """
                <beans xmlns="http://www.springframework.org/schema/beans"
                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                       xsi:schemaLocation="http://www.springframework.org/schema/beans
                       https://www.springframework.org/schema/beans/spring-beans.xsd">
                  <bean id="fromXml" class="java.lang.Object"/>
                </beans>
                """);
        try (var loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ModuleResourceHost.class)
                    .withPropertyValues("topolith.modules.user.mode=none").run(context -> {
                        Throwable error = context.getStartupFailure();
                        while (error != null && !(error instanceof TopolithException)) {
                            error = error.getCause();
                        }
                        assertNotNull(error);
                        assertEquals(TopolithCode.TL0007, ((TopolithException) error).code());
                        assertTrue(error.getMessage().contains("module-beans.xml"));
                    });
        }
    }

    public static class HostXmlBean { }

    private void xmlBean(Path xmlRoot, String className) throws Exception {
        Fixtures.resource(xmlRoot, "module-beans.xml", """
                <beans xmlns="http://www.springframework.org/schema/beans"
                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                       xsi:schemaLocation="http://www.springframework.org/schema/beans
                       https://www.springframework.org/schema/beans/spring-beans.xsd">
                  <bean id="fromXml" class="%s"/>
                </beans>
                """.formatted(className));
    }

    @Test
    void jar_resource_with_single_module_descriptor_is_owned() throws Exception {
        Path classes = root.resolve("classes");
        Fixtures.module(classes, "user", Fixtures.USER, List.of(Fixtures.USER_API));
        xmlBean(classes, "java.lang.Object");
        Path jar = root.resolve("module.jar");
        try (var out = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar));
                var paths = java.nio.file.Files.walk(classes)) {
            for (Path path : paths.filter(p -> !p.equals(classes)).sorted().toList()) {
                String name = classes.relativize(path).toString().replace('\\', '/');
                out.putNextEntry(new java.util.jar.JarEntry(name + (java.nio.file.Files.isDirectory(path) ? "/" : "")));
                if (java.nio.file.Files.isRegularFile(path)) {
                    java.nio.file.Files.copy(path, out);
                }
                out.closeEntry();
            }
        }
        try (var loader = new Fixtures.RecordingLoader(jar)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ResourceHost.class)
                    .withPropertyValues("topolith.modules.user.mode=none").run(context -> {
                        Throwable error = context.getStartupFailure();
                        while (error != null && !(error instanceof TopolithException)) { error = error.getCause(); }
                        assertNotNull(error);
                        assertEquals(TopolithCode.TL0007, ((TopolithException) error).code());
                    });
        }
    }

    @Test
    void host_xml_declaring_none_module_class_fails() throws Exception {
        Fixtures.module(root, "user", Fixtures.USER, List.of(Fixtures.USER_API));
        Path host = root.resolve("host");
        xmlBean(host, UserComponent.class.getName());
        try (var loader = new Fixtures.RecordingLoader(host, root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ResourceHost.class)
                    .withPropertyValues("topolith.modules.user.mode=none").run(context -> {
                        Throwable error = context.getStartupFailure();
                        while (error != null && !(error instanceof TopolithException)) { error = error.getCause(); }
                        assertNotNull(error);
                        assertEquals(TopolithCode.TL0007, ((TopolithException) error).code());
                    });
        }
    }

    @Test
    void host_xml_in_shared_root_declaring_host_class_passes() throws Exception {
        Fixtures.module(root, "user", Fixtures.USER, List.of(Fixtures.USER_API));
        xmlBean(root, HostXmlBean.class.getName());
        try (var loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ResourceHost.class)
                    .withPropertyValues("topolith.modules.user.mode=none")
                    .run(context -> assertNull(context.getStartupFailure()));
        }
    }

    @Test
    void ac_arch_27_none_management_import_fails_without_registration() throws Exception {
        Fixtures.module(root, "user", Fixtures.USER, List.of(Fixtures.USER_API));
        String resource = "META-INF/spring/org.springframework.boot.actuate.autoconfigure.web."
                + "ManagementContextConfiguration.imports";
        Fixtures.resource(root, resource, UserConfiguration.class.getName());
        try (var loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(HostOwnedBean.class)
                    .withPropertyValues("topolith.modules.user.mode=none").run(context -> {
                        Throwable error = context.getStartupFailure();
                        while (error != null && !(error instanceof TopolithException)) { error = error.getCause(); }
                        assertNotNull(error);
                        assertEquals(TopolithCode.TL0007, ((TopolithException) error).code());
                        assertTrue(error.getMessage().contains(resource));
                    });
        }
    }

    @Test
    void management_import_is_ignored_without_actuator() throws Exception {
        Fixtures.module(root, "user", Fixtures.USER, List.of(Fixtures.USER_API));
        Fixtures.resource(root, "META-INF/spring/org.springframework.boot.actuate.autoconfigure.web."
                + "ManagementContextConfiguration.imports", UserConfiguration.class.getName());
        var parent = new org.springframework.boot.test.context.FilteredClassLoader(
                "org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration");
        try (var loader = new Fixtures.RecordingLoader(parent, root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(HostOwnedBean.class)
                    .withPropertyValues("topolith.modules.user.mode=none")
                    .run(context -> assertNull(context.getStartupFailure()));
        }
    }

    @Test
    void ac_arch_34_spring_factories_entries_are_ignored() throws Exception {
        Fixtures.module(root, "user", Fixtures.USER, List.of(Fixtures.USER_API));
        Fixtures.resource(root, "META-INF/spring.factories",
                "org.springframework.boot.autoconfigure.EnableAutoConfiguration=" + UserConfiguration.class.getName());
        try (var loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(HostOwnedBean.class)
                    .withPropertyValues("topolith.modules.user.mode=none").run(context -> {
                        assertNull(context.getStartupFailure());
                        assertFalse(context.containsBean("userSharedService"));
                    });
        }
    }

    private void run(Class<?> host, boolean fails) throws Exception {
        Fixtures.module(root, "user", Fixtures.USER, List.of(Fixtures.USER_API));
        try (var loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(host)
                    .withPropertyValues("topolith.modules.user.mode=none")
                    .run(context -> {
                        Throwable error = context.getStartupFailure();
                        if (!fails) {
                            assertNull(error);
                            assertNotNull(context.getBean(UserComponent.class));
                            return;
                        }
                        while (error != null && !(error instanceof TopolithException)) {
                            error = error.getCause();
                        }
                        assertNotNull(error);
                        assertEquals(TopolithCode.TL0007, ((TopolithException) error).code());
                        assertTrue(error.getMessage().contains("user"));
                        assertTrue(error.getMessage().contains(UserConfiguration.class.getName()));
                        assertFalse(error.getMessage().contains("from null"));
                    });
        }
    }

    @Test
    void ac_arch_09_non_internal_declaration_fails() throws Exception {
        run(ModuleOwnedHost.class, true);
    }

    @Test
    void ac_arch_10_host_declared_module_type_passes() throws Exception {
        run(HostOwnedBean.class, false);
    }
}
