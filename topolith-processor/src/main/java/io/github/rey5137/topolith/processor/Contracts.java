package io.github.rey5137.topolith.processor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/** Finds the contract interfaces of API packages and the ones a class implements (D-29). */
final class Contracts {
    private final Elements elements;
    private final Types types;

    Contracts(Elements elements, Types types) {
        this.elements = elements;
        this.types = types;
    }

    /** Every class and interface, member types included, declared by the given top-level types. */
    List<TypeElement> allTypes(Collection<String> topLevelNames) {
        List<TypeElement> found = new ArrayList<>();
        for (String name : topLevelNames) {
            TypeElement type = elements.getTypeElement(name);
            if (type != null) {
                collect(type, found);
            }
        }
        return found;
    }

    private static void collect(TypeElement type, List<TypeElement> into) {
        into.add(type);
        for (Element member : type.getEnclosedElements()) {
            if (member instanceof TypeElement nested) {
                collect(nested, into);
            }
        }
    }

    String packageOf(TypeElement type) {
        return elements.getPackageOf(type).getQualifiedName().toString();
    }

    /** A public, non-sealed, non-annotation interface in exactly one of the api packages. */
    boolean isContract(TypeElement type, Set<String> apiPackages) {
        if (type.getKind() != ElementKind.INTERFACE || type.getModifiers().contains(Modifier.SEALED)
                || !apiPackages.contains(packageOf(type))) {
            return false;
        }
        for (Element current = type; current instanceof TypeElement owner; current = current.getEnclosingElement()) {
            boolean implicitlyPublic = owner.getEnclosingElement() instanceof TypeElement outer
                    && outer.getKind().isInterface();
            if (!owner.getModifiers().contains(Modifier.PUBLIC) && !implicitlyPublic) {
                return false;
            }
        }
        return true;
    }

    /** The contracts the api packages declare in this compilation. */
    Set<TypeElement> declared(List<TypeElement> all, Set<String> apiPackages) {
        Set<TypeElement> found = new LinkedHashSet<>();
        for (TypeElement type : all) {
            if (isContract(type, apiPackages)) {
                found.add(type);
            }
        }
        return found;
    }

    /** Whether the class can be instantiated by a bean definition: concrete, not an interface or annotation. */
    static boolean isConcrete(TypeElement type) {
        return type.getKind().isClass() && !type.getModifiers().contains(Modifier.ABSTRACT);
    }

    /** The contracts in the supertype closure of the class: superclasses, interfaces and their superinterfaces. */
    Set<TypeElement> implemented(TypeElement type, Set<String> apiPackages) {
        Set<TypeElement> seen = new LinkedHashSet<>();
        Set<TypeElement> found = new LinkedHashSet<>();
        Deque<TypeElement> pending = new ArrayDeque<>();
        pending.add(type);
        while (!pending.isEmpty()) {
            TypeElement current = pending.remove();
            if (!seen.add(current)) {
                continue;
            }
            if (isContract(current, apiPackages)) {
                found.add(current);
            }
            addSupertype(pending, current.getSuperclass());
            current.getInterfaces().forEach(supertype -> addSupertype(pending, supertype));
        }
        return found;
    }

    private void addSupertype(Deque<TypeElement> pending, TypeMirror supertype) {
        if (supertype.getKind() == TypeKind.DECLARED
                && types.asElement(types.erasure(supertype)) instanceof TypeElement element) {
            pending.add(element);
        }
    }

    /** Binary names of the contracts, minus any that is a superinterface of another, sorted. */
    Set<String> mostDerived(Collection<TypeElement> contracts) {
        Set<String> names = new TreeSet<>();
        for (TypeElement contract : contracts) {
            boolean covered = contracts.stream().anyMatch(other -> other != contract && types.isSubtype(
                    types.erasure(other.asType()), types.erasure(contract.asType())));
            if (!covered) {
                names.add(elements.getBinaryName(contract).toString());
            }
        }
        return names;
    }
}
