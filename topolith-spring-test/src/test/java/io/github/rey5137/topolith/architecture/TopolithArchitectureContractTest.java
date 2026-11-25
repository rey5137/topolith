package io.github.rey5137.topolith.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.rey5137.topolith.architecture.fixtures.contracts.EntityOrder;
import io.github.rey5137.topolith.architecture.fixtures.contracts.FileStreamParam;
import io.github.rey5137.topolith.architecture.fixtures.contracts.GenericBase;
import io.github.rey5137.topolith.architecture.fixtures.contracts.GenericContract;
import io.github.rey5137.topolith.architecture.fixtures.contracts.InheritedBase;
import io.github.rey5137.topolith.architecture.fixtures.contracts.InheritedContract;
import io.github.rey5137.topolith.architecture.fixtures.contracts.IntStreamReturn;
import io.github.rey5137.topolith.architecture.fixtures.contracts.NestedContract;
import io.github.rey5137.topolith.architecture.fixtures.contracts.PageSubtypeReturn;
import io.github.rey5137.topolith.architecture.fixtures.contracts.PagedResult;
import io.github.rey5137.topolith.architecture.fixtures.contracts.SliceParam;
import io.github.rey5137.topolith.architecture.fixtures.contracts.EntityParam;
import io.github.rey5137.topolith.architecture.fixtures.contracts.EntityReturn;
import io.github.rey5137.topolith.architecture.fixtures.contracts.InputStreamParam;
import io.github.rey5137.topolith.architecture.fixtures.contracts.InputStreamReturn;
import io.github.rey5137.topolith.architecture.fixtures.contracts.NestedPageReturn;
import io.github.rey5137.topolith.architecture.fixtures.contracts.PageParam;
import io.github.rey5137.topolith.architecture.fixtures.contracts.PageReturn;
import io.github.rey5137.topolith.architecture.fixtures.contracts.ProxyParam;
import io.github.rey5137.topolith.architecture.fixtures.contracts.ProxyReturn;
import io.github.rey5137.topolith.architecture.fixtures.contracts.SliceReturn;
import io.github.rey5137.topolith.architecture.fixtures.contracts.StreamParam;
import io.github.rey5137.topolith.architecture.fixtures.contracts.StreamReturn;
import io.github.rey5137.topolith.architecture.fixtures.contracts.ValueContract;
import io.github.rey5137.topolith.core.ApiDescriptor;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.spring.test.TopolithArchitecture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopolithArchitectureContractTest {
    private static final String PACKAGE = "io.github.rey5137.topolith.architecture.fixtures.contracts";
    private static final List<ModuleDescriptor> MODULES = List.of(ModuleDescriptor.builder("catalog")
            .packageName("io.github.rey5137.topolith.architecture.fixtures")
            .apiPackages(List.of(PACKAGE)).apiVersion("1.0.0").build());

    private static void rejects(Class<?> contract, String exposed) {
        JavaClasses classes = new ClassFileImporter().importClasses(contract, EntityOrder.class);
        AssertionError error = assertThrows(AssertionError.class,
                () -> TopolithArchitecture.verify(MODULES, List.of(), classes));
        String message = error.getMessage();
        assertTrue(message.contains("module 'catalog'") && message.contains(contract.getSimpleName())
                && message.contains(exposed), message);
    }

    @Test
    void ac_arch_05_page_in_a_return_type_fails() {
        rejects(PageReturn.class, "org.springframework.data.domain.Page");
    }

    @Test
    void ac_arch_05_page_in_a_parameter_fails() {
        rejects(PageParam.class, "org.springframework.data.domain.Page");
    }

    @Test
    void ac_arch_05_page_in_a_nested_generic_argument_fails() {
        rejects(NestedPageReturn.class, "org.springframework.data.domain.Page");
    }

    @Test
    void ac_arch_05_slice_fails() {
        rejects(SliceReturn.class, "org.springframework.data.domain.Slice");
    }

    @Test
    void ac_arch_05_stream_in_a_return_type_fails() {
        rejects(StreamReturn.class, "java.util.stream.Stream");
    }

    @Test
    void ac_arch_05_stream_in_a_parameter_fails() {
        rejects(StreamParam.class, "java.util.stream.Stream");
    }

    @Test
    void ac_arch_05_entity_in_a_return_type_fails() {
        rejects(EntityReturn.class, EntityOrder.class.getName());
    }

    @Test
    void ac_arch_05_entity_in_a_generic_parameter_fails() {
        rejects(EntityParam.class, EntityOrder.class.getName());
    }

    @Test
    void ac_arch_05_input_stream_in_a_return_type_fails() {
        rejects(InputStreamReturn.class, "java.io.InputStream");
    }

    @Test
    void ac_arch_05_input_stream_in_a_parameter_fails() {
        rejects(InputStreamParam.class, "java.io.InputStream");
    }

    @Test
    void ac_arch_05_lazy_proxy_in_a_return_type_fails() {
        rejects(ProxyReturn.class, "org.hibernate.proxy.HibernateProxy");
    }

    @Test
    void ac_arch_05_lazy_proxy_in_a_parameter_fails() {
        rejects(ProxyParam.class, "org.hibernate.proxy.HibernateProxy");
    }

    @Test
    void ac_arch_06_a_contract_of_value_types_and_a_contract_exception_passes() {
        JavaClasses classes = new ClassFileImporter().importClasses(ValueContract.class, ValueContract.Line.class);
        assertDoesNotThrow(() -> TopolithArchitecture.verify(MODULES, List.of(), classes));
    }

    @Test
    void ac_arch_05_only_the_provided_contracts_are_checked_when_the_api_descriptor_lists_them() {
        ApiDescriptor api = ApiDescriptor.builder("catalog").apiPackages(List.of(PACKAGE))
                .provides(List.of(ValueContract.class.getName())).apiVersion("1.0.0").build();
        JavaClasses classes = new ClassFileImporter().importClasses(ValueContract.class, PageReturn.class);
        assertDoesNotThrow(() -> TopolithArchitecture.verify(MODULES, List.of(api), classes));
    }

    @Test
    void ac_arch_05_slice_in_a_parameter_fails() {
        rejects(SliceParam.class, "org.springframework.data.domain.Slice");
    }

    @Test
    void ac_arch_05_a_page_subtype_fails() {
        rejects(PageSubtypeReturn.class, PagedResult.class.getName());
    }

    @Test
    void ac_arch_05_a_stream_subtype_fails() {
        rejects(IntStreamReturn.class, "java.util.stream.IntStream");
    }

    @Test
    void ac_arch_05_an_input_stream_subtype_fails() {
        rejects(FileStreamParam.class, "java.io.FileInputStream");
    }

    private static void rejectsProvided(Class<?> provided, Class<?>... imported) {
        ApiDescriptor api = ApiDescriptor.builder("catalog").apiPackages(List.of(PACKAGE))
                .provides(List.of(provided.getName())).apiVersion("1.0.0").build();
        JavaClasses classes = new ClassFileImporter().importClasses(imported);
        AssertionError error = assertThrows(AssertionError.class,
                () -> TopolithArchitecture.verify(MODULES, List.of(api), classes));
        assertTrue(error.getMessage().contains("module 'catalog'"), error.getMessage());
    }

    @Test
    void ac_arch_05_a_nested_contract_named_by_its_binary_name_in_provides_is_checked() {
        rejectsProvided(NestedContract.OrderApi.class, NestedContract.OrderApi.class);
    }

    @Test
    void ac_arch_05_methods_a_contract_inherits_are_checked() {
        rejectsProvided(InheritedContract.class, InheritedContract.class, InheritedBase.class);
    }

    @Test
    void ac_arch_05_a_prohibited_type_in_a_super_interface_argument_is_checked() {
        rejectsProvided(GenericContract.class, GenericContract.class, GenericBase.class);
    }

    @Test
    void ac_arch_03_a_cycle_fails_even_when_no_class_is_imported() {
        ModuleDescriptor first = ModuleDescriptor.builder("first").packageName("x.first")
                .apiPackages(List.of("x.first.api")).requires(List.of("second")).apiVersion("1.0.0").build();
        ModuleDescriptor second = ModuleDescriptor.builder("second").packageName("x.second")
                .apiPackages(List.of("x.second.api")).requires(List.of("first")).apiVersion("1.0.0").build();
        AssertionError error = assertThrows(AssertionError.class, () -> TopolithArchitecture
                .verify(List.of(first, second), List.of(), new ClassFileImporter().importClasses()));
        assertTrue(error.getMessage().contains(TopolithCode.TL0008.name()), error.getMessage());
    }

    @Test
    void ac_arch_01_duplicate_module_ids_fail_as_a_descriptor_problem_not_a_cycle() {
        ModuleDescriptor copy = ModuleDescriptor.builder("catalog").packageName("x.catalog")
                .apiPackages(List.of("x.catalog.api")).apiVersion("1.0.0").build();
        AssertionError error = assertThrows(AssertionError.class, () -> TopolithArchitecture
                .verify(List.of(MODULES.get(0), copy), List.of(), new ClassFileImporter().importClasses()));
        assertTrue(error.getMessage().contains(TopolithCode.TL0006.name()), error.getMessage());
        assertTrue(!error.getMessage().contains(TopolithCode.TL0008.name()), error.getMessage());
    }

    @Test
    void ac_arch_01_an_api_package_listed_by_two_modules_fails_instead_of_picking_an_owner() {
        ModuleDescriptor audit = ModuleDescriptor.builder("audit").packageName("x.audit")
                .apiPackages(List.of("x.shared.api")).apiVersion("1.0.0").build();
        ModuleDescriptor billing = ModuleDescriptor.builder("billing").packageName("x.billing")
                .apiPackages(List.of("x.shared.api")).apiVersion("1.0.0").build();
        AssertionError error = assertThrows(AssertionError.class, () -> TopolithArchitecture
                .verify(List.of(audit, billing), List.of(), new ClassFileImporter().importClasses()));
        assertTrue(error.getMessage().contains("x.shared.api") && error.getMessage().contains("audit, billing"),
                error.getMessage());
    }
}
