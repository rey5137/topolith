package io.github.rey5137.topolith.spring.boot;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.PriorityOrdered;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.ClassUtils;

/**
 * The advice on an internal module's provider beans: the D-2 transaction boundary (R-EXT-07, D-47) and the call
 * observation of an internal module (R-EXT-11, D-86), on one proxy with the observation in front.
 * Every call through a provided contract interface runs with {@code NOT_SUPPORTED}, so a caller's transaction is
 * suspended and the callee's own {@code @Transactional} decides; the advice sits outside Spring's transaction
 * interceptor, for every caller, the module's own code included. The boundary does nothing without a
 * {@link PlatformTransactionManager}, the observation nothing without an observer, and an external or {@code none}
 * module's beans never get either (INV-2). A final handler provider fails with TL0029 whichever advice would apply
 * (D-89); a loopback module's provider takes the boundary only, its call being observed on the client.
 */
final class ContractTransactionBoundary implements BeanPostProcessor, PriorityOrdered {
    /** The counter of calls that found a caller transaction (tagged {@code module} and {@code contract}). */
    static final String METRIC = "topolith.contract.calls.in.transaction";

    /** Holds the definition so this class loads without spring-tx. */
    private static final class Suspension {
        static final TransactionDefinition NOT_SUPPORTED = new DefaultTransactionDefinition(
                TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    /** Counts a contract call made inside a caller transaction. */
    interface CallCounter {
        void count(String module, String contract);
    }

    private static final boolean WEB = ClassUtils.isPresent(
            "org.springframework.web.context.request.RequestContextHolder",
            ContractTransactionBoundary.class.getClassLoader());

    private final BeanFactory beanFactory;
    private final CallCounter counter;
    private final boolean transactionApi;
    private volatile Boolean transactional;
    private volatile Optional<CallObserver> observer;

    ContractTransactionBoundary(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
        ClassLoader classLoader = beanFactory instanceof ConfigurableBeanFactory configurable
                ? configurable.getBeanClassLoader() : null;
        this.counter = ClassUtils.isPresent("io.micrometer.core.instrument.MeterRegistry", classLoader)
                ? new MicrometerCallCounter(beanFactory) : (module, contract) -> { };
        this.transactionApi = ClassUtils.isPresent("org.springframework.transaction.PlatformTransactionManager",
                classLoader);
    }

    /** After Spring's auto-proxy creator, so the advice can go in front of the transaction interceptor. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Class<?> type = AopProxyUtils.ultimateTargetClass(bean);
        TopologyResolver.Resolution resolution = beanFactory.getBean(TopologyResolver.RESOLUTION_BEAN,
                TopologyResolver.Resolution.class);
        String module = ModuleHandlers.internalOwner(resolution.topology(), type);
        if (module == null) {
            return bean;
        }
        List<Class<?>> contracts = contracts(resolution.contracts().getOrDefault(module, Set.of()), type);
        boolean observed = resolution.topology().mode(module) == Mode.INTERNAL;
        boolean transactional = hasTransactionManager();
        if (contracts.isEmpty() || !(observed || transactional)) {
            return bean;
        }
        boolean last = Modifier.isFinal(type.getModifiers()) && !AopUtils.isAopProxy(bean);
        if (last && handler(type)) {
            throw new TopolithException(new Diagnostic(TopolithCode.TL0029, "provider bean '" + beanName
                    + "' of module '" + module + "' is a final handler ("
                    + type.getName() + ") and cannot take the contract call advice (the call observation and the "
                    + "transaction boundary): an interface-only proxy would hide its routes and its class.",
                    "Make " + type.getSimpleName() + " non-final, as Spring's @Transactional requires."));
        }
        Provided provided = new Provided(contracts);
        List<Advisor> advisors = new ArrayList<>();
        if (observed) {
            advisors.add(new DefaultPointcutAdvisor(new Observed(module, provided)));
        }
        if (transactional) {
            advisors.add(new DefaultPointcutAdvisor(new Boundary(module, provided)));
        }
        if (bean instanceof Advised advised && !advised.isFrozen()) {
            for (int i = advisors.size() - 1; i >= 0; i--) {
                advised.addAdvisor(0, advisors.get(i));
            }
            return bean;
        }
        ProxyFactory factory = new ProxyFactory(bean);
        // a final non-handler (a lambda, a JDK proxy class) gets the interface proxy; a frozen CGLIB proxy stays
        // class-based
        factory.setProxyTargetClass(AopUtils.isCglibProxy(bean) || (!AopUtils.isAopProxy(bean) && !last));
        advisors.forEach(factory::addAdvisor);
        return factory.getProxy(type.getClassLoader());
    }

    /** The managers in bean-name order, looked up on each call so one created after startup is included. */
    private List<PlatformTransactionManager> managers() {
        return List.copyOf(new TreeMap<>(((ListableBeanFactory) beanFactory)
                .getBeansOfType(PlatformTransactionManager.class)).values());
    }

    /** Looked up once, by definition, so no transaction manager is created early. */
    private boolean hasTransactionManager() {
        Boolean known = transactional;
        if (known == null) {
            known = transactionApi && beanFactory instanceof ListableBeanFactory listable
                    && listable.getBeanNamesForType(PlatformTransactionManager.class, false, false).length > 0;
            transactional = known;
        }
        return known;
    }

    /** Whether {@code type} is a web handler, whose routes and class an interface-only proxy would hide. */
    private static boolean handler(Class<?> type) {
        return AnnotatedElementUtils.hasAnnotation(type, Controller.class)
                || AnnotatedElementUtils.isAnnotated(type, "org.springframework.web.bind.annotation.RequestMapping");
    }

    /** The contracts of {@code names} that {@code type} implements; a name that does not load is skipped. */
    private static List<Class<?>> contracts(Set<String> names, Class<?> type) {
        List<Class<?>> contracts = new ArrayList<>();
        for (String name : names) {
            try {
                Class<?> contract = Class.forName(name, false, type.getClassLoader());
                if (contract.isInterface() && contract.isAssignableFrom(type)) {
                    contracts.add(contract);
                }
            } catch (ClassNotFoundException | LinkageError e) {
                // TL0026 reports an external module's unloadable contract; here there is nothing to advise
            }
        }
        return contracts;
    }

    /** The contracts a provider bean implements, and which of them declares a called method. */
    private static final class Provided {
        private final List<Class<?>> contracts;
        private final Map<Method, Optional<Class<?>>> declaring = new ConcurrentHashMap<>();

        Provided(List<Class<?>> contracts) {
            this.contracts = contracts;
        }

        /** The contract declaring {@code method}, or null when the method is not part of one. */
        Class<?> contractOf(Method method) {
            return declaring.computeIfAbsent(method, this::declaringContract).orElse(null);
        }

        private Optional<Class<?>> declaringContract(Method method) {
            for (Class<?> contract : contracts) {
                try {
                    contract.getMethod(method.getName(), method.getParameterTypes());
                    return Optional.of(contract);
                } catch (NoSuchMethodException e) {
                    // not this contract's method
                }
            }
            return Optional.empty();
        }
    }

    private final class Boundary implements MethodInterceptor {
        private final String module;
        private final Provided provided;

        Boundary(String module, Provided provided) {
            this.module = module;
            this.provided = provided;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            Class<?> contract = provided.contractOf(invocation.getMethod());
            if (contract == null || !TransactionSynchronizationManager.isActualTransactionActive()) {
                return invocation.proceed();
            }
            counter.count(module, contract.getName());
            return suspended(managers(), invocation);
        }
    }

    /** The call observation of an internal module's contract call, in front of the boundary. */
    private final class Observed implements MethodInterceptor {
        private final String module;
        private final Provided provided;

        Observed(String module, Provided provided) {
            this.module = module;
            this.provided = provided;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            Class<?> contract = provided.contractOf(invocation.getMethod());
            CallObserver observing = contract == null ? null : observer();
            // the container's invocation of an exposed module's route handler is not a contract call (D-86)
            if (observing == null || (WEB && RouteMark.consume(invocation.getThis(), invocation.getMethod()))) {
                return invocation.proceed();
            }
            return observing.observe(module, Mode.INTERNAL, contract, invocation.getMethod().getName(),
                    invocation::proceed);
        }
    }

    /** The observer bean, looked up on the first call so none is created early; null when Topolith has none. */
    private CallObserver observer() {
        Optional<CallObserver> known = observer;
        if (known == null) {
            known = Optional.ofNullable(beanFactory.getBeanProvider(CallObserver.class).getIfAvailable());
            observer = known;
        }
        return known.orElse(null);
    }

    /**
     * Suspends the current transaction of each manager in turn (by bean name, so the order is stable), runs the call
     * outside all of them, then resumes every manager that was suspended, last first. A failure while suspending, in
     * the call or while resuming still resumes the others; the first failure propagates and later ones are added to
     * it as suppressed.
     */
    private static Object suspended(List<PlatformTransactionManager> managers, MethodInvocation invocation)
            throws Throwable {
        List<PlatformTransactionManager> suspended = new ArrayList<>();
        List<TransactionStatus> statuses = new ArrayList<>();
        Throwable failure = null;
        Object result = null;
        try {
            for (PlatformTransactionManager manager : managers) {
                statuses.add(manager.getTransaction(Suspension.NOT_SUPPORTED));
                suspended.add(manager);
            }
            result = invocation.proceed();
        } catch (Throwable e) {
            failure = e;
        }
        for (int i = suspended.size() - 1; i >= 0; i--) {
            try {
                if (failure == null) {
                    suspended.get(i).commit(statuses.get(i));
                } else {
                    suspended.get(i).rollback(statuses.get(i));
                }
            } catch (Throwable e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        return result;
    }
}
