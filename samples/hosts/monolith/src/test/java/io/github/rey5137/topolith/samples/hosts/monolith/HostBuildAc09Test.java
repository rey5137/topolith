package io.github.rey5137.topolith.samples.hosts.monolith;

import java.nio.file.Path;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The host parent is the adopter's build: it imports only the platform BOM and enforces both dependency rules, and
 * every host dependency version comes from that import (AC-BUILD-09). The enforcement itself runs in {@code verify}.
 */
class HostBuildAc09Test {
    private static final Path BASEDIR = Path.of(System.getProperty("basedir", System.getProperty("user.dir", ".")));
    private static final Path HOSTS_POM = BASEDIR.resolve("../pom.xml").normalize();
    private static final Path ROOT_POM = BASEDIR.resolve("../../../pom.xml").normalize();
    private static final XPath XPATH = XPathFactory.newInstance().newXPath();

    @Test
    void ac_build_09_hosts_take_every_version_from_the_platform_bom_and_enforce_both_rules() throws Exception {
        Document hosts = parse(HOSTS_POM);
        assertNotNull(XPATH.evaluate("/project/dependencyManagement/dependencies/dependency[artifactId='topolith-bom'"
                + " and type='pom' and scope='import']", hosts, XPathConstants.NODE), "BOM import in " + HOSTS_POM);
        assertEquals("", XPATH.evaluate("/project/parent", hosts), "the host parent has no parent of its own");
        assertNotNull(XPATH.evaluate("//plugin[artifactId='maven-enforcer-plugin']/executions/execution"
                + "/configuration/rules[dependencyConvergence and requireUpperBoundDeps]",
                hosts, XPathConstants.NODE), "both enforcer rules in " + HOSTS_POM);

        // The revision is a literal here, so it must not drift from the reactor's.
        assertEquals(XPATH.evaluate("/project/properties/revision", parse(ROOT_POM)),
                XPATH.evaluate("/project/properties/revision", hosts), "revision of " + HOSTS_POM);
        // So is the Boot plugin version: it must follow the reactor's Spring Boot.
        assertEquals(XPATH.evaluate("/project/properties/spring-boot.version", parse(ROOT_POM)),
                XPATH.evaluate("//plugin[artifactId='spring-boot-maven-plugin']/version", hosts),
                "spring-boot-maven-plugin version in " + HOSTS_POM);

        for (String host : List.of("monolith", "hybrid", "payment-host")) {
            Path pom = BASEDIR.resolve("../" + host + "/pom.xml").normalize();
            Document document = parse(pom);
            assertEquals("topolith-sample-hosts", XPATH.evaluate("/project/parent/artifactId", document),
                    pom.toString());
            assertEquals("", XPATH.evaluate("/project/dependencyManagement", document), "no local versions in " + pom);
            NodeList versions = (NodeList) XPATH.evaluate("/project/dependencies/dependency/version", document,
                    XPathConstants.NODESET);
            for (int i = 0; i < versions.getLength(); i++) {
                assertEquals("${project.version}", versions.item(i).getTextContent(),
                        "a version that does not come from the BOM in " + pom);
            }
        }
    }

    private static Document parse(Path pom) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile());
    }
}
