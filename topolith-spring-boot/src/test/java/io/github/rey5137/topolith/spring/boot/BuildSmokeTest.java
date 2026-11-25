package io.github.rey5137.topolith.spring.boot;

import java.io.DataInputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildSmokeTest {
    @Test
    void ac_build_01_spring_boot_builds() throws ClassNotFoundException {
        assertNotNull(Class.forName("io.github.rey5137.topolith.spring.boot.package-info").getPackage());
    }

    @Test
    void ac_build_02_baseline() throws Exception {
        assertTrue(org.springframework.boot.SpringBootVersion.getVersion().startsWith("4.2."));
        assertTrue(org.springframework.core.SpringVersion.getVersion().startsWith("7."));
        try (var input = new DataInputStream(BuildSmokeTest.class.getResourceAsStream("package-info.class"))) {
            assertEquals(0xCAFEBABE, input.readInt());
            input.readUnsignedShort();
            assertEquals(65, input.readUnsignedShort());
        }
    }

    @Test
    void ac_build_02_bom_entries() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(Path.of("../topolith-bom/pom.xml").toFile());
        var management = (Element) XPathFactory.newInstance().newXPath()
                .evaluate("/project/dependencyManagement", document, XPathConstants.NODE);
        var dependencies = management.getElementsByTagName("dependency");
        Set<String> artifacts = new HashSet<>();
        for (int i = 0; i < dependencies.getLength(); i++) {
            var dependency = (Element) dependencies.item(i);
            var group = dependency.getElementsByTagName("groupId").item(0).getTextContent();
            assertFalse(group.startsWith("org.springframework"));
            if (group.equals("io.github.rey5137")) {
                artifacts.add(dependency.getElementsByTagName("artifactId").item(0).getTextContent());
            }
        }
        assertTrue(artifacts.containsAll(Set.of("topolith-core", "topolith-spring-boot", "topolith-spring-cloud",
                "topolith-processor", "topolith-spring-test")));
        assertFalse(artifacts.contains("topolith-samples"));
    }
}
