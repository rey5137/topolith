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
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
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
 * The D-2 transaction boundary (R-EXT-07, D-47): every call through a provided contract interface of an internal module
 * runs with {@code NOT_SUPPORTED}, so a caller's transaction is suspended and the callee's own {@code @Transactional}
 * decides. The advice is added to the module's provider bean outside Spring's transaction interceptor, for every
 * caller, the module's own code included. It does nothing without a {@link PlatformTransactionManager}, and an external
 * or {@code none} module's beans never get it (INV-2).
 */
final class ContractTransactionBoundary implements BeanPostProcessor, PriorityOrdered {
    /** The counter of calls that found a caller transaction (tagged {@code module} and {@code contract}). */
    static final String METRIC = "topolith.contract.calls.in.transaction";
    private static final TransactionDefinition NOT_SUPPORTED = new DefaultTransactionDefinition(
            TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

    /** Counts a contract call made inside a caller transaction. */
    interface CallCounter {
        void count(String module, String contract);
    }

    private final BeanFactory beanFactory;
    private final CallCounter counter;
    private volatile Boolean transactional;

    ContractTransactionBoundary(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
        ClassLoader classLoader = beanFactory instanceof ConfigurableBeanFactory configurable
                ? configurable.getBeanClassLoader() : null;
        this.counter = ClassUtils.isPresent("io.micrometer.core.instrument.MeterRegistry", classLoader)
                ? new MicrometerCallCounter(beanFactory) : (module, contract) -> { };
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
        if (module == null || !hasTransactionManager()) {
            return bean;
        }
        List<Class<?>> contracts = contracts(resolution.contracts().getOrDefault(module, Set.of()), type);
        if (contracts.isEmpty()) {
            return bean;
        }
        boolean last = Modifier.isFinal(type.getModifiers()) && !AopUtils.isAopProxy(bean);
        if (last && handler(type)) {
            throw new TopolithException(new Diagnostic(TopolithCode.TL0029, "provider bean '" + beanName + "' of module '"
                    + module + "' is a final handler (" + type.getName() + ") and cannot take the contract transaction boundary: "
                    + "an interface-only proxy would hide its routes and its class.",
                    "Make " + type.getSimpleName() + " non-final, as Spring's @Transactional requires."));
        }
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(new Boundary(module, contracts));
        if (bean instanceof Advised advised && !advised.isFrozen()) {
            advised.addAdvisor(0, advisor);
            return bean;
        }
        ProxyFactory factory = new ProxyFactory(bean);
        // a final non-handler (a lambda, a JDK proxy class) gets the interface proxy; a frozen CGLIB proxy stays class-based
        factory.setProxyTargetClass(AopUtils.isCglibProxy(bean) || (!AopUtils.isAopProxy(bean) && !last));
        factory.addAdvisor(advisor);
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
            known = beanFactory instanceof ListableBeanFactory listable
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

    private final class Boundary implements MethodInterceptor {
        private final String module;
        private final List<Class<?>> contracts;
        private final Map<Method, Optional<Class<?>>> declaring = new ConcurrentHashMap<>();

        Boundary(String module, List<Class<?>> contracts) {
            this.module = module;
            this.contracts = contracts;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            Class<?> contract = declaring.computeIfAbsent(invocation.getMethod(), this::declaringContract)
                    .orElse(null);
            if (contract == null || !TransactionSynchronizationManager.isActualTransactionActive()) {
                return invocation.proceed();
            }
            counter.count(module, contract.getName());
            return suspended(managers(), invocation);
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
                statuses.add(manager.getTransaction(NOT_SUPPORTED));
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
