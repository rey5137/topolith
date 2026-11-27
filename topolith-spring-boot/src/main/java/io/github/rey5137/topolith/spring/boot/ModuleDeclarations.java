package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;
import org.springframework.asm.AnnotationVisitor;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.SpringAsmInfo;

/**
 * Reads a module declaration's {@code provides} (D-110, D-128): the contracts only a {@code @Bean} method provides. The
 * descriptor does not tell them from the inferred ones, so they are read from the {@code @TopolithModule} kept in the
 * module package's {@code package-info.class}. The annotation has class retention, so the class file is parsed, never
 * loaded.
 */
final class ModuleDeclarations {
    private static final String ANNOTATION = "Lio/github/rey5137/topolith/annotation/TopolithModule;";

    private ModuleDeclarations() {
    }

    /**
     * The contracts the module's declaration lists in {@code provides}, among its descriptor's {@code provides},
     * sorted; empty when no {@code package-info.class} of its package carries a declaration with its id.
     */
    static Set<String> listed(ClassLoader loader, ModuleDescriptor module) {
        Set<String> listed = new TreeSet<>();
        String path = module.packageName().replace('.', '/') + "/package-info.class";
        String current = path;
        try {
            for (URL url : Collections.list(loader.getResources(path))) {
                current = url.toString();
                try (InputStream in = url.openStream()) {
                    read(new ClassReader(in), module.id(), listed);
                }
            }
        } catch (IOException | RuntimeException e) {
            // an unreadable or malformed class file: the module's declaration is part of what its descriptor means
            TopolithException failure = new TopolithException(new Diagnostic(TopolithCode.TL0013,
                    "The declaration of module '" + module.id() + "' in " + current + " cannot be read: " + e + ".",
                    "Rebuild the module artifact with the Topolith processor, so its package-info.class is a valid "
                            + "class file."));
            failure.initCause(e);
            throw failure;
        }
        listed.retainAll(module.provides());
        return listed;
    }

    private static void read(ClassReader reader, String id, Set<String> into) {
        Set<String> provides = new TreeSet<>();
        String[] declared = new String[1];
        reader.accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!ANNOTATION.equals(descriptor)) {
                    return null;
                }
                return new AnnotationVisitor(SpringAsmInfo.ASM_VERSION) {
                    @Override
                    public void visit(String name, Object value) {
                        if ("id".equals(name)) {
                            declared[0] = String.valueOf(value);
                        }
                    }

                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        if (!"provides".equals(name)) {
                            return null;
                        }
                        return new AnnotationVisitor(SpringAsmInfo.ASM_VERSION) {
                            @Override
                            public void visit(String element, Object value) {
                                provides.add(String.valueOf(value));
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (id.equals(declared[0])) {
            into.addAll(provides);
        }
    }
}
