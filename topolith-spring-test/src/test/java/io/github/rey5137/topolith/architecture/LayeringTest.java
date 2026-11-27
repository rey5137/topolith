package io.github.rey5137.topolith.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayeringTest {
    private static final String BASE = "io.github.rey5137.topolith";
    private static final String[] OPTIONAL = {BASE + ".spring.cloud.."};

    // INV-7: javax is excluded because not every javax package belongs to the JDK.
    private static final ArchRule CORE = classes().that()
            .resideInAnyPackage(BASE + ".core..", BASE + ".annotation..", BASE + ".contract..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "jdk..", BASE + ".core..", BASE + ".annotation..", BASE + ".contract..")
            .allowEmptyShould(true);
    private static final ArchRule SPRING = noClasses().that()
            .resideInAPackage(BASE + "..")
            .and().resideOutsideOfPackage(BASE + ".spring..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework..")
            .allowEmptyShould(true);
    private static final ArchRule OPTIONAL_ONLY = noClasses().that().resideOutsideOfPackages(OPTIONAL)
            .should().dependOnClassesThat().resideInAPackage("org.springframework.cloud..").allowEmptyShould(true);
    private static final ArchRule NO_MODULITH = noClasses().should().dependOnClassesThat()
            .resideInAPackage("org.springframework.modulith..").allowEmptyShould(true);
    // INV-7: each optional library stays in the classes that are guarded against its absence (R-EXT-11, R-EXT-12,
    // R-EXT-13). A nested class counts as its top-level class.
    private static final String SPRING_BOOT = BASE + ".spring.boot.";
    private static final ArchRule SECURITY_CONFINED = confined("org.springframework.security..",
            SPRING_BOOT + "BearerToken", SPRING_BOOT + "ClientCredentialsHook", SPRING_BOOT + "ExposedRequestMatcher");
    private static final ArchRule OBSERVATION_CONFINED = confined("io.micrometer.observation..",
            SPRING_BOOT + "ModuleCallObserver", SPRING_BOOT + "observation.ModuleCallContext",
            SPRING_BOOT + "observation.ModuleCallObservationConvention",
            SPRING_BOOT + "observation.DefaultModuleCallObservationConvention",
            BASE + ".spring.test.RestClientSerializer");
    private static final ArchRule SERVLET_CONFINED = confined("jakarta.servlet..",
            SPRING_BOOT + "ContextRoute", SPRING_BOOT + "ExposedRequestMatcher", SPRING_BOOT + "ExposureConfiguration",
            SPRING_BOOT + "ModuleExceptionResolver", SPRING_BOOT + "RouteMark");
    private static final ArchRule GUARDED = classes().that().resideInAnyPackage(OPTIONAL)
            .and(new com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass>(
                    "depend on an integration and are Spring configurations") {
                @Override public boolean test(com.tngtech.archunit.core.domain.JavaClass type) {
                    boolean integration = type.getDirectDependenciesFromSelf().stream().anyMatch(dependency -> {
                        String name = dependency.getTargetClass().getPackageName();
                        return name.startsWith("org.springframework.cloud.")
                                || name.startsWith("org.springframework.modulith.");
                    });
                    return integration
                            && type.isMetaAnnotatedWith(org.springframework.context.annotation.Configuration.class);
                }
            })
            .should(new ArchCondition<JavaClass>("be guarded by ConditionalOnClass") {
                @Override public void check(JavaClass type, ConditionEvents events) {
                    JavaClass current = type;
                    boolean guarded = false;
                    while (current != null) {
                        if (current.isAnnotatedWith(
                                org.springframework.boot.autoconfigure.condition.ConditionalOnClass.class)) {
                            guarded = true;
                            break;
                        }
                        current = current.getEnclosingClass().orElse(null);
                    }
                    events.add(new SimpleConditionEvent(type, guarded, type.getName() + " is not guarded"));
                }
            })
            .allowEmptyShould(true);

    private JavaClasses production() {
        return new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages(BASE);
    }

    @Test void ac_build_03_core_uses_only_jdk() { CORE.check(production()); }
    @Test void ac_build_04_spring_stays_in_spring_modules() { SPRING.check(production()); }
    @Test void ac_build_05_integrations_stay_in_optional_modules() {
        OPTIONAL_ONLY.check(production());
        NO_MODULITH.check(production());
    }
    @Test void ac_build_05_security_micrometer_and_servlet_stay_in_guarded_classes() {
        SECURITY_CONFINED.check(production());
        OBSERVATION_CONFINED.check(production());
        SERVLET_CONFINED.check(production());
    }
    @Test void ac_build_06_optional_configurations_are_guarded() { GUARDED.check(production()); }
    @Test void ac_build_03_fails_on_bad_fixture() {
        assertViolation(CORE, BASE + ".core.bad", "BadCore");
    }

    @Test void ac_build_03_fails_on_bad_annotation_fixture() {
        assertViolation(CORE, BASE + ".annotation.bad", "BadAnnotation");
    }

    @Test void ac_build_04_fails_on_bad_fixture() {
        assertViolation(SPRING, BASE + ".processor.bad", "BadProcessor");
        assertViolation(SPRING, BASE + ".core.bad", "BadCore");
    }

    @Test void ac_build_05_fails_on_bad_cloud_fixture() {
        assertViolation(OPTIONAL_ONLY, BASE + ".spring.boot.badcloud", "BadCloud");
    }

    @Test void ac_build_05_fails_on_bad_modulith_fixture() {
        assertViolation(NO_MODULITH, BASE + ".spring.boot.badmodulith", "BadModulith");
    }

    @Test void ac_build_06_fails_on_bad_fixture() {
        assertViolation(GUARDED, BASE + ".spring.cloud.bad", "BadConfiguration");
    }

    @Test void ac_build_06_passes_on_guarded_fixture() {
        assertFalse(GUARDED.allowEmptyShould(false).evaluate(fixture(BASE + ".spring.cloud.good")).hasViolation());
    }

    @Test void ac_build_03_production_import_covers_every_module() {
        JavaClasses classes = production();
        for (String module : new String[] {"core", "processor", "spring.boot", "spring.cloud", "spring.test"}) {
            assertTrue(classes.contain(BASE + "." + module + ".package-info"), module);
        }
    }

    @Test void ac_build_04_fails_on_other_bad_fixture() {
        assertViolation(SPRING, BASE + ".other.bad", "BadOther");
    }

    @Test void ac_build_06_fails_on_composed_configuration() {
        assertViolation(GUARDED, BASE + ".spring.cloud.composed", "ComposedConfiguration");
    }

    @Test void ac_build_06_passes_when_enclosing_configuration_is_guarded() {
        assertFalse(GUARDED.allowEmptyShould(false).evaluate(fixture(BASE + ".spring.cloud.nested")).hasViolation());
    }

    @Test void ac_build_03_to_06_production_excludes_fixtures() {
        JavaClasses classes = production();
        for (String name : new String[] {"core.bad.BadCore", "annotation.bad.BadAnnotation",
                "processor.bad.BadProcessor", "spring.boot.badcloud.BadCloud", "spring.boot.badmodulith.BadModulith",
                "spring.cloud.bad.BadConfiguration", "spring.cloud.good.GoodConfiguration", "other.bad.BadOther",
                "spring.cloud.composed.ComposedConfiguration",
                "spring.cloud.nested.GuardedOuter$NestedConfiguration"}) {
            assertFalse(classes.contain(BASE + "." + name), name);
        }
    }

    private static ArchRule confined(String library, String... allowed) {
        return noClasses().that(new com.tngtech.archunit.base.DescribedPredicate<JavaClass>(
                "are not one of " + String.join(", ", allowed)) {
            @Override public boolean test(JavaClass type) {
                String outer = type.getName().split("\\$")[0];
                return !java.util.Arrays.asList(allowed).contains(outer);
            }
        }).should().dependOnClassesThat().resideInAPackage(library).allowEmptyShould(true);
    }

    private JavaClasses fixture(String packageName) {
        return new ClassFileImporter().importPackages(packageName);
    }

    private void assertViolation(ArchRule rule, String packageName, String fixtureName) {
        EvaluationResult result = rule.evaluate(fixture(packageName));
        assertTrue(result.hasViolation());
        assertTrue(result.getFailureReport().toString().contains(fixtureName));
    }
}
