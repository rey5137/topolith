package io.github.rey5137.topolith.processor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import io.github.rey5137.topolith.contract.Idempotent;
import io.github.rey5137.topolith.core.Diagnostic;
import io.github.rey5137.topolith.core.TopolithCode;

/**
 * The D-100 idempotency rule over the compiler's model (R-DESC-05): which methods of a provided contract are
 * idempotent, and where {@code @Idempotent} may not be placed.
 */
final class Idempotency {
    private final Elements elements;
    private final Types types;

    Idempotency(Elements elements, Types types) {
        this.elements = elements;
        this.types = types;
    }

    /**
     * The sorted {@code <contract>#<method>} entries of the contracts, adding a TL0013 to {@code problems} for each
     * method name whose abstract overloads on one contract disagree, and for each {@code @Idempotent} default method a
     * contract exposes, wherever it is declared (a library superinterface included), as startup rejects it (TL0040).
     */
    Set<String> methods(Collection<TypeElement> contracts, List<Diagnostic> problems) {
        Set<String> entries = new TreeSet<>();
        for (TypeElement contract : contracts) {
            String name = elements.getBinaryName(contract).toString();
            boolean whole = contract.getAnnotation(Idempotent.class) != null;
            Map<String, Set<Boolean>> byName = new TreeMap<>();
            for (ExecutableElement method : exposed(contract)) {
                if (method.getModifiers().contains(Modifier.DEFAULT)
                        && method.getAnnotation(Idempotent.class) != null) {
                    Diagnostic misplaced = misplaced(((TypeElement) method.getEnclosingElement()).getQualifiedName()
                            + "#" + method.getSimpleName(), "a default method");
                    if (!problems.contains(misplaced)) {
                        problems.add(misplaced);
                    }
                } else if (method.getModifiers().contains(Modifier.ABSTRACT) && !objectMethod(method)) {
                    boolean idempotent = whole || method.getAnnotation(Idempotent.class) != null
                            || method.getEnclosingElement().getAnnotation(Idempotent.class) != null;
                    byName.computeIfAbsent(method.getSimpleName().toString(), key -> new TreeSet<>()).add(idempotent);
                }
            }
            byName.forEach((method, flags) -> {
                if (flags.size() > 1) {
                    problems.add(new Diagnostic(TopolithCode.TL0013, "The overloads of " + name + "#" + method
                            + " disagree on @Idempotent.",
                            "Put @Idempotent on every abstract overload of " + method + " or on none."));
                } else if (flags.contains(true)) {
                    entries.add(name + "#" + method);
                }
            });
        }
        return entries;
    }

    /**
     * Adds a TL0013 for each {@code @Idempotent} on a type that is not an interface, a type outside the api packages,
     * a method of either, or a {@code default}, {@code static} or {@code private} interface method.
     */
    void misplaced(Collection<TypeElement> compiled, Set<String> apiPackages, List<Diagnostic> problems) {
        for (TypeElement type : compiled) {
            String where = placement(type, apiPackages);
            if (type.getAnnotation(Idempotent.class) != null && where != null) {
                problems.add(misplaced(type.getQualifiedName().toString(), where));
            }
            for (ExecutableElement method : ElementFilter.methodsIn(type.getEnclosedElements())) {
                if (method.getAnnotation(Idempotent.class) == null) {
                    continue;
                }
                Set<Modifier> modifiers = method.getModifiers();
                String methodWhere = where != null ? where
                        : modifiers.contains(Modifier.DEFAULT) ? "a default method"
                        : modifiers.contains(Modifier.STATIC) ? "a static method"
                        : modifiers.contains(Modifier.PRIVATE) ? "a private method" : null;
                if (methodWhere != null) {
                    problems.add(misplaced(type.getQualifiedName() + "#" + method.getSimpleName(), methodWhere));
                }
            }
        }
    }

    private String placement(TypeElement type, Set<String> apiPackages) {
        if (type.getKind() != ElementKind.INTERFACE) {
            return "a type that is not an interface";
        }
        String pkg = elements.getPackageOf(type).getQualifiedName().toString();
        return apiPackages.contains(pkg) ? null : "an interface outside the api packages " + new TreeSet<>(apiPackages);
    }

    private static Diagnostic misplaced(String element, String where) {
        return new Diagnostic(TopolithCode.TL0013, "@Idempotent on " + element + " is on " + where + ".",
                "Put @Idempotent on an abstract method of a contract interface in an api package, or on the "
                        + "contract interface.");
    }

    /**
     * The methods the interface exposes: its own declarations, then each superinterface method it inherits, one
     * declaration per signature (the one it overrides wins, JLS 9.4.1). Static and private methods are not inherited.
     */
    private List<ExecutableElement> exposed(TypeElement type) {
        DeclaredType owner = (DeclaredType) type.asType();
        List<ExecutableElement> result = new ArrayList<>(ElementFilter.methodsIn(type.getEnclosedElements()));
        List<ExecutableElement> inherited = new ArrayList<>();
        for (TypeMirror supertype : type.getInterfaces()) {
            if (supertype.getKind() == TypeKind.DECLARED && types.asElement(supertype) instanceof TypeElement parent) {
                for (ExecutableElement method : exposed(parent)) {
                    Set<Modifier> modifiers = method.getModifiers();
                    if (!modifiers.contains(Modifier.STATIC) && !modifiers.contains(Modifier.PRIVATE)
                            && !inherited.contains(method)) {
                        inherited.add(method);
                    }
                }
            }
        }
        for (ExecutableElement method : inherited) {
            boolean overridden = result.stream().anyMatch(own -> own.getEnclosingElement() == type
                    && sameSignature(owner, own, method))
                    || inherited.stream().anyMatch(other -> other != method
                            && other.getEnclosingElement() != method.getEnclosingElement()
                            && sameSignature(owner, other, method) && types.isSubtype(
                                    types.erasure(other.getEnclosingElement().asType()),
                                    types.erasure(method.getEnclosingElement().asType())));
            if (!overridden) {
                result.add(method);
            }
        }
        return result;
    }

    private boolean sameSignature(DeclaredType owner, ExecutableElement first, ExecutableElement second) {
        if (!first.getSimpleName().equals(second.getSimpleName())) {
            return false;
        }
        List<? extends TypeMirror> a = ((ExecutableType) types.asMemberOf(owner, first)).getParameterTypes();
        List<? extends TypeMirror> b = ((ExecutableType) types.asMemberOf(owner, second)).getParameterTypes();
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!types.isSameType(types.erasure(a.get(i)), types.erasure(b.get(i)))) {
                return false;
            }
        }
        return true;
    }

    /** A redeclaration of a public {@code java.lang.Object} method, which a contract never exposes as a call. */
    private boolean objectMethod(ExecutableElement method) {
        TypeElement object = elements.getTypeElement(Object.class.getName());
        for (ExecutableElement candidate : ElementFilter.methodsIn(object.getEnclosedElements())) {
            if (candidate.getModifiers().contains(Modifier.PUBLIC)
                    && candidate.getSimpleName().equals(method.getSimpleName())
                    && candidate.getParameters().size() == method.getParameters().size()
                    && sameErasure(candidate, method)) {
                return true;
            }
        }
        return false;
    }

    private boolean sameErasure(ExecutableElement first, ExecutableElement second) {
        for (int i = 0; i < first.getParameters().size(); i++) {
            if (!types.isSameType(types.erasure(first.getParameters().get(i).asType()),
                    types.erasure(second.getParameters().get(i).asType()))) {
                return false;
            }
        }
        return true;
    }
}
