package io.github.rey5137.topolith.samples.user.service;

import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.Topology;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Starts the service with its shipped config (payment external at its fixed url); only the datasource changes. */
@SpringBootTest(classes = UserServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.location=classpath:/config/user-service/",
                "spring.datasource.url=jdbc:h2:mem:user-service-test;DB_CLOSE_DELAY=-1"
        })
class UserServiceSmokeTest {
    @Autowired
    ApplicationContext context;

    /** The executable artifact itself is required after {@code package} by the pom's {@code requireFilesExist}. */
    @Test
    void ac_build_08_user_api_and_module_descriptors_are_on_the_service_classpath() {
        ClassLoader loader = getClass().getClassLoader();
        assertNotNull(loader.getResource("META-INF/topolith/apis/user.properties"));
        assertNotNull(loader.getResource("META-INF/topolith/modules/user.properties"));
    }

    @Test
    void ac_build_08_standalone_service_runs_only_its_own_module_internal() {
        Topology topology = context.getBean(Topology.class);
        assertEquals(Mode.INTERNAL, topology.mode("user"));
        assertEquals(Mode.EXTERNAL, topology.mode("payment"));
    }
}
