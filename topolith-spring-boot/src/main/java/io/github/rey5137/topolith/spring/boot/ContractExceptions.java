package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;
import org.springframework.asm.AnnotationVisitor;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.util.ClassUtils;

/**
 * The exceptions a contract call carries across HTTP (R-EXT-04, D-43). A declared exception is an unchecked exception
 * whose class, or nearest non-abstract superclass, lies in a known api package (exact, D-27);
 * {@link ModuleUnavailableException} is always declared. TL0027 fails startup when a contract could not keep that
 * promise remotely.
 */
final class ContractExceptions {
    private ContractExceptions() {
    }

    /**
     * The class a caller rebuilds for {@code type}: {@link ModuleUnavailableException}, or the nearest non-abstract
     * class from {@code type} up that lies in one of {@code apiPackages}; null when {@code type} is not declared.
     */
    static Class<?> declared(Class<?> type, Set<String> apiPackages) {
        if (!RuntimeException.class.isAssignableFrom(type)) {
            return null;
        }
        for (Class<?> current = type; current != RuntimeException.class; current = current.getSuperclass()) {
            if (current == ModuleUnavailableException.class) {
                return current;
            }
            if (!Modifier.isAbstract(current.getModifiers()) && apiPackages.contains(current.getPackageName())) {
                return current;
            }
        }
        return null;
    }

    /** Whether a caller can rebuild {@code type}: public, and a public {@code (String)} or no-arg constructor. */
    static boolean rebuildable(Class<?> type) {
        if (!Modifier.isPublic(type.getModifiers())) {
            return false;
        }
        for (Class<?>[] parameters : List.<Class<?>[]>of(new Class<?>[] {String.class}, new Class<?>[0])) {
            try {
                type.getConstructor(parameters);
                return true;
            } catch (NoSuchMethodException e) {
                // try the next shape
            }
        }
        return false;
    }

    /**
     * Adds a TL0027 diagnostic for every contract method of {@code modules} (internal and external, in every
     * topology) that declares a checked exception, and every concrete unchecked exception class in their api
     * packages that a caller could not rebuild. Contracts load with {@code initialize=false}; api package classes are
     * only read as class files, never loaded, so an external module's classes stay unloaded (INV-2).
     */
    static void check(Set<String> modules, Map<String, Set<String>> contracts, Map<String, Set<String>> apiPackages,
            ClassLoader loader, List<Diagnostic> problems) {
        for (String id : modules) {
            for (String name : contracts.getOrDefault(id, Set.of())) {
                Class<?> contract = load(name, loader);
                if (contract != null) {
                    checkMethods(id, contract, problems);
                }
            }
            for (String name : classes(apiPackages.getOrDefault(id, Set.of()), loader)) {
                ClassFile type = ClassFile.read(name, loader);
                if (type == null || type.isAbstract() || !type.unchecked(loader)) {
                    continue;
                }
                if (!type.rebuildable()) {
                    problems.add(new Diagnostic(TopolithCode.TL0027, "module '" + id + "' declares exception " + name
                            + ", which a caller cannot rebuild: it is not public or has neither a public (String) "
                            + "nor a public no-arg constructor.", "Make the class public and give it a public "
                            + "constructor that takes the message, or a public no-arg one."));
                }
                String status = type.responseStatus(loader, 0);
                if (status != null && !errorStatus(status, loader)) {
                    problems.add(new Diagnostic(TopolithCode.TL0027, "module '" + id + "' declares exception " + name
                            + " with @ResponseStatus " + status + ", which is not an error status: a caller would "
                            + "read the problem as a result.", "Use a 4xx or 5xx status."));
                }
            }
        }
    }

