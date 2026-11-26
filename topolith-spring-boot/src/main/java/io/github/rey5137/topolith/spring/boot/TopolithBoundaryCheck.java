package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.rey5137.topolith.core.BoundaryBaseline;
import io.github.rey5137.topolith.core.BoundaryBaseline.Violation;
import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import io.github.rey5137.topolith.core.Topology;
import org.springframework.asm.AnnotationVisitor;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.FieldVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Checks references from internal module classes before beans are instantiated. */
final class TopolithBoundaryCheck implements BeanFactoryPostProcessor, EnvironmentAware {
    private static final Pattern TYPE = Pattern.compile("L([a-zA-Z0-9_$/]+)");
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory factory) throws BeansException {
        ClassLoader loader = factory.getBeanClassLoader();
        Topology topology = TopologyResolver.resolve(factory, environment, loader).topology();
        var resolver = new PathMatchingResourcePatternResolver(loader);
        Set<Violation> violations = new TreeSet<>();
        try {
            for (String id : topology.internal()) {
                String root = topology.modulePackages().get(id);
                if (root == null) {
                    continue;
                }
                Set<String> roots = descriptorRoots(loader, id);
                if (roots.isEmpty()) {
                    throw new IllegalStateException("Internal module '" + id + "' has no descriptor root on the class "
                            + "path (META-INF/topolith/modules/" + id + ".properties); its classes cannot be scanned.");
                }
                Set<Violation> frozen = baseline(loader, id, roots);
                String pattern = "classpath*:" + root.replace('.', '/') + "/**/*.class";
                for (Resource resource : resolver.getResources(pattern)) {
                    if (!inRoot(resource.getURL().toString(), roots)) {
                        continue;
                    }
                    ClassReader reader;
                    try (var in = resource.getInputStream()) {
                        reader = new ClassReader(in);
                    }
                    String source = reader.getClassName().replace('/', '.');
                    for (String target : references(reader)) {
                        var owner = topology.ownerOfClass(target);
                        int separator = target.lastIndexOf('.');
                        boolean api = separator >= 0 && topology.apiPackages().contains(target.substring(0, separator));
                        Violation pair = new Violation(source, target);
                        if (owner.isPresent() && !owner.get().equals(id) && !api && !frozen.contains(pair)) {
                            violations.add(pair);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read module classes or baselines", e);
        }
        if (!violations.isEmpty()) {
            throw new TopolithException(List.of(new Diagnostic(TopolithCode.TL0012,
                    "Cross-module access to non-API classes: " + violations + ".", "Use the target module's API, or freeze it with TopolithArchitecture.verifyFrozen and "
                            + "TopolithBaseline.generate.")));
        }
    }

    /** The class-path roots (URL prefixes) that hold the module's descriptor (D-63). */
    static Set<String> descriptorRoots(ClassLoader loader, String id) throws IOException {
        Set<String> roots = new HashSet<>();
        var found = loader.getResources(DescriptorDiscovery.MODULES + id + ".properties");
        for (URL url : Collections.list(found)) {
            roots.add(DescriptorDiscovery.root(url.toString(), DescriptorDiscovery.MODULES));
        }
        return roots;
    }

    static boolean inRoot(String url, Set<String> roots) {
        return roots.stream().anyMatch(url::startsWith);
    }

    /**
     * The module's baseline: the copy in its descriptor root, or in a directory root that holds no module descriptor
     * (a resource directory); a copy anywhere else, or a second accepted copy, is TL0030 (D-63).
     */
    private static Set<Violation> baseline(ClassLoader loader, String id, Set<String> roots) throws IOException {
        String name = BoundaryBaseline.resource(id);
        URL accepted = null;
        for (URL url : Collections.list(loader.getResources(name))) {
            String root = DescriptorDiscovery.root(url.toString(), name);
            if (!inRoot(url.toString(), roots) && !descriptorlessDirectory(root)) {
                throw new TopolithException(List.of(new Diagnostic(TopolithCode.TL0030,
                        "Baseline '" + name + "' at " + url + " is not in the descriptor root of module '" + id
                                + "' (" + String.join(", ", roots) + ") or in a directory root without a module "
                                + "descriptor.",
                        "Remove the copy, or regenerate it in the module's own build with "
                                + "TopolithBaseline.generate(...).")));
            }
            if (accepted != null) {
                throw new TopolithException(List.of(new Diagnostic(TopolithCode.TL0030,
                        "Baseline '" + name + "' of module '" + id + "' exists twice: " + accepted
                                + " and " + url + ".",
                        "Keep one copy; startup never merges baselines.")));
            }
            accepted = url;
        }
        Set<Violation> frozen = new HashSet<>();
        if (accepted != null) {
            try (var in = accepted.openStream()) {
                frozen.addAll(BoundaryBaseline.parse(in));
            } catch (IllegalArgumentException e) {
                throw new TopolithException(List.of(new Diagnostic(TopolithCode.TL0030,
                        "Malformed baseline '" + name + "' at " + accepted + ": " + e.getMessage(),
                        "Regenerate it with TopolithBaseline.generate(...).")));
            }
        }
        return frozen;
    }

    /** A {@code file:} directory root with no {@code META-INF/topolith/modules/*.properties}; never a JAR. */
    private static boolean descriptorlessDirectory(String root) {
        if (!root.startsWith("file:")) {
            return false;
        }
        try {
            Path modules = Path.of(new java.net.URI(root)).resolve(DescriptorDiscovery.MODULES);
            if (!Files.isDirectory(modules)) {
                return true;
            }
            try (var files = Files.list(modules)) {
                return files.noneMatch(file -> file.getFileName().toString().endsWith(".properties"));
            }
        } catch (java.net.URISyntaxException | IOException e) {
            throw new IllegalStateException("Cannot inspect class-path root " + root, e);
        }
    }

    private static Set<String> references(ClassReader reader) {
        Set<String> names = new HashSet<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName,
                    String[] interfaces) {
                if (superName != null) {
                    names.add(superName.replace('/', '.'));
                }
                for (String type : interfaces) {
                    names.add(type.replace('/', '.'));
                }
                if (signature != null) {
                    addTypes(names, signature);
                }
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                addTypes(names, descriptor);
                return annotationValues(names);
            }

            @Override
            public AnnotationVisitor visitTypeAnnotation(int typeRef, org.springframework.asm.TypePath path,
                    String descriptor, boolean visible) {
                addTypes(names, descriptor);
                return annotationValues(names);
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                addTypes(names, descriptor);
                if (signature != null) {
                    addTypes(names, signature);
                }
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitTypeAnnotation(int typeRef, org.springframework.asm.TypePath path,
                            String annotation, boolean visible) {
                        addTypes(names, annotation);
                        return annotationValues(names);
                    }
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        addTypes(names, annotation);
                        return annotationValues(names);
                    }
                };
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                addTypes(names, descriptor);
                if (signature != null) {
                    addTypes(names, signature);
                }
                if (exceptions != null) {
                    for (String type : exceptions) {
                        names.add(type.replace('/', '.'));
                    }
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        addOwner(names, owner);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                            boolean isInterface) {
                        addOwner(names, owner);
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if (opcode == Opcodes.INSTANCEOF) {
                            addOwner(names, type);
                        }
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof org.springframework.asm.Type type
                                && type.getSort() != org.springframework.asm.Type.METHOD) {
                            addTypes(names, type.getDescriptor());
                        }
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap,
                            Object... arguments) {
                        for (Object argument : arguments) {
                            if (argument instanceof Handle handle) {
                                addOwner(names, handle.getOwner());
                            }
                        }
                    }

                    @Override
                    public void visitTryCatchBlock(org.springframework.asm.Label start,
                            org.springframework.asm.Label end, org.springframework.asm.Label handler, String type) {
                        if (type != null) {
                            addOwner(names, type);
                        }
                    }

                    @Override
                    public AnnotationVisitor visitAnnotationDefault() {
                        return annotationValues(names);
                    }
                    @Override
                    public AnnotationVisitor visitTypeAnnotation(int typeRef, org.springframework.asm.TypePath path,
                            String annotation, boolean visible) {
                        addTypes(names, annotation);
                        return annotationValues(names);
                    }
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        addTypes(names, annotation);
                        return annotationValues(names);
                    }

                    @Override
                    public AnnotationVisitor visitParameterAnnotation(int parameter, String annotation,
                            boolean visible) {
                        addTypes(names, annotation);
                        return annotationValues(names);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return names;
    }

    private static AnnotationVisitor annotationValues(Set<String> names) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                if (value instanceof org.springframework.asm.Type type) {
                    addTypes(names, type.getDescriptor());
                }
            }
            @Override
            public void visitEnum(String name, String descriptor, String value) {
                addTypes(names, descriptor);
            }
            @Override
            public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                addTypes(names, descriptor);
                return this;
            }
            @Override
            public AnnotationVisitor visitArray(String name) {
                return this;
            }
        };
    }

    /** An instruction's owner or type operand: a class name in internal form, or an array descriptor (skipped). */
    private static void addOwner(Set<String> names, String internalName) {
        if (!internalName.startsWith("[")) {
            names.add(internalName.replace('/', '.'));
        }
    }

    private static void addTypes(Set<String> names, String descriptor) {
        Matcher matcher = TYPE.matcher(descriptor);
        while (matcher.find()) {
            names.add(matcher.group(1).replace('/', '.'));
        }
    }
}
