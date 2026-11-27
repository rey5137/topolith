package io.github.rey5137.topolith.spring.boot;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import io.github.rey5137.topolith.contract.ModuleUnavailableException;
import io.github.rey5137.topolith.core.Topology;
import io.github.rey5137.topolith.spring.boot.internal.Outcomes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Renders a declared exception thrown by an internal module's handler as a Topolith problem (R-EXT-04, D-43).
 * It runs ahead of {@code ExceptionHandlerExceptionResolver}, so on module handlers it takes precedence over host and
 * controller advices; it declines (returns null) for every other handler and for undeclared exceptions, whose message
 * it never sends.
 */
final class ModuleExceptionResolver implements HandlerExceptionResolver, Ordered {
    /** The {@code type} of every problem Topolith writes; a caller rebuilds only problems carrying it. */
    static final String TYPE = Outcomes.PROBLEM_TYPE;
    static final String EXCEPTION = "exception";
    static final String MODULE = "module";

    private final Topology topology;
    private final TopologyResolver.Resolution resolution;
    private final ListedContractRoutes routes;
    private final Supplier<List<HttpMessageConverter<?>>> converters;

    ModuleExceptionResolver(Topology topology, TopologyResolver.Resolution resolution, ListedContractRoutes routes,
            Supplier<List<HttpMessageConverter<?>>> converters) {
        this.topology = topology;
        this.resolution = resolution;
        this.routes = routes;
        this.converters = converters;
    }

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception exception) {
        if (!(handler instanceof HandlerMethod method) || response.isCommitted()) {
            return null;
        }
        String module = routes.internalOwner(topology, method);
        Class<?> declared = module == null ? null : ContractExceptions.declared(exception.getClass(),
                topology.apiPackages());
        if (declared == null || (!(exception instanceof RuntimeException)
                && !declaredByContract(module, method, declared))) {
            return null;
        }
        HttpStatus status;
        String detail;
        if (exception instanceof ModuleUnavailableException unavailable) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            module = unavailable.moduleId();
            // a fixed message: whatever the exception carries (URLs, downstream bodies) stays here
            detail = ContractCallTranslator.message(module, ContractCallTranslator.DOWNSTREAM);
        } else {
            ResponseStatus annotated = AnnotatedElementUtils.findMergedAnnotation(exception.getClass(),
                    ResponseStatus.class);
            status = annotated == null ? HttpStatus.INTERNAL_SERVER_ERROR : annotated.code();
            // TL0027 rejects a non-error status; a class it could not see still never reads as a result
            if (!status.isError()) {
                status = HttpStatus.INTERNAL_SERVER_ERROR;
            }
            detail = exception.getMessage();
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE));
        problem.setProperty(EXCEPTION, declared.getName());
        problem.setProperty(MODULE, module);
        if (!write(problem, response)) {
            // no converter writes problem+json (a host without Jackson): never fall through to the host's error page
            writeJson(problem, response);
        }
        return new ModelAndView();
    }

    /** Writes the problem as JSON by hand; only the string and integer members Topolith sets are present. */
    static void writeJson(ProblemDetail problem, HttpServletResponse response) {
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("type", problem.getType() == null ? null : problem.getType().toString());
        members.put("title", problem.getTitle());
        members.put("status", problem.getStatus());
        members.put("detail", problem.getDetail());
        if (problem.getProperties() != null) {
            members.putAll(problem.getProperties());
        }
        StringBuilder json = new StringBuilder("{");
        members.forEach((name, value) -> {
            if (value != null) {
                json.append(json.length() > 1 ? "," : "").append(quote(name)).append(':')
                        .append(value instanceof Number ? value.toString() : quote(value.toString()));
            }
        });
        byte[] body = json.append('}').toString().getBytes(StandardCharsets.UTF_8);
        reset(response);
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setContentLength(body.length);
        try {
            response.getOutputStream().write(body);
            response.flushBuffer();
        } catch (IOException e) {
            throw new IllegalStateException("cannot write the contract problem", e);
        }
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> out.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return out.append('"').toString();
    }

    @SuppressWarnings("unchecked")
    private boolean write(ProblemDetail problem, HttpServletResponse response) {
        for (HttpMessageConverter<?> converter : converters.get()) {
            if (converter.canWrite(ProblemDetail.class, MediaType.APPLICATION_PROBLEM_JSON)) {
                reset(response);
                ServletServerHttpResponse out = new ServletServerHttpResponse(response);
                out.setStatusCode(HttpStatus.valueOf(problem.getStatus()));
                try {
                    ((HttpMessageConverter<Object>) converter).write(problem, MediaType.APPLICATION_PROBLEM_JSON, out);
                    out.flush();
                } catch (IOException e) {
                    throw new IllegalStateException("cannot write the contract problem", e);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Drops what the handler wrote before it threw (body, content type, length, writer or stream choice) and keeps the
     * other headers, such as those a filter set.
     */
    private static void reset(HttpServletResponse response) {
        Map<String, List<String>> kept = new LinkedHashMap<>();
        for (String name : response.getHeaderNames()) {
            if (!name.equalsIgnoreCase(HttpHeaders.CONTENT_TYPE)
                    && !name.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH)) {
                kept.putIfAbsent(name, List.copyOf(response.getHeaders(name)));
            }
        }
        response.reset();
        kept.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
    }

    private boolean declaredByContract(String module, HandlerMethod handler, Class<?> exception) {
        if (routes.owner(handler) != null) {
            return Arrays.stream(handler.getMethod().getExceptionTypes()).anyMatch(t -> t.isAssignableFrom(exception));
        }
        Class<?> beanType = ClassUtils.getUserClass(handler.getBeanType());
        for (String name : resolution.contracts().getOrDefault(module, Set.of())) {
            try {
                Class<?> contract = Class.forName(name, false, beanType.getClassLoader());
                if (!contract.isAssignableFrom(beanType)) {
                    continue;
                }
                for (Method method : contract.getMethods()) {
                    Method implementation = BridgeMethodResolver.findBridgedMethod(
                            ClassUtils.getMostSpecificMethod(method, beanType));
                    if (implementation.equals(BridgeMethodResolver.findBridgedMethod(handler.getMethod()))
                            && Arrays.stream(method.getExceptionTypes()).anyMatch(t -> t.isAssignableFrom(exception))) {
                        return true;
                    }
                }
            } catch (ClassNotFoundException | LinkageError ignored) {
                // An unreadable contract cannot declare the exception for this route.
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
