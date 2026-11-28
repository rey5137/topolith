package io.github.rey5137.topolith.samples.hosts.monolith;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackagedMonolithIT {
    @TempDir
    Path temp;

    private String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private Path jar() throws Exception {
        try (var files = Files.list(Path.of("target"))) {
            return files.filter(path -> path.getFileName().toString().matches("sample-monolith-.*\\.jar"))
                    .findFirst().orElseThrow().toAbsolutePath();
        }
    }

    @Test
    void ac_mode_63_nested_modules_start_in_the_repackaged_jar() throws Exception {
        starts(jar());
    }

    @Test
    void ac_mode_64_nested_modules_start_from_the_extracted_layout() throws Exception {
        Path jar = jar();
        Path log = temp.resolve("extract.log");
        Process extraction = new ProcessBuilder(java(), "-Djarmode=tools", "-jar", jar.toString(), "extract",
                "--destination", temp.resolve("extracted").toString()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try {
            assertTrue(extraction.waitFor(15, TimeUnit.SECONDS), "Extraction timed out");
            assertEquals(0, extraction.exitValue(), Files.readString(log));
        } finally {
            extraction.destroyForcibly();
            extraction.waitFor();
        }
        starts(temp.resolve("extracted").resolve(jar.getFileName()));
    }

    private void starts(Path jar) throws Exception {
        Path log = temp.resolve("startup.log");
        Process process = new ProcessBuilder(List.of(java(), "-jar", jar.toString(), "--server.port=0"))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
            String output = "";
            while (System.nanoTime() < deadline && process.isAlive()) {
                output = Files.readString(log);
                if (output.contains("Started MonolithApplication in ")) {
                    break;
                }
                Thread.sleep(100);
            }
            output = Files.readString(log);
            assertTrue(output.contains("Started MonolithApplication in "), output);
            assertFalse(output.matches("(?s).*TL[0-9]{4}.*"), output);
            var port = java.util.regex.Pattern.compile("Tomcat started on port (\\d+)").matcher(output);
            assertTrue(port.find(), output);
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port.group(1)
                    + "/user/orders/missing")).timeout(Duration.ofSeconds(5)).GET().build();
            var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(404, response.statusCode(), response.body());
            assertEquals("user", response.headers().firstValue("Topolith-Module").orElse(""));
        } finally {
            process.destroyForcibly();
            process.waitFor();
        }
    }
}