    private static void checkMethods(String id, Class<?> contract, List<Diagnostic> problems) {
        Set<String> found = new TreeSet<>();
        for (Method method : contract.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getDeclaringClass() == Object.class) {
                continue;
            }
            for (Class<?> thrown : method.getExceptionTypes()) {
                if (!RuntimeException.class.isAssignableFrom(thrown) && !Error.class.isAssignableFrom(thrown)) {
                    found.add(contract.getName() + "." + method.getName() + " declares checked exception "
                            + thrown.getName());
                }
            }
        }
        for (String text : found) {
            problems.add(new Diagnostic(TopolithCode.TL0027, "contract method of module '" + id + "' " + text
                    + ", which cannot cross HTTP.", "Throw an unchecked exception from an api package instead."));
        }
    }

    /** The classes directly in each package (no subpackages), sorted, from every classpath root. */
    private static Set<String> classes(Set<String> packages, ClassLoader loader) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(loader);
        Set<String> names = new TreeSet<>();
        for (String packageName : packages) {
            try {
                for (Resource resource : resolver.getResources(PathMatchingResourcePatternResolver
                        .CLASSPATH_ALL_URL_PREFIX + packageName.replace('.', '/') + "/*.class")) {
                    String file = resource.getFilename();
                    String simple = file == null ? "" : file.substring(0, file.length() - ".class".length());
                    if (!simple.isEmpty() && !simple.equals("package-info") && !simple.equals("module-info")) {
                        names.add(packageName + "." + simple);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot list the classes of api package " + packageName, e);
            }
        }
        return names;
    }

    /** Whether a {@code HttpStatus} constant name is a 4xx or 5xx status; the empty name is the default, 500. */
    private static boolean errorStatus(String status, ClassLoader loader) {
        return status.isEmpty() || !ClassUtils.isPresent(WebStatus.HTTP_STATUS, loader) || WebStatus.error(status);
    }

    /** Touches spring-web's {@code HttpStatus} only when it is present. */
    private static final class WebStatus {
        static final String HTTP_STATUS = "org.springframework.http.HttpStatus";

        static boolean error(String status) {
            try {
                return HttpStatus.valueOf(status).isError();
            } catch (IllegalArgumentException unknown) {
                return true;
            }
        }
    }

    /**
     * What TL0027 needs from a class file, read with ASM so the class is not loaded. {@code status} is the
     * {@code @ResponseStatus} constant ("" for the default), or null without one; {@code annotations} are the other
     * annotation types, which may carry it as a meta-annotation.
     */
    private record ClassFile(int access, String superName, Set<String> publicConstructors, String status,
            List<String> annotations) {
        private static final String RESPONSE_STATUS = "Lorg/springframework/web/bind/annotation/ResponseStatus;";

        private static final Set<String> NOT_UNCHECKED = Set.of("java/lang/Object", "java/lang/Throwable",
                "java/lang/Exception", "java/lang/Error");

        /** The class file of {@code name} (binary name), or null when it cannot be read. */
        static ClassFile read(String name, ClassLoader loader) {
            Resource resource = new ClassPathResource(name.replace('.', '/') + ".class", loader);
            if (!resource.exists()) {
                return null;
            }
            int[] access = new int[1];
            String[] superName = new String[1];
            Set<String> constructors = new TreeSet<>();
            String[] status = new String[1];
            List<String> annotations = new ArrayList<>();
            String internalName = name.replace('.', '/');
            try (InputStream in = resource.getInputStream()) {
                new ClassReader(in).accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
                    @Override
                    public void visit(int version, int flags, String type, String signature, String parent,
                            String[] interfaces) {
                        access[0] = flags;
                        superName[0] = parent;
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        if (!descriptor.equals(RESPONSE_STATUS)) {
                            annotations.add(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'));
                            return null;
                        }
                        status[0] = "";
                        return new AnnotationVisitor(SpringAsmInfo.ASM_VERSION) {
                            @Override
                            public void visitEnum(String attribute, String type, String value) {
                                if (attribute.equals("code") || attribute.equals("value")) {
                                    status[0] = value;
                                }
                            }
                        };
                    }

                    @Override
                    public void visitInnerClass(String type, String outer, String simple, int flags) {
                        if (type.equals(internalName)) {
                            access[0] = flags | (access[0] & Opcodes.ACC_ABSTRACT);
                        }
                    }

                    @Override
                    public MethodVisitor visitMethod(int flags, String method, String descriptor, String signature,
                            String[] exceptions) {
                        if (method.equals("<init>") && (flags & Opcodes.ACC_PUBLIC) != 0) {
                            constructors.add(descriptor);
                        }
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            } catch (IOException | RuntimeException e) {
                return null;
            }
            return new ClassFile(access[0], superName[0], constructors, status[0], annotations);
        }

        boolean isAbstract() {
            return (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0;
        }

        /** Whether a superclass, read the same way, is {@code RuntimeException}. */
        boolean unchecked(ClassLoader loader) {
            String parent = superName;
            for (int depth = 0; parent != null && depth < 64; depth++) {
                if (parent.equals("java/lang/RuntimeException")) {
                    return true;
                }
                if (NOT_UNCHECKED.contains(parent)) {
                    return false;
                }
                ClassFile next = read(parent.replace('/', '.'), loader);
                parent = next == null ? null : next.superName();
            }
            return false;
        }

        /**
         * The {@code @ResponseStatus} Spring would find: on the class, as a meta-annotation of one of its
         * annotations, or on a superclass; null when there is none.
         */
        String responseStatus(ClassLoader loader, int depth) {
            if (status != null) {
                return status;
            }
            for (String annotation : annotations) {
                ClassFile meta = annotation.startsWith("java.") ? null : read(annotation, loader);
                if (meta != null && meta.status() != null) {
                    return meta.status();
                }
            }
            if (superName == null || depth > 64 || superName.startsWith("java/")) {
                return null;
            }
            ClassFile parent = read(superName.replace('/', '.'), loader);
            return parent == null ? null : parent.responseStatus(loader, depth + 1);
        }

        boolean rebuildable() {
            return (access & Opcodes.ACC_PUBLIC) != 0 && (publicConstructors.contains("(Ljava/lang/String;)V")
                    || publicConstructors.contains("()V"));
        }
    }

    private static Class<?> load(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }
}
