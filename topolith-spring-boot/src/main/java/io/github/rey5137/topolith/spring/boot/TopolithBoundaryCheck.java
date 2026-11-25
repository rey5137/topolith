package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.net.URL;
import java.util.HashSet;
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
                Set<Violation> frozen = baseline(loader, id);
                String pattern = "classpath*:" + root.replace('.', '/') + "/**/*.class";
                for (Resource resource : resolver.getResources(pattern)) {
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
            throw new TopolithException(java.util.List.of(new Diagnostic(TopolithCode.TL0012,
                    "Cross-module access to non-API classes: " + violations + ".", "Use the target module's API.")));
        }
    }

    private static Set<Violation> baseline(ClassLoader loader, String id) throws IOException {
        String name = BoundaryBaseline.resource(id);
        Set<Violation> frozen = new HashSet<>();
        var resources = loader.getResources(name);
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            try {
                frozen.addAll(BoundaryBaseline.parse(url.openStream()));
            } catch (IllegalArgumentException e) {
                throw new TopolithException(java.util.List.of(new Diagnostic(TopolithCode.TL0030,
                        "Malformed baseline '" + name + "' at " + url + ": " + e.getMessage(),
                        "Regenerate the baseline with the M3.2 tooling.")));
            }
        }
        return frozen;
    }

    private static Set<String> references(ClassReader reader) {
        Set<String> names = new HashSet<>();
        char[] buffer = new char[reader.getMaxStringLength()];
        for (int i = 1; i < reader.getItemCount(); i++) {
            int offset = reader.getItem(i);
            if (offset == 0) {
                continue;
            }
            int tag = reader.readByte(offset - 1);
            if (tag == 7) {
                String name = reader.readUTF8(offset, buffer);
                if (name.startsWith("[")) {
                    addTypes(names, name);
                } else {
                    names.add(name.replace('/', '.'));
                }
            } else if (tag == 12 || tag == 16) {
                String descriptor = reader.readUTF8(offset + (tag == 12 ? 2 : 0), buffer);
                addTypes(names, descriptor);
            }
        }
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName,
                    String[] interfaces) {
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
                return new MethodVisitor(Opcodes.ASM9) {
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
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
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

    private static void addTypes(Set<String> names, String descriptor) {
        Matcher matcher = TYPE.matcher(descriptor);
        while (matcher.find()) {
            names.add(matcher.group(1).replace('/', '.'));
        }
    }
}
