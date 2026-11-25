package io.github.rey5137.topolith.core;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A module's identity, API metadata and dependency edges ({@code META-INF/topolith/modules/<id>.properties}).
 * Equality covers the content, not {@link #source()}.
 */
@Incubating
public final class ModuleDescriptor {
    private final ApiDescriptor api;
    private final String packageName;
    private final Set<String> requires;
    private final Set<String> optional;

    ModuleDescriptor(DescriptorData data) {
        this.api = new ApiDescriptor(data);
        this.packageName = data.packageName;
        this.requires = Collections.unmodifiableSet(new LinkedHashSet<>(data.requires));
        this.optional = Collections.unmodifiableSet(new LinkedHashSet<>(data.optional));
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public String id() {
        return api.id();
    }

    /** The module's root package: the implementation the host scans when the module is internal. */
    public String packageName() {
        return packageName;
    }

    /** The API part of this descriptor. */
    public ApiDescriptor api() {
        return api;
    }

    public Set<String> apiPackages() {
        return api.apiPackages();
    }

    public Set<String> provides() {
        return api.provides();
    }

    public String apiVersion() {
        return api.apiVersion();
    }

    /** The ids of the modules this one needs, in declared order. */
    public Set<String> requires() {
        return requires;
    }

    /** The ids of the modules this one can use when present, in declared order. */
    public Set<String> optional() {
        return optional;
    }

    public String source() {
        return api.source();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ModuleDescriptor that && api.equals(that.api) && packageName.equals(that.packageName)
                && requires.equals(that.requires) && optional.equals(that.optional);
    }

    @Override
    public int hashCode() {
        return Objects.hash(api, packageName, requires, optional);
    }

    @Override
    public String toString() {
        return "ModuleDescriptor[" + id() + ", package=" + packageName + ", requires=" + requires + ", optional="
                + optional + "]";
    }

    /** Validating builder; {@link #build()} throws a TL0013 {@link TopolithException} on any D-27 violation. */
    @Incubating
    public static final class Builder {
        private final DescriptorData data;

        private Builder(String id) {
            this.data = new DescriptorData(true, id);
        }

        /** The module's root package (the declaring package); required, and not one of its own api packages. */
        public Builder packageName(String packageName) {
            data.packageName = packageName;
            return this;
        }

        public Builder apiPackages(Collection<String> packages) {
            data.apiPackages.addAll(packages);
            return this;
        }

        public Builder provides(Collection<String> contracts) {
            data.provides.addAll(contracts);
            return this;
        }

        public Builder requires(Collection<String> ids) {
            data.requires.addAll(ids);
            return this;
        }

        public Builder optional(Collection<String> ids) {
            data.optional.addAll(ids);
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

        public ModuleDescriptor build() {
            List<Diagnostic> problems = data.validate();
            if (!problems.isEmpty()) {
                throw new TopolithException(problems);
            }
            return new ModuleDescriptor(data);
        }
    }
}
