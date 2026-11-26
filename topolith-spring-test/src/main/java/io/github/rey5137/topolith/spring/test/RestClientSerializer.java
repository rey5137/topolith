package io.github.rey5137.topolith.spring.test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.AbstractBufferingClientHttpRequest;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.ClassUtils;
import org.springframework.web.client.RestClient;

/**
 * Round trips through the host's {@link RestClient.Builder}, so the converters and the JSON mapper are the ones the
 * module's HTTP service group uses in external mode. The request factory answers with the request body.
 */
final class RestClientSerializer implements ByValueSerializer {
    private static final URI URI_ = URI.create("http://by-value.invalid/");
    private static final ParameterizedTypeReference<Object> TREE = new ParameterizedTypeReference<>() { };

    private final RestClient client;

    RestClientSerializer(RestClient.Builder builder) {
        // a copy of the host's builder, so its converters and mapper are used but not its interceptors, initializers
        // or observation, and the host's own builder keeps its request factory
        RestClient.Builder copy = builder.clone().requestInterceptors(List::clear).requestInitializers(List::clear)
                .requestFactory((uri, method) -> new Echo(uri, method));
        ClassLoader loader = builder.getClass().getClassLoader();
        if (ClassUtils.isPresent("io.micrometer.observation.ObservationRegistry", loader)) {
            NoObservation.apply(copy);
        }
        this.client = copy.build();
        // fails when no converter writes and reads JSON
        Copy probe = copy(Map.of("probe", 1), Map.class);
        if (!(probe.copy() instanceof Map<?, ?>)) {
            throw new IllegalStateException("The host's HTTP message converters do not read JSON objects");
        }
    }

    @Override
    public Copy copy(Object value, Type type) {
        ParameterizedTypeReference<Object> reference = ParameterizedTypeReference.forType(type);
        return client.post().uri(URI_).contentType(MediaType.APPLICATION_JSON).body(value, reference)
                .exchange((request, response) -> new Copy(response.bodyTo(reference), response.bodyTo(TREE)));
    }

    @Override
    public Object tree(Object value, Type type) {
        ParameterizedTypeReference<Object> reference = ParameterizedTypeReference.forType(type);
        return client.post().uri(URI_).contentType(MediaType.APPLICATION_JSON).body(value, reference)
                .exchange((request, response) -> response.bodyTo(TREE));
    }

    /** Apart so Micrometer is loaded only when present. */
    private static final class NoObservation {
        static void apply(RestClient.Builder builder) {
            builder.observationRegistry(io.micrometer.observation.ObservationRegistry.NOOP);
        }
    }

    private static final class Echo extends AbstractBufferingClientHttpRequest implements ClientHttpRequest {
        private final URI uri;
        private final HttpMethod method;

        Echo(URI uri, HttpMethod method) {
            this.uri = uri;
            this.method = method;
        }

        @Override
        public HttpMethod getMethod() { return method; }

        @Override
        public URI getURI() { return uri; }

        @Override
        protected ClientHttpResponse executeInternal(HttpHeaders headers, byte[] body) {
            return new ClientHttpResponse() {
                private final HttpHeaders responseHeaders = new HttpHeaders();

                {
                    responseHeaders.setContentType(headers.getContentType());
                }

                @Override
                public HttpStatusCode getStatusCode() { return HttpStatus.OK; }

                @Override
                public String getStatusText() { return "OK"; }

                @Override
                public void close() { }

                @Override
                public InputStream getBody() throws IOException { return new ByteArrayInputStream(body); }

                @Override
                public HttpHeaders getHeaders() { return responseHeaders; }
            };
        }
    }
}
