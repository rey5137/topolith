package io.github.rey5137.topolith.samples.hosts.paymenthost;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import javax.sql.DataSource;

import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.samples.paymenttests.AbstractPaymentTest;
import io.github.rey5137.topolith.samples.paymenttests.PaymentObservations;
import io.github.rey5137.topolith.samples.user.UserAutoConfiguration;
import io.github.rey5137.topolith.samples.user.api.UserApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = PaymentHostApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(PaymentObservations.class)
class PaymentHostPaymentTest extends AbstractPaymentTest {
    private static final String USER_PACKAGE = "io.github.rey5137.topolith.samples.user";

    @Autowired
    ConfigurableApplicationContext context;

    @Autowired
    DataSource dataSource;

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    @Override
    protected Mode paymentMode() {
        return Mode.INTERNAL;
    }

    @Test
    void ac_test_06_user_module_leaves_no_bean_route_table_or_auto_configuration() throws Exception {
        ConfigurableListableBeanFactory factory = context.getBeanFactory();
        for (String name : factory.getBeanDefinitionNames()) {
            Class<?> type = factory.getType(name, false);
            if (type != null) {
                assertFalse(type.getName().startsWith(USER_PACKAGE), "user bean '" + name + "' (" + type + ")");
            }
        }
        assertEquals(0, factory.getBeanNamesForType(UserApi.class, true, false).length, "UserApi beans");
        assertEquals(0, factory.getBeanNamesForType(UserAutoConfiguration.UserMarker.class, true, false).length,
                "user auto-configuration marker");
        List<String> userMappings = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream())
                .filter(pattern -> pattern.equals("/user") || pattern.startsWith("/user/"))
                .toList();
        assertTrue(userMappings.isEmpty(), "user routes " + userMappings);

        Set<String> tables = new TreeSet<>();
        try (Connection connection = dataSource.getConnection();
                ResultSet result = connection.getMetaData().getTables(null, null, "%", new String[] {"TABLE"})) {
            while (result.next()) {
                tables.add(result.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
        }
        assertFalse(tables.contains("user_order"), "user table " + tables);
        assertFalse(tables.contains("flyway_schema_history_user"), "user migration history " + tables);
        assertTrue(tables.contains("payment_charge"), "payment table " + tables);
    }
}
