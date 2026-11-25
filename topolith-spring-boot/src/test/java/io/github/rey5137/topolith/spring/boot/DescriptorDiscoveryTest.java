package io.github.rey5137.topolith.spring.boot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.github.rey5137.topolith.core.Descriptors;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.loader.net.protocol.Handlers;
import org.springframework.boot.loader.net.protocol.jar.JarUrl;
import org.springframework.core.io.support.SpringFactoriesLoader;

import static io.github.rey5137.topolith.spring.boot.Fixtures.OUTSIDE;
import static io.github.rey5137.topolith.spring.boot.Fixtures.USER;
import static io.github.rey5137.topolith.spring.boot.Fixtures.USER_API;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Discovery on the bean class loader: directories, plain JARs and the nested JARs of a Boot fat JAR. */
class DescriptorDiscoveryTest {
    @TempDir
    Path temp;

    private static byte[] module(String id, String packageName, String api) throws IOException {
        java.io.StringWriter out = new java.io.StringWriter();
        Descriptors.write(ModuleDescriptor.builder(id).packageName(packageName).apiPackages(List.of(api))
                .apiVersion("1.0.0").build(), out);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] jar(boolean directoryEntries, Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            if (directoryEntries) {
                for (String directory : List.of("META-INF/", "META-INF/topolith/", "META-INF/topolith/modules/",
                        "META-INF/spring/")) {
                    jar.putNextEntry(new JarEntry(directory));
                    jar.closeEntry();
                }
            }
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                jar.putNextEntry(new JarEntry(file.getKey()));
                jar.write(file.getValue());
                jar.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Map<String, byte[]> userFiles() throws IOException {
        return Map.of("META-INF/topolith/modules/user.properties", module("user", USER, USER_API),
                Fixtures.IMPORTS, (OUTSIDE + ".ColocatedAutoConfiguration\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void stored(ZipOutputStream out, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(content.length);
        entry.setCompressedSize(content.length);
        CRC32 crc = new CRC32();
        crc.update(content);
        entry.setCrc(crc.getValue());
        out.putNextEntry(entry);
        out.write(content);
        out.closeEntry();
    }

    @Test
    void ac_mode_23_a_fat_jar_nested_jar_is_discovered_with_its_ownership_root() throws Exception {
        Path app = temp.resolve("app.jar");
        try (OutputStream file = Files.newOutputStream(app); ZipOutputStream out = new ZipOutputStream(file)) {
            stored(out, "BOOT-INF/lib/user.jar", jar(true, userFiles()));
            stored(out, "BOOT-INF/lib/bare.jar", jar(false, Map.of("META-INF/topolith/modules/bare.properties",
                    module("bare", "com.acme.bare", "com.acme.bareapi"))));
        }
        Handlers.register();
        URL[] urls = {JarUrl.create(app.toFile(), "BOOT-INF/lib/user.jar"),
                JarUrl.create(app.toFile(), "BOOT-INF/lib/bare.jar")};
        try (URLClassLoader loader = new URLClassLoader(urls, null)) {
            DescriptorDiscovery.Found found = DescriptorDiscovery.discover(loader);
            assertEquals(List.of("user"), found.modules().stream().map(ModuleDescriptor::id).toList());
            assertTrue(found.modules().get(0).source().startsWith("jar:nested:"), found.modules().get(0).source());
            assertEquals(Map.of(OUTSIDE + ".ColocatedAutoConfiguration", "user"), found.importOwners());
            // A JAR written without directory entries shows no descriptor to a wildcard classpath* lookup.
            assertTrue(found.modules().stream().noneMatch(module -> module.id().equals("bare")));
        }
    }

    @Test
    void ac_mode_23_a_plain_jar_is_discovered_and_a_listed_class_is_owned_by_its_module() throws Exception {
        Path user = Files.write(temp.resolve("user.jar"), jar(true, userFiles()));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {user.toUri().toURL()}, null)) {
            DescriptorDiscovery.Found found = DescriptorDiscovery.discover(loader);
            assertEquals("user", found.modules().get(0).id());
            assertEquals(Map.of(OUTSIDE + ".ColocatedAutoConfiguration", "user"), found.importOwners());
        }
    }

    @Test
    void the_same_descriptor_through_two_urls_is_tl0006() throws Exception {
        Path one = Files.write(temp.resolve("one.jar"), jar(true, userFiles()));
        Path two = Files.write(temp.resolve("two.jar"), jar(true, userFiles()));
        try (URLClassLoader loader = new URLClassLoader(new URL[] {one.toUri().toURL(), two.toUri().toURL()}, null)) {
            DescriptorDiscovery.Found found = DescriptorDiscovery.discover(loader);
            assertEquals(2, found.modules().size());
            // two files with one id: the identity stage of the topology reports TL0006
            TopolithException failure = assertThrows(TopolithException.class, () -> io.github.rey5137.topolith.core
                    .Topology.resolve(found.modules(), found.apis(), Map.of("user", "none"), null));
            assertEquals(TopolithCode.TL0006, failure.code());
        }
    }

    @Test
    void a_malformed_descriptor_fails_discovery_with_tl0013() throws Exception {
        Path root = Fixtures.dir(temp, "bad");
        Path file = root.resolve("META-INF/topolith/modules/user.properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "format-version=1\nid=user\n");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, null)) {
            TopolithException failure = assertThrows(TopolithException.class,
                    () -> DescriptorDiscovery.discover(loader));
            assertEquals(TopolithCode.TL0013, failure.code());
        }
    }

    @Test
    void the_filter_and_the_auto_configuration_are_registered_for_boot() {
        ClassLoader loader = DescriptorDiscoveryTest.class.getClassLoader();
        assertTrue(SpringFactoriesLoader.forDefaultResourceLocation(loader).load(AutoConfigurationImportFilter.class)
                .stream().anyMatch(filter -> filter instanceof TopolithAutoConfigurationFilter));
        assertTrue(ImportCandidates.load(AutoConfiguration.class, loader).getCandidates()
                .contains(TopolithAutoConfiguration.class.getName()));
    }
}
