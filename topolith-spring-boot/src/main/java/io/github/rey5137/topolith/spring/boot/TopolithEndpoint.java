package io.github.rey5137.topolith.spring.boot;

import java.util.Map;
import java.util.TreeMap;

import io.github.rey5137.topolith.core.Topology;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

@Endpoint(id = "topolith")
class TopolithEndpoint {
    private final Topology topology;

    TopolithEndpoint(Topology topology) {
        this.topology = topology;
    }

    @ReadOperation
    Map<String, Map<String, Map<String, String>>> modules() {
        Map<String, Map<String, String>> modules = new TreeMap<>();
        topology.known().forEach(id -> modules.put(id, Map.of("mode", topology.mode(id).id())));
        return Map.of("modules", modules);
    }
}
