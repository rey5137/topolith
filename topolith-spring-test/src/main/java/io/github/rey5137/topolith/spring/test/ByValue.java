package io.github.rey5137.topolith.spring.test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import io.github.rey5137.topolith.core.Topology;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.test.util.AopTestUtils;
import org.springframework.util.ClassUtils;
import org.springframework.web.client.RestClient;

/**
 * The by-value proxy (R-TEST-04, D-79): installs the recorder, the checks and a bean post-processor that puts
 * {@link Advice} in front of the contract beans of the selected internal modules.
 */
final class ByValue {
    static final String RECORDER = ByValueRecorder.class.getName();
    /** The name {@code TopologyResolver} registers the topology under. */
    private static final String TOPOLOGY = "topolithTopology";

    private ByValue() { }

    /** Adds the proxy for {@code ids} (every internal module when empty) to a context that is not refreshed yet. */
    static void install(ConfigurableApplicationContext context, Set<String> ids) {
        DefaultListableBeanFactory beans = (DefaultListableBeanFactory) context.getBeanFactory();
        if (beans.containsSingleton(RECORDER)) { return; }
        ByValueRecorder recorder = new ByValueRecorder(() -> serializer(beans),
                () -> "warn".equals(context.getEnvironment().getProperty("topolith.test.parity", "fail")));
        beans.registerSingleton(RECORDER, recorder);
        context.addBeanFactoryPostProcessor(factory -> {
            String parity = context.getEnvironment().getProperty("topolith.test.parity", "fail");
            if (!parity.equals("fail") && !parity.equals("warn")) {
                throw new IllegalStateException("topolith.test.parity must be fail or warn");
            }
        });
        Set<String> selected = Set.copyOf(ids);
        context.addBeanFactoryPostProcessor(new Unknown(selected));
        Processor processor = new Processor(selected);
        var definition = BeanDefinitionBuilder.rootBeanDefinition(Processor.class, () -> processor)
                .setRole(BeanDefinition.ROLE_INFRASTRUCTURE).getBeanDefinition();
        beans.registerBeanDefinition(Processor.class.getName(), definition);
        context.addApplicationListener((ApplicationListener<ContextRefreshedEvent>) event -> {
            processor.checkConverter(beans, recorder);
        });
    }

    private static ByValueSerializer serializer(BeanFactory beans) {
        if (!ClassUtils.isPresent("org.springframework.web.client.RestClient", beans.getClass().getClassLoader())) {
            throw new IllegalStateException("By-value parity needs spring-web and a JSON converter such as Jackson");
        }
        List<RestClient.Builder> builders = beans.getBeanProvider(RestClient.Builder.class).stream().toList();
        if (builders.isEmpty()) {
            throw new IllegalStateException("@TopolithByValue needs the host's HTTP message converters: the context"
                    + " has no RestClient.Builder bean; configure a JSON converter such as Jackson");
        }
        if (builders.size() > 1) {
            throw new IllegalStateException("@TopolithByValue needs one RestClient.Builder bean to take the JSON HTTP"
                    + " message converters from, but the context has " + builders.size());
        }
        try {
            return new RestClientSerializer(builders.get(0));
        } catch (RuntimeException e) {
            throw new IllegalStateException("By-value parity needs a JSON HTTP converter such as Jackson: "
                    + e.getMessage(), e);
        }
    }

