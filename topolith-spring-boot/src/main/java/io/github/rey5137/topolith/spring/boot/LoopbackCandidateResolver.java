package io.github.rey5137.topolith.spring.boot;

import java.lang.reflect.Member;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.rey5137.topolith.core.Mode;
import io.github.rey5137.topolith.core.Topology;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.AutowireCandidateResolver;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.util.ClassUtils;

/**
 * Hides a loopback module's provider from callers outside the module (D-81): wraps the bean factory's resolver and,
 * for a dependency typed as one of the module's provided contracts (or a collection, array or map of one), excludes
 * the beans the module owns when the injection point is declared outside the module's package, and the group's HTTP
 * client when it is declared inside. A caller outside gets the client alone and the module's own injections get the
 * provider; injections of the provider's concrete class and collections of its other interfaces are untouched.
 * Nothing is {@code @Primary} and nothing falls back (INV-4). Installed only when a module is loopback (AC-MODE-46).
 */
final class LoopbackCandidateResolver implements AutowireCandidateResolver {
    /** The attribute Spring's HTTP service registry sets on each client bean definition: its group's name. */
    static final String GROUP_ATTRIBUTE = "httpServiceGroupName";

    private final AutowireCandidateResolver delegate;
    private final ConfigurableListableBeanFactory factory;
    private final Map<String, String> contracts;
    private final Map<String, Optional<String>> owners = new ConcurrentHashMap<>();

    private LoopbackCandidateResolver(AutowireCandidateResolver delegate, ConfigurableListableBeanFactory factory,
            Map<String, String> contracts) {
        this.delegate = delegate;
        this.factory = factory;
        this.contracts = contracts;
    }

    /** Wraps the factory's current resolver; Boot's bean factory is always a {@link DefaultListableBeanFactory}. */
    static void install(ConfigurableListableBeanFactory factory) {
        if (!(factory instanceof DefaultListableBeanFactory beans)
                || beans.getAutowireCandidateResolver() instanceof LoopbackCandidateResolver) {
            return;
        }
        TopologyResolver.Resolution resolution = resolution(factory);
        Map<String, String> contracts = new HashMap<>();
        for (String id : resolution.topology().idsOf(Mode.LOOPBACK)) {
            resolution.contracts().getOrDefault(id, Set.of()).forEach(name -> contracts.put(name, id));
        }
        beans.setAutowireCandidateResolver(new LoopbackCandidateResolver(beans.getAutowireCandidateResolver(), factory,
                Map.copyOf(contracts)));
    }

    private static TopologyResolver.Resolution resolution(ConfigurableListableBeanFactory factory) {
        return factory.getBean(TopologyResolver.RESOLUTION_BEAN, TopologyResolver.Resolution.class);
    }

    @Override
    public boolean isAutowireCandidate(BeanDefinitionHolder holder, DependencyDescriptor descriptor) {
        if (!delegate.isAutowireCandidate(holder, descriptor)) {
            return false;
        }
        Class<?> type = elementType(descriptor);
        String id = type == null ? null : contracts.get(type.getName());
        if (id == null) {
            return true;
        }
        String name = holder.getBeanName();
        BeanDefinition definition = factory.containsBeanDefinition(name) ? factory.getBeanDefinition(name)
                : holder.getBeanDefinition();
        if (id.equals(definition.getAttribute(GROUP_ATTRIBUTE))) {
            return !inside(descriptor, id);
        }
        return inside(descriptor, id) || !owned(name, definition, id);
    }

    /** The type each matching bean must have: the element type of a collection, array or map, else the type. */
    private static Class<?> elementType(DependencyDescriptor descriptor) {
        Class<?> type = descriptor.getDependencyType();
        if (type.isArray()) {
            return type.getComponentType();
        }
        if (Collection.class.isAssignableFrom(type)) {
            return descriptor.getResolvableType().asCollection().resolveGeneric();
        }
        if (Map.class.isAssignableFrom(type)) {
            return descriptor.getResolvableType().asMap().resolveGeneric(1);
        }
        return type;
    }

    /** Whether the injection point is declared in module {@code id}'s package (the package rule, D-30). */
    private boolean inside(DependencyDescriptor descriptor, String id) {
        if (descriptor.getField() == null && descriptor.getMethodParameter() == null) {
            return false;
        }
        Member member = descriptor.getMember();
        Topology topology = resolution(factory).topology();
        return topology.ownerOfClass(ClassUtils.getUserClass(member.getDeclaringClass()).getName())
                .filter(id::equals).isPresent();
    }

    /** Whether module {@code id} owns the bean: its class or its declaring configuration is in the module. */
    private boolean owned(String name, BeanDefinition definition, String id) {
        return owners.computeIfAbsent(name, key -> ModuleBeans.owner(definition, resolution(factory)))
                .filter(id::equals).isPresent();
    }

    @Override
    public boolean isRequired(DependencyDescriptor descriptor) {
        return delegate.isRequired(descriptor);
    }

    @Override
    public boolean hasQualifier(DependencyDescriptor descriptor) {
        return delegate.hasQualifier(descriptor);
    }

    @Override
    public String getSuggestedName(DependencyDescriptor descriptor) {
        return delegate.getSuggestedName(descriptor);
    }

    @Override
    public Object getSuggestedValue(DependencyDescriptor descriptor) {
        return delegate.getSuggestedValue(descriptor);
    }

    @Override
    public Object getLazyResolutionProxyIfNecessary(DependencyDescriptor descriptor, String beanName) {
        return delegate.getLazyResolutionProxyIfNecessary(descriptor, beanName);
    }

    @Override
    public Class<?> getLazyResolutionProxyClass(DependencyDescriptor descriptor, String beanName) {
        return delegate.getLazyResolutionProxyClass(descriptor, beanName);
    }

    @Override
    public AutowireCandidateResolver cloneIfNecessary() {
        return new LoopbackCandidateResolver(delegate.cloneIfNecessary(), factory, contracts);
    }
}
