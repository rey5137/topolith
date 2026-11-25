package io.github.rey5137.topolith.spring.boot;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.rey5137.topolith.core.ApiDescriptor;
import io.github.rey5137.topolith.core.Descriptors;
import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Finds the descriptors on the bean class loader, the one Boot reads its imports files with, so that both always agree
 * (also in a fat JAR). It needs the JARs to carry directory entries: {@code classpath*:} with a wildcard lists a JAR
 * directory, and a JAR written without directory entries shows no descriptor.
 */
final class DescriptorDiscovery {
    static final String MODULES = "META-INF/topolith/modules/";
    static final String APIS = "META-INF/topolith/apis/";
    static final String AUTO_CONFIGURATION_IMPORTS =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /**
     * What is on the classpath.
     *
     * @param importOwners auto-configuration class name to the id of the module that lists it in an
     *                     {@code AutoConfiguration.imports} file in the root of its own artifact (R-MODE-02)
     */
    record Found(List<ModuleDescriptor> modules, List<ApiDescriptor> apis, Map<String, String> importOwners) {
    }

    private DescriptorDiscovery() {
    }

    static Found discover(ClassLoader loader) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(loader);
        List<Diagnostic> problems = new ArrayList<>();
        Map<String, InputStream> moduleFiles = read(resolver, MODULES, problems);
        Map<String, InputStream> apiFiles = read(resolver, APIS, problems);
        if (!problems.isEmpty()) {
            throw new TopolithException(problems);
        }
        List<ModuleDescriptor> modules = Descriptors.parseModules(moduleFiles);
        List<ApiDescriptor> apis = Descriptors.parseApis(apiFiles);
        return new Found(modules, apis, importOwners(loader, modules));
    }

    /** The files by URL string; a URL reached twice is one file. */
    private static Map<String, InputStream> read(PathMatchingResourcePatternResolver resolver, String directory,
            List<Diagnostic> problems) {
        Map<String, InputStream> files = new TreeMap<>();
        try {
            for (Resource resource : resolver.getResources("classpath*:" + directory + "*.properties")) {
                String source = resource.getURL().toString();
                try {
                    files.putIfAbsent(source, new ByteArrayInputStream(resource.getContentAsByteArray()));
                } catch (IOException e) {
                    problems.add(unreadable(source, e));
                }
            }
        } catch (IOException e) {
            problems.add(unreadable(directory, e));
        }
        return files;
    }

    private static Diagnostic unreadable(String source, IOException e) {
        return new Diagnostic(TopolithCode.TL0013, source + ": the file cannot be read (" + e.getMessage() + ").",
                "Check that the JAR or directory is readable.");
    }

    /**
     * An entry of an imports file is owned by the module whose sole module descriptor sits in the same classpath root
     * as the imports file or as the entry's {@code .class} resource (the class is looked up as a resource, never
     * loaded). Split output directories put the descriptor and the class in one root and the imports file in another.
     * When the two roots name different modules, or a root holds several module descriptors, nobody owns the entry.
     */
    private static Map<String, String> importOwners(ClassLoader loader, List<ModuleDescriptor> modules) {
        Map<String, List<String>> idsByRoot = new HashMap<>();
        for (ModuleDescriptor module : modules) {
            idsByRoot.computeIfAbsent(root(module.source(), MODULES), key -> new ArrayList<>()).add(module.id());
        }
        Map<String, String> owners = new TreeMap<>();
        if (idsByRoot.isEmpty()) {
            return owners;
        }
        try {
            for (URL file : Collections.list(loader.getResources(AUTO_CONFIGURATION_IMPORTS))) {
                String fileOwner = soleId(idsByRoot, root(file.toString(), AUTO_CONFIGURATION_IMPORTS));
                for (String className : classNames(file)) {
                    URL resource = loader.getResource(className.replace('.', '/') + ".class");
                    String classOwner = resource == null ? null
                            : soleId(idsByRoot, root(resource.toString(), className.replace('.', '/') + ".class"));
                    if (fileOwner == null || classOwner == null || fileOwner.equals(classOwner)) {
                        String owner = fileOwner != null ? fileOwner : classOwner;
                        if (owner != null) {
                            owners.putIfAbsent(className, owner);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new TopolithException(new Diagnostic(TopolithCode.TL0013,
                    AUTO_CONFIGURATION_IMPORTS + ": the file cannot be read (" + e.getMessage() + ").",
                    "Check that the JAR or directory is readable."));
        }
        return owners;
    }

    private static String soleId(Map<String, List<String>> idsByRoot, String root) {
        List<String> ids = idsByRoot.get(root);
        return ids != null && ids.size() == 1 ? ids.get(0) : null;
    }

    /** The URL string up to the resource path: the root of the JAR or directory that holds the resource. */
    private static String root(String url, String path) {
        int at = url.lastIndexOf(path);
        return at < 0 ? url : url.substring(0, at);
    }

    private static List<String> classNames(URL file) throws IOException {
        List<String> names = new ArrayList<>();
        try (InputStream in = file.openStream()) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                int comment = line.indexOf('#');
                String name = (comment < 0 ? line : line.substring(0, comment)).trim();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return names;
    }
}