    /** Fails context load for a module id no descriptor names. */
    private record Unknown(Set<String> ids) implements BeanFactoryPostProcessor {
        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beans) {
            if (ids.isEmpty()) { return; }
            Set<String> known = beans.containsSingleton(TOPOLOGY)
                    ? beans.getBean(TOPOLOGY, Topology.class).known() : Set.of();
            for (String id : new TreeSet<>(ids)) {
                if (!known.contains(id)) {
                    throw new IllegalStateException("@TopolithByValue names module '" + id
                            + "' but no module or API descriptor has that id");
                }
            }
        }
    }

    /** Wraps the contract beans of the selected internal modules; never a mock, never another mode's beans. */
    static final class Processor implements BeanPostProcessor, BeanFactoryAware, Ordered {
        private final Set<String> ids;
        private BeanFactory beans;
        private ByValueRecorder recorder;
        private volatile boolean eligible;

        Processor(Set<String> ids) { this.ids = ids; }

        void checkConverter(DefaultListableBeanFactory factory, ByValueRecorder recorder) {
            if (eligible) {
                recorder.serializer();
                return;
            }
            if (!factory.containsSingleton(TOPOLOGY)) { return; }
            Topology topology = factory.getBean(TOPOLOGY, Topology.class);
            Set<String> lazy = new TreeSet<>();
            for (String id : topology.known()) {
                if (topology.mode(id) != Mode.INTERNAL || !(ids.isEmpty() || ids.contains(id))) { continue; }
                for (String contract : topology.graph().module(id).orElseThrow().provides()) {
                    try {
                        Class<?> type = Class.forName(contract, false, factory.getBeanClassLoader());
                        for (String name : factory.getBeanNamesForType(type, true, false)) {
                            if (!factory.containsSingleton(name)) { lazy.add(name); }
                        }
                    } catch (ClassNotFoundException | LinkageError e) {
                        // no loadable contract to wrap
                    }
                }
            }
            if (lazy.isEmpty()) { return; }
            try {
                recorder.serializer();
            } catch (RuntimeException failure) {
                // Resolve candidates only when needed to distinguish a real provider from a mock.
                for (String name : lazy) {
                    factory.getBean(name);
                    if (eligible) { throw failure; }
                }
            }
        }

        @Override
        public void setBeanFactory(BeanFactory beanFactory) throws BeansException { this.beans = beanFactory; }

        @Override
        public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (!(beans instanceof DefaultListableBeanFactory factory) || !factory.containsSingleton(TOPOLOGY)) {
                return bean;
            }
            Class<?> type = AopProxyUtils.ultimateTargetClass(bean);
            if (mock(bean, type)) { return bean; }
            Topology topology = factory.getBean(TOPOLOGY, Topology.class);
            String module = topology.ownerOfClass(ClassUtils.getUserClass(type).getName()).orElse(null);
            if (module == null || topology.mode(module) != Mode.INTERNAL || !(ids.isEmpty() || ids.contains(module))) {
                return bean;
            }
            List<Class<?>> contracts = contracts(topology, module, type);
            if (contracts.isEmpty()) { return bean; }
            eligible = true;
            var advisor = new DefaultPointcutAdvisor(new Advice(module, contracts, topology.apiPackages(),
                    () -> recorder()));
            if (bean instanceof Advised advised && !advised.isFrozen()) {
                advised.addAdvisor(0, advisor);
                return bean;
            }
            boolean last = Modifier.isFinal(type.getModifiers()) && !AopUtils.isAopProxy(bean);
            ProxyFactory proxy = new ProxyFactory(bean);
            proxy.setProxyTargetClass(AopUtils.isCglibProxy(bean) || (!AopUtils.isAopProxy(bean) && !last));
            proxy.addAdvisor(advisor);
            return proxy.getProxy(type.getClassLoader());
        }

        /**
         * Whether the bean is a Mockito mock or spy. Asked of Mockito reflectively, so no Mockito is a dependency: an
         * inline spy keeps the provider's own class, so the class name alone does not show it.
         */
        private static boolean mock(Object bean, Class<?> type) {
            if (type.getName().contains("$MockitoMock$")) { return true; }
            if (!ClassUtils.isPresent("org.mockito.Mockito", type.getClassLoader())) { return false; }
            try {
                Object target = bean;
                try {
                    target = AopTestUtils.getUltimateTargetObject(bean);
                } catch (RuntimeException e) {
                    // not a proxy of a single target: ask about the bean itself
                }
                Object details = Class.forName("org.mockito.Mockito", false, type.getClassLoader())
                        .getMethod("mockingDetails", Object.class).invoke(null, target);
                return (boolean) Class.forName("org.mockito.MockingDetails", false, type.getClassLoader())
                        .getMethod("isMock").invoke(details);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return false;
            }
        }

        private synchronized ByValueRecorder recorder() {
            if (recorder == null) { recorder = beans.getBean(RECORDER, ByValueRecorder.class); }
            return recorder;
        }

        private static List<Class<?>> contracts(Topology topology, String module, Class<?> type) {
            Optional<ModuleDescriptor> descriptor = topology.graph().module(module);
            List<Class<?>> found = new ArrayList<>();
            for (String name : descriptor.map(ModuleDescriptor::provides).orElse(Set.of())) {
                try {
                    Class<?> contract = Class.forName(name, false, type.getClassLoader());
                    if (contract.isInterface() && contract.isAssignableFrom(type)) { found.add(contract); }
                } catch (ClassNotFoundException | LinkageError e) {
                    // not loadable here: nothing to wrap
                }
            }
            return found;
        }
    }

    /** Copies arguments and result by JSON and keeps what the provider might hold on to. */
    static final class Advice implements MethodInterceptor {
        private final String module;
        private final List<Class<?>> contracts;
        private final Set<String> apiPackages;
        private final Supplier<ByValueRecorder> recorders;
        private final Map<Method, Optional<Contract>> declared = new ConcurrentHashMap<>();

        Advice(String module, List<Class<?>> contracts, Set<String> apiPackages,
                Supplier<ByValueRecorder> recorders) {
            this.module = module;
            this.contracts = contracts;
            this.apiPackages = apiPackages;
            this.recorders = recorders;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            Contract found = declared.computeIfAbsent(invocation.getMethod(), this::contractMethod).orElse(null);
            if (found == null) { return invocation.proceed(); }
            Method method = found.method();
            ByValueRecorder recorder = recorders.get();
            AssertionError later = recorder.recheck(module);
            if (later != null && !recorder.warning()) { throw later; }
            String label = method.getDeclaringClass().getSimpleName() + "." + method.getName();
            Object[] arguments = invocation.getArguments();
            Type[] types = found.parameterTypes();
            Object[] trees = new Object[arguments.length];
            boolean violated = false;
            for (int i = 0; i < arguments.length; i++) {
                if (!immutable(arguments[i])) {
                    ByValueSerializer.Copy copy = copy(recorder, label, "argument " + i, arguments[i], types[i]);
                    if (copy == null) {
                        violated = true;
                    } else {
                        arguments[i] = copy.copy();
                        trees[i] = copy.tree();
                    }
                }
            }
            Object result = null;
            Throwable thrown = null;
            try {
                result = invocation.proceed();
            } catch (Throwable e) {
                thrown = e;
            }
            for (int i = 0; i < arguments.length; i++) {
                if (trees[i] == null) { continue; }
                String where = label + ", argument " + i;
                String path;
                try {
                    path = ByValueJson.diff(trees[i], recorder.serializer().tree(arguments[i], types[i]), "$");
                } catch (RuntimeException e) {
                    if (!recorder.warning()) { throw e; }
                    path = "$";
                }
                if (path != null) {
                    if (recorder.warning()) {
                        recorder.warning(module, where, path);
                        violated = true;
                        continue;
                    }
                    AssertionError changed = recorder.record(new AssertionError("By-value proxy: module '" + module
                            + "', " + where + " (" + types[i].getTypeName() + "): the provider changed the argument"
                            + " during the call, at " + path));
                    if (thrown != null) { changed.addSuppressed(thrown); }
                    throw changed;
                }
                recorder.keep(module, new ByValueRecorder.Kept(where, arguments[i], types[i], trees[i]));
            }
            if (thrown != null) { throw violated ? thrown : rebuilt(thrown); }
            if (result == null || method.getReturnType() == void.class || immutable(result)) { return result; }
            Type type = found.returnType();
            ByValueSerializer.Copy copy = copy(recorder, label, "result", result, type);
            if (copy == null) { return result; }
            if (violated) { return result; }
            recorder.keep(module, new ByValueRecorder.Kept(label + ", result", result, type, copy.tree()));
            return copy.copy();
        }

        /** The copy, after checking that it serializes as the original did. */
        private ByValueSerializer.Copy copy(ByValueRecorder recorder, String label, String part, Object value,
                Type type) {
            ByValueSerializer.Copy copy;
            String path;
            try {
                copy = recorder.serializer().copy(value, type);
                path = ByValueJson.diff(copy.tree(), recorder.serializer().tree(copy.copy(), type), "$");
            } catch (RuntimeException e) {
                if (recorder.warning()) {
                    recorder.warning(module, label + ", " + part, "$");
                    return null;
                }
                throw recorder.record(new AssertionError("By-value proxy: " + where(label, part, type)
                        + " does not survive the JSON round trip: " + e.getMessage(), e));
            }
            if (path != null) {
                if (recorder.warning()) {
                    recorder.warning(module, label + ", " + part, path);
                    return null;
                }
                throw recorder.record(new AssertionError("By-value proxy: " + where(label, part, type)
                        + " does not survive the JSON round trip: the copy differs from the original at " + path));
            }
            return copy;
        }

        private String where(String label, String part, Type type) {
            return "module '" + module + "', " + label + ", " + part + " (" + type.getTypeName() + ")";
        }

        /** The contract method with its types resolved against the contract, as an HTTP interface resolves them. */
        private Optional<Contract> contractMethod(Method method) {
            for (Class<?> contract : contracts) {
                Method declaredMethod = declaredIn(contract, method);
                if (declaredMethod != null) {
                    Type[] parameters = new Type[declaredMethod.getParameterCount()];
                    for (int i = 0; i < parameters.length; i++) {
                        parameters[i] = resolved(ResolvableType.forMethodParameter(declaredMethod, i, contract));
                    }
                    return Optional.of(new Contract(declaredMethod, parameters,
                            resolved(ResolvableType.forMethodReturnType(declaredMethod, contract))));
                }
            }
            return Optional.empty();
        }

        /**
         * The contract's method for the one invoked: the same signature, or the generic one whose parameters resolve
         * to the invoked method's (a call through a bridge method reaches the provider as the bound signature).
         */
        private static Method declaredIn(Class<?> contract, Method method) {
            try {
                return contract.getMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException e) {
                for (Method candidate : contract.getMethods()) {
                    if (!candidate.getName().equals(method.getName())
                            || candidate.getParameterCount() != method.getParameterCount()) {
                        continue;
                    }
                    boolean same = true;
                    for (int i = 0; i < candidate.getParameterCount() && same; i++) {
                        same = ResolvableType.forMethodParameter(candidate, i, contract).resolve(Object.class)
                                == method.getParameterTypes()[i];
                    }
                    if (same) { return candidate; }
                }
                return null;
            }
        }

        private record Contract(Method method, Type[] parameterTypes, Type returnType) { }

        /** A concrete type: type variables replaced by what the contract binds them to. */
        private static Type resolved(ResolvableType type) {
            Class<?> raw = type.resolve(Object.class);
            if (!type.hasGenerics() || type.isArray()) { return raw; }
            ResolvableType[] generics = type.getGenerics();
            Type[] arguments = new Type[generics.length];
            for (int i = 0; i < arguments.length; i++) { arguments[i] = resolved(generics[i]); }
            return new ParameterizedType() {
                @Override
                public Type[] getActualTypeArguments() { return arguments.clone(); }

                @Override
                public Type getRawType() { return raw; }

                @Override
                public Type getOwnerType() { return raw.getDeclaringClass(); }

                @Override
                public String toString() {
                    return raw.getTypeName() + Arrays.stream(arguments).map(Type::getTypeName)
                            .collect(Collectors.joining(", ", "<", ">"));
                }
            };
        }

        /** A declared exception as the caller's translation rebuilds it; anything else as thrown (D-55). */
        private Throwable rebuilt(Throwable thrown) {
            if (!(thrown instanceof RuntimeException) || thrown instanceof ModuleUnavailableException) {
                return thrown;
            }
            Class<?> type = null;
            for (Class<?> current = thrown.getClass(); current != RuntimeException.class;
                    current = current.getSuperclass()) {
                if (current == ModuleUnavailableException.class) { return thrown; }
                if (!Modifier.isAbstract(current.getModifiers()) && apiPackages.contains(current.getPackageName())) {
                    type = current;
                    break;
                }
            }
            if (type == null || !Modifier.isPublic(type.getModifiers())) { return thrown; }
            RuntimeException rebuilt = construct(type, thrown.getMessage());
            if (rebuilt == null) { return thrown; }
            rebuilt.addSuppressed(thrown);
            return rebuilt;
        }

        private static RuntimeException construct(Class<?> type, String message) {
            try {
                Constructor<?> withMessage = type.getConstructor(String.class);
                return (RuntimeException) withMessage.newInstance(message);
            } catch (NoSuchMethodException e) {
                try {
                    return (RuntimeException) type.getConstructor().newInstance();
                } catch (ReflectiveOperationException | RuntimeException none) {
                    return null;
                }
            } catch (ReflectiveOperationException | RuntimeException failure) {
                return null;
            }
        }

        /** Values no provider can change, passed as they are. */
        private static boolean immutable(Object value) {
            return value == null || value instanceof String || value instanceof Integer || value instanceof Long
                    || value instanceof Short || value instanceof Byte || value instanceof Double
                    || value instanceof Float || value instanceof BigDecimal || value instanceof BigInteger
                    || value instanceof Boolean || value instanceof Character || value instanceof Enum<?>
                    || value instanceof Temporal
                    || value instanceof UUID;
        }
    }
}
