package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.nio.file.Path;

import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.AccountController;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountContract;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountException;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountFailure;
import io.github.rey5137.topolith.spring.boot.errorfixture.account.api.AccountNotFoundException;
import io.github.rey5137.topolith.spring.boot.errorfixture.host.ClientHost;
import io.github.rey5137.topolith.spring.boot.errorfixture.payment.api.PaymentDeclinedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * INV-6: the same contract assertions hold whether the account module is internal or external (R-EXT-04). M2.5 adds
 * its wirings here.
 */
class ContractParityTest {
    @TempDir
    Path temp;
    Path root;

    @BeforeEach
    void roots() throws IOException {
        root = Fixtures.dir(temp, "roots");
        ContractExceptionsTest.contracts(root, "account", AccountContract.class);
        ContractExceptionsTest.contracts(root, "payment");
    }

    /** What every caller of {@link AccountContract} observes, in any mode. */
    static void assertContract(AccountContract account) {
        assertEquals("found", account.find("ok"));
        assertEquals("no such account", assertThrows(AccountNotFoundException.class, () -> account.find("missing"))
                .getMessage());
        assertEquals("account is locked", assertThrows(AccountException.class, () -> account.find("locked"))
                .getMessage());
        assertEquals("after a partial answer", assertThrows(AccountException.class, account::partial).getMessage());
        assertEquals("card declined", assertThrows(PaymentDeclinedException.class, () -> account.find("declined"))
                .getMessage());
        assertEquals("payment", assertThrows(ModuleUnavailableException.class, () -> account.find("unavailable"))
                .moduleId());
        RuntimeException undeclared = assertThrows(RuntimeException.class, () -> account.find("secret"));
        assertFalse(undeclared instanceof AccountFailure || undeclared instanceof ModuleUnavailableException);
    }

    @Test
    void ac_ext_09_and_ac_ext_10_internal_wiring_meets_the_contract() throws Exception {
        try (Fixtures.RecordingLoader loader = new Fixtures.RecordingLoader(root)) {
            new ApplicationContextRunner().withClassLoader(loader).withUserConfiguration(ClientHost.class)
                    .withPropertyValues("spring.liquibase.enabled=false", "topolith.modules.account.mode=internal",
                            "topolith.modules.payment.mode=none")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        AccountContract account = context.getBean(AccountContract.class);
                        assertInstanceOf(AccountController.class, account);
                        assertContract(account);
                    });
        }
    }

    @Test
    void ac_ext_09_and_ac_ext_10_external_wiring_meets_the_contract() throws Exception {
        ProviderErrorsTest.serve(root, "internal", provider -> {
            try {
                CallerErrorsTest.call(root, "http://127.0.0.1:" + ProviderErrorsTest.port(provider), context -> {
                    AccountContract account = context.getBean(AccountContract.class);
                    assertFalse(account instanceof AccountController);
                    assertContract(account);
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
