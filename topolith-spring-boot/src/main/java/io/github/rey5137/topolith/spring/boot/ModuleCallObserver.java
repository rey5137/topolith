package io.github.rey5137.topolith.spring.boot;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.core.Topology;
import io.github.rey5137.topolith.spring.boot.internal.DeclaredExceptions;
import io.github.rey5137.topolith.spring.boot.observation.DefaultModuleCallObservationConvention;
import io.github.rey5137.topolith.spring.boot.observation.ModuleCallContext;
import io.github.rey5137.topolith.spring.boot.observation.ModuleCallObservationConvention;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;

/**
 * The one {@code topolith.module.call} observation of a contract call (R-EXT-11, D-86 through D-89). The registry and
 * the convention are looked up on the first call, so no bean is created early; without a registry nothing is observed.
 * The call runs in the observation's scope, so Spring's {@code http.client.requests} is its child.
 */
final class ModuleCallObserver implements CallObserver {
    private static final ModuleCallObservationConvention DEFAULT = new DefaultModuleCallObservationConvention();

    private final BeanFactory beanFactory;
    private final Topology topology;
    private final CallerModules callers;
    private volatile Lookup lookup;

    private record Lookup(ObservationRegistry registry, ModuleCallObservationConvention convention) {
    }

    /** TL0033 when the host defines more than one convention bean (INV-4). */
    ModuleCallObserver(BeanFactory beanFactory, Topology topology) {
        this.beanFactory = beanFactory;
        this.topology = topology;
        this.callers = new CallerModules(topology);
        if (beanFactory instanceof ListableBeanFactory listable) {
            String[] names = listable.getBeanNamesForType(ModuleCallObservationConvention.class, true, false);
            if (names.length > 1) {
                throw new TopolithException(new Diagnostic(TopolithCode.TL0033, "more than one "
                        + "ModuleCallObservationConvention bean exists (" + String.join(", ", names)
                        + "), so the call observation's convention is ambiguous.",
                        "Keep one ModuleCallObservationConvention bean."));
            }
        }
    }

    @Override
    public Object observe(String module, Mode mode, Class<?> contract, String method, Call call) throws Throwable {
        Lookup found = lookup();
        if (found.registry() == null) {
            return call.proceed();
        }
        ModuleCallContext context = new ModuleCallContext(module, mode, contract, method);
        Observation observation = Observation.createNotStarted(found.convention(), DEFAULT, () -> context,
                found.registry());
        if (observation.isNoop()) {
            return call.proceed();
        }
        context.setCallerModule(callers.find(module, contract));
        if (module.equals(context.callerModule())) {
            // a module's call to its own contract is not observed (D-88)
            return call.proceed();
        }
        observation.start();
        try (Observation.Scope scope = observation.openScope()) {
            return call.proceed();
        } catch (Throwable failure) {
            context.setException(exception(failure));
            observation.error(failure);
            throw failure;
        } finally {
            observation.stop();
        }
    }

    @Override
    public Object current() {
        ObservationRegistry registry = lookup().registry();
        return registry == null ? null : registry.getCurrentObservation();
    }

    @Override
    public Reopened reopen(Object captured) {
        ObservationRegistry registry = lookup().registry();
        if (registry == null || !(captured instanceof Observation observation)
                || registry.getCurrentObservation() == observation) {
            return null;
        }
        Observation.Scope scope = observation.openScope();
        return scope::close;
    }

    /** The declared class the caller receives, by simple name, or {@code undeclared}. */
    private String exception(Throwable failure) {
        Class<?> declared = DeclaredExceptions.declared(failure.getClass(), topology.apiPackages());
        return declared == null ? ModuleCallContext.UNDECLARED : declared.getSimpleName();
    }

    private Lookup lookup() {
        Lookup known = lookup;
        if (known == null) {
            known = new Lookup(beanFactory.getBeanProvider(ObservationRegistry.class).getIfUnique(),
                    beanFactory.getBeanProvider(ModuleCallObservationConvention.class).getIfUnique());
            lookup = known;
        }
        return known;
    }
}
