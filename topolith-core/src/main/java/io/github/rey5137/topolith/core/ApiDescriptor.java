package io.github.rey5137.topolith.core;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The published API of a module (a {@code *-api} JAR's {@code META-INF/topolith/apis/<id>.properties}). Equality
 * covers the content, not {@link #source()}.
 */
@Incubating
public final class ApiDescriptor {
    private final String id;
    private final Set<String> apiPackages;
    private final Set<String> provides;
    private final String apiVersion;
    private final int formatVersion;
    private final String source;

    ApiDescriptor(DescriptorData data) {
        this.id = data.id;
        this.apiPackages = Collections.unmodifiableSet(new LinkedHashSet<>(data.apiPackages));
        this.provides = Collections.unmodifiableSet(new LinkedHashSet<>(data.provides));
        this.apiVersion = data.apiVersion;
        this.formatVersion = data.formatVersion;
        this.source = data.source == null ? "<generated>" : data.source;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public String id() {
        return id;
    }

    /** The exact API packages, in declared order (subpackages are not included). */
    public Set<String> apiPackages() {
        return apiPackages;
    }

    /** The contract interfaces the module provides, in declared order. */
    public Set<String> provides() {
        return provides;
    }

    public String apiVersion() {
        return apiVersion;
    }

    public int formatVersion() {
        return formatVersion;
    }

    /** Where the descriptor was read from, such as a JAR URL and path. */
    public String source() {
        return source;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ApiDescriptor that && id.equals(that.id) && apiPackages.equals(that.apiPackages)
                && provides.equals(that.provides) && apiVersion.equals(that.apiVersion)
                && formatVersion == that.formatVersion;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, apiPackages, provides, apiVersion, formatVersion);
    }

    @Override
    public String toString() {
        return "ApiDescriptor[" + id + ", " + apiVersion + ", " + apiPackages + ", " + provides + "]";
    }

    /** Validating builder; {@link #build()} throws a TL0013 {@link TopolithException} on any D-27 violation. */
    @Incubating
    public static final class Builder {
        private final DescriptorData data;

        private Builder(String id) {
            this.data = new DescriptorData(false, id);
        }

        public Builder apiPackages(Collection<String> packages) {
            data.apiPackages.addAll(packages);
            return this;
        }

        public Builder provides(Collection<String> contracts) {
            data.provides.addAll(contracts);
            return this;
        }

        public Builder apiVersion(String apiVersion) {
            data.apiVersion = apiVersion;
            return this;
        }

        public Builder formatVersion(int formatVersion) {
            data.formatVersion = formatVersion;
            return this;
        }

        /** The location the descriptor comes from; when set, its file name must be {@code <id>.properties}. */
        public Builder source(String source) {
            data.source = source;
            return this;
        }

        public ApiDescriptor build() {
            List<Diagnostic> problems = data.validate();
            if (!problems.isEmpty()) {
                throw new TopolithException(problems);
            }
            return new ApiDescriptor(data);
        }
    }
}
