package io.github.rey5137.topolith.processor;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

import io.github.rey5137.topolith.annotation.TopolithModule;
import io.github.rey5137.topolith.core.ApiDescriptor;
import io.github.rey5137.topolith.core.Descriptors;
import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.Incubating;
import io.github.rey5137.topolith.core.ModuleDescriptor;
import io.github.rey5137.topolith.core.TopolithCode;
import io.github.rey5137.topolith.core.TopolithException;

/**
 * Turns the one {@link TopolithModule} of a compilation into {@code META-INF/topolith/modules/<id>.properties} or
 * {@code apis/<id>.properties} (D-29). Elements are not kept across rounds: the rounds only record names, and the
 * work happens in the last round against the final symbol table. Problems are compile errors, never exceptions.
 */
@Incubating
public final class TopolithModuleProcessor extends AbstractProcessor {
    static final String API_VERSION_OPTION = "topolith.apiVersion";
    private static final String DIRECTORY = "META-INF/topolith/";

    private record Declaration(String packageName, String id, List<String> api, List<String> requires,
            List<String> optional) {
    }

    private final List<Declaration> declarations = new ArrayList<>();
    private final Set<String> topLevelTypes = new TreeSet<>();
    private final Set<String> packageInfos = new TreeSet<>();

    /** Where an api package's types come from: this build, the classpath, both (an error) or nowhere. */
    private enum Origin { LOCAL, CLASSPATH, SPLIT, ABSENT }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of("*");
    }

    @Override
    public Set<String> getSupportedOptions() {
        return Set.of(API_VERSION_OPTION);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (round.processingOver()) {
            if (!round.errorRaised() && !declarations.isEmpty()) {
                finish();
            }
            return false;
        }
        for (Element root : round.getRootElements()) {
            if (root instanceof TypeElement type) {
                topLevelTypes.add(type.getQualifiedName().toString());
            } else if (root instanceof PackageElement pkg) {
                packageInfos.add(pkg.getQualifiedName().toString());
            }
        }
        for (Element annotated : round.getElementsAnnotatedWith(TopolithModule.class)) {
            if (annotated instanceof PackageElement pkg) {
                TopolithModule declaration = pkg.getAnnotation(TopolithModule.class);
                declarations.add(new Declaration(pkg.getQualifiedName().toString(), declaration.id(),
                        Arrays.asList(declaration.api()), Arrays.asList(declaration.requires()),
                        Arrays.asList(declaration.optional())));
            }
        }
        return false;
    }

    private void finish() {
        Declaration declaration = declarations.get(0);
        List<Diagnostic> problems = new ArrayList<>();
        if (declarations.size() > 1) {
            problems.add(new Diagnostic(TopolithCode.TL0013, "The compilation has " + declarations.size()
                    + " @TopolithModule declarations, in " + declarations.stream().map(Declaration::packageName)
                            .sorted().toList() + ".", "Keep one declaration per compilation."));
        } else {
            try {
                declare(declaration, problems);
            } catch (TopolithException failure) {
                problems.addAll(failure.diagnostics());
            }
        }
        PackageElement pkg = processingEnv.getElementUtils().getPackageElement(declaration.packageName());
        problems.forEach(problem -> processingEnv.getMessager().printMessage(Kind.ERROR, problem.toString(), pkg,
                mirror(pkg)));
    }

    /** Sorted for a deterministic file; duplicates stay so the core builder rejects them. */
    private static List<String> sorted(List<String> values) {
        return values.stream().sorted().toList();
    }

    private AnnotationMirror mirror(PackageElement pkg) {
        if (pkg != null) {
            for (AnnotationMirror candidate : pkg.getAnnotationMirrors()) {
                if (candidate.getAnnotationType().toString().equals(TopolithModule.class.getCanonicalName())) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private void declare(Declaration declaration, List<Diagnostic> problems) {
        String version = processingEnv.getOptions().get(API_VERSION_OPTION);
        if (version == null || version.isBlank()) {
            problems.add(new Diagnostic(TopolithCode.TL0013, "The processor option -A" + API_VERSION_OPTION
                    + " is missing, so the descriptor has no api-version.",
                    "Add -A" + API_VERSION_OPTION + "=${project.version} to the compiler plugin."));
            return;
        }
        Set<String> apiPackages = new LinkedHashSet<>(declaration.api());
        Contracts contracts = new Contracts(processingEnv.getElementUtils(), processingEnv.getTypeUtils());
        Set<String> names = new TreeSet<>(topLevelTypes);
        names.addAll(localMembers(declaration.packageName()));
        apiPackages.forEach(name -> names.addAll(localMembers(name)));
        List<TypeElement> compiled = contracts.allTypes(names);
        idempotency().misplaced(compiled, apiPackages, problems);
        if (apiPackages.contains(declaration.packageName())) {
            declareApi(declaration, version, contracts, compiled, problems);
        } else {
            declareModule(declaration, version, contracts, compiled, problems);
        }
    }

    private void declareApi(Declaration declaration, String version, Contracts contracts, List<TypeElement> compiled,
            List<Diagnostic> problems) {
        Set<String> apiPackages = new LinkedHashSet<>(declaration.api());
        if (!declaration.requires().isEmpty() || !declaration.optional().isEmpty()) {
            problems.add(new Diagnostic(TopolithCode.TL0013, "The API declaration of '" + declaration.id()
                    + "' sets requires or optional.", "Move them to the module declaration."));
        }
        for (String name : apiPackages) {
            Origin origin = origin(name);
            if (origin == Origin.SPLIT) {
                problems.add(split(name));
            } else if (origin != Origin.LOCAL) {
                problems.add(new Diagnostic(TopolithCode.TL0013, "API package '" + name + "' has no type in this "
                        + "compilation.", "Check the spelling or put the API declaration in the module that compiles "
                                + "it."));
            }
        }
        Set<TypeElement> declared = contracts.declared(compiled, apiPackages);
        Set<String> provides = contracts.mostDerived(declared);
        Set<String> idempotent = idempotency().methods(contracts.named(declared, provides), problems);
        ApiDescriptor api = ApiDescriptor.builder(declaration.id()).apiPackages(sorted(declaration.api()))
                .provides(provides).idempotent(idempotent).apiVersion(version).build();
        if (problems.isEmpty()) {
            write("apis/" + api.id(), problems, out -> Descriptors.write(api, out));
        }
    }

    private void declareModule(Declaration declaration, String version, Contracts contracts,
            List<TypeElement> compiled, List<Diagnostic> problems) {
        if (declaration.api().isEmpty()) {
            declareWithoutContracts(declaration, version, problems);
            return;
        }
        Set<String> apiPackages = new LinkedHashSet<>(declaration.api());
        Set<String> onClasspath = new LinkedHashSet<>();
        boolean anyLocal = false;
        boolean anySplit = false;
        for (String name : apiPackages) {
            switch (origin(name)) {
                case LOCAL -> anyLocal = true;
                case CLASSPATH -> onClasspath.add(name);
                case SPLIT -> {
                    anySplit = true;
                    problems.add(split(name));
                }
                case ABSENT -> problems.add(new Diagnostic(TopolithCode.TL0013, "API package '" + name + "' is "
                        + "neither in this compilation nor on the classpath.", "Check the spelling in the api list."));
            }
        }
        if (anyLocal && !onClasspath.isEmpty()) {
            problems.add(new Diagnostic(TopolithCode.TL0013, "API packages " + onClasspath + " are not in this "
                    + "compilation, but other api packages are.",
                    "Compile all api packages with the module or none of them."));
        }
        if (anySplit) {
            return;
        }
        boolean sameCompilation = anyLocal && onClasspath.isEmpty() && problems.isEmpty();
        boolean classpath = !anyLocal;
        Set<TypeElement> implemented = implemented(declaration, contracts, compiled, apiPackages);
        Set<String> provides = contracts.mostDerived(implemented);
        Set<String> idempotent = idempotency().methods(contracts.named(implemented, provides), problems);
        ModuleDescriptor module = ModuleDescriptor.builder(declaration.id()).packageName(declaration.packageName())
                .apiPackages(sorted(declaration.api())).provides(provides).idempotent(idempotent)
                .requires(sorted(declaration.requires())).optional(sorted(declaration.optional()))
                .apiVersion(version).build();
        if (sameCompilation) {
            Set<TypeElement> declaredTypes = contracts.declared(compiled, apiPackages);
            Set<String> declared = contracts.mostDerived(declaredTypes);
            compare(declaration, "this compilation", declared, provides, problems);
            compareIdempotent("this compilation", contracts, declaredTypes, declared, idempotent, problems);
            if (problems.isEmpty()) {
                ApiDescriptor api = module.api();
                write("apis/" + api.id(), problems, out -> Descriptors.write(api, out));
            }
        } else if (classpath) {
            verifyClasspath(declaration, module, contracts, problems);
        }
        if (problems.isEmpty()) {
            write("modules/" + module.id(), problems, out -> Descriptors.write(module, out));
        }
    }

    /** A module without contracts (D-115): a module descriptor with no api keys and no API descriptor. */
    private void declareWithoutContracts(Declaration declaration, String version, List<Diagnostic> problems) {
        ModuleDescriptor module = ModuleDescriptor.builder(declaration.id()).packageName(declaration.packageName())
                .requires(sorted(declaration.requires())).optional(sorted(declaration.optional()))
                .apiVersion(version).build();
        if (problems.isEmpty()) {
            write("modules/" + module.id(), problems, out -> Descriptors.write(module, out));
        }
    }

    private Diagnostic split(String name) {
        return new Diagnostic(TopolithCode.TL0013, "API package '" + name + "' is split: this compilation and the "
                + "classpath both hold types or a @TopolithModule in it.",
                "Keep an api package in one artifact, and move the module's own classes out of it.");
    }

    /** The top-level types of a package that javac sees, whichever way they got there. */
    private List<TypeElement> members(String packageName) {
        PackageElement pkg = processingEnv.getElementUtils().getPackageElement(packageName);
        List<TypeElement> found = new ArrayList<>();
        if (pkg != null) {
            for (Element member : pkg.getEnclosedElements()) {
                if (member instanceof TypeElement type) {
                    found.add(type);
                }
            }
        }
        return found;
    }

    /** Names of the package's types built by this module: in this compilation or already in its output directory. */
    private List<String> localMembers(String packageName) {
        return members(packageName).stream().filter(this::isLocal).map(type -> type.getQualifiedName().toString())
                .toList();
    }

    private boolean isLocal(TypeElement type) {
        if (topLevelTypes.contains(type.getQualifiedName().toString())) {
            return true;
        }
        String binary = processingEnv.getElementUtils().getBinaryName(type).toString();
        String pkg = processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
        try {
            FileObject file = processingEnv.getFiler().getResource(StandardLocation.CLASS_OUTPUT, pkg,
                    binary.substring(pkg.isEmpty() ? 0 : pkg.length() + 1) + ".class");
            try (InputStream in = file.openInputStream()) {
                return true;
            }
        } catch (IOException | RuntimeException absent) {
            return false;
        }
    }

    /**
     * Classifies an api package by what it contains, not by root elements, so a partial compile that skips its types
     * still sees them in the output directory. A classpath {@code @TopolithModule} is an API declaration of an
     * artifact built elsewhere.
     */
    private Origin origin(String name) {
        PackageElement pkg = processingEnv.getElementUtils().getPackageElement(name);
        if (pkg == null) {
            return Origin.ABSENT;
        }
        List<TypeElement> members = members(name);
        long local = members.stream().filter(this::isLocal).count();
        boolean classpathDeclaration = pkg.getAnnotation(TopolithModule.class) != null && !packageInfos.contains(name);
        if (local == 0) {
            return Origin.CLASSPATH;
        }
        return local < members.size() || classpathDeclaration ? Origin.SPLIT : Origin.LOCAL;
    }

    private Set<TypeElement> implemented(Declaration declaration, Contracts contracts, List<TypeElement> compiled,
            Set<String> apiPackages) {
        String root = declaration.packageName();
        Set<TypeElement> implemented = new LinkedHashSet<>();
        for (TypeElement type : compiled) {
            String owner = contracts.packageOf(type);
            if (!Contracts.isConcrete(type) || apiPackages.contains(owner)) {
                continue;
            }
            Set<TypeElement> found = contracts.implemented(type, apiPackages);
            if (owner.equals(root) || owner.startsWith(root + ".")) {
                implemented.addAll(found);
            } else if (!found.isEmpty()) {
                processingEnv.getMessager().printMessage(Kind.WARNING, "Class " + type.getQualifiedName()
                        + " implements an API contract but lies outside the module package " + root
                        + ", so it is not counted in provides.", type);
            }
        }
        return implemented;
    }

    private void compare(Declaration declaration, String where, Set<String> declared, Set<String> implemented,
            List<Diagnostic> problems) {
        for (String contract : declared) {
            if (!implemented.contains(contract)) {
                problems.add(new Diagnostic(TopolithCode.TL0013, "The API in " + where + " provides " + contract
                        + " but no class in " + declaration.packageName() + " implements it.",
                        "Implement it or move it out of the api packages."));
            }
        }
        for (String contract : implemented) {
            if (!declared.contains(contract)) {
                problems.add(new Diagnostic(TopolithCode.TL0013, "The module implements " + contract
                        + " but the API in " + where + " does not provide it.",
                        "Rebuild the API artifact so its descriptor matches."));
            }
        }
    }

    /**
     * Verifies the module against the API on the classpath without reading the descriptor file, which javac cannot
     * open inside a JAR: the API declaration is the {@code @TopolithModule} in an api package's package-info class,
     * and the api's {@code provides} are recomputed from the classpath types with the rule the API build used.
     */
    private void verifyClasspath(Declaration declaration, ModuleDescriptor module, Contracts contracts,
            List<Diagnostic> problems) {
        boolean found = false;
        for (String name : module.apiPackages()) {
            PackageElement pkg = processingEnv.getElementUtils().getPackageElement(name);
            TopolithModule api = pkg == null ? null : pkg.getAnnotation(TopolithModule.class);
            if (api == null) {
                continue;
            }
            found = true;
            Set<String> declared = new TreeSet<>(Arrays.asList(api.api()));
            if (!api.id().equals(module.id()) || !declared.equals(new TreeSet<>(module.apiPackages()))) {
                problems.add(new Diagnostic(TopolithCode.TL0013, "The @TopolithModule on " + name + " has id '"
                        + api.id() + "' and api " + declared + " but the module declares id '" + module.id()
                        + "' and api " + new TreeSet<>(module.apiPackages()) + ".",
                        "Make the id and api lists equal."));
            }
        }
        if (!found) {
            problems.add(new Diagnostic(TopolithCode.TL0013, "None of the api packages " + module.apiPackages()
                    + " carries a @TopolithModule declaration on the classpath, so the API of '" + module.id()
                    + "' cannot be verified.", "Build the API artifact with @TopolithModule on its api package and "
                            + "the Topolith processor."));
            return;
        }
        List<String> names = new ArrayList<>();
        for (String name : module.apiPackages()) {
            members(name).forEach(type -> names.add(type.getQualifiedName().toString()));
        }
        Set<TypeElement> declaredTypes = contracts.declared(contracts.allTypes(names), module.apiPackages());
        Set<String> declared = contracts.mostDerived(declaredTypes);
        compare(declaration, "the classpath API", declared, module.provides(), problems);
        compareIdempotent("the classpath API", contracts, declaredTypes, declared, module.idempotent(), problems);
    }

    /**
     * Recomputes the API's idempotent methods from its contract types with the API declaration's rule (D-100) and
     * reports each entry the module's set lacks or adds; overload problems were already reported for the module.
     */
    private void compareIdempotent(String where, Contracts contracts, Set<TypeElement> declaredTypes,
            Set<String> declared, Set<String> module, List<Diagnostic> problems) {
        Set<String> api = idempotency().methods(contracts.named(declaredTypes, declared), new ArrayList<>());
        for (String entry : api) {
            if (!module.contains(entry)) {
                problems.add(new Diagnostic(TopolithCode.TL0013, "The API in " + where + " declares " + entry
                        + " idempotent but the module does not.", "Rebuild the API artifact so its descriptor "
                                + "matches, and keep @Idempotent on the contract in the api packages."));
            }
        }
        for (String entry : module) {
            if (!api.contains(entry)) {
                problems.add(new Diagnostic(TopolithCode.TL0013, "The module declares " + entry
                        + " idempotent but the API in " + where + " does not.", "Rebuild the API artifact so its "
                                + "descriptor matches, and keep @Idempotent on the contract in the api packages."));
            }
        }
    }

    private Idempotency idempotency() {
        return new Idempotency(processingEnv.getElementUtils(), processingEnv.getTypeUtils());
    }

    private void write(String name, List<Diagnostic> problems, Writing writing) {
        String path = DIRECTORY + name + ".properties";
        PackageElement origin = processingEnv.getElementUtils().getPackageElement(declarations.get(0).packageName());
        try {
            FileObject file = origin == null
                    ? processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", path)
                    : processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", path, origin);
            try (Writer out = file.openWriter()) {
                writing.write(out);
            }
        } catch (IOException failure) {
            problems.add(new Diagnostic(TopolithCode.TL0013, "Cannot write " + path + ": " + failure.getMessage(),
                    "Check the compiler output directory."));
        }
    }

    @FunctionalInterface
    private interface Writing {
        void write(Writer out) throws IOException;
    }
}
