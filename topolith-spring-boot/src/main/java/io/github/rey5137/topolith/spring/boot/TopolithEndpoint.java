package io.github.rey5137.topolith.spring.boot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.rey5137.topolith.core.Topology;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

@Endpoint(id = "topolith")
class TopolithEndpoint {
    private final Topology topology;
    private final TopolithRefreshStatus status;

    TopolithEndpoint(Topology topology, TopolithRefreshStatus status) {
        this.topology = topology;
        this.status = status;
    }

    /** The running modes, plus the restart and refresh state a refresh left (D-13, D-14, D-50); read-only. */
    @ReadOperation
    Map<String, Object> modules() {
        Map<String, Object> modules = new TreeMap<>();
        for (String id : topology.known()) {
            Map<String, Object> module = new LinkedHashMap<>();
            module.put("mode", topology.mode(id).id());
            String requested = status.requestedModes().get(id);
            if (requested != null) {
                module.put("requestedMode", requested);
            }
            List<String> reasons = new ArrayList<>();
            if (requested != null) {
                reasons.add("mode");
            }
            if (status.wiring().contains(id)) {
                reasons.add("wiring");
            }
            if (!reasons.isEmpty()) {
                module.put("restartPending", reasons);
            }
            TopolithRefreshStatus.Failure failure = status.failure(id);
            if (failure != null) {
                module.put("refresh", Map.of("status", "failed", "code", "TL0011", "message", failure.message(),
                        "at", failure.at().toString()));
            }
            modules.put(id, module);
        }
        return Map.of("restartPending", status.restartPending(), "modules", modules);
    }
}
