package io.github.rey5137.topolith.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** The raw fields of one descriptor and the D-27 value rules, shared by both builders and the parser. */
final class DescriptorData {
    static final int FORMAT_VERSION = 1;
    static final int MAX_ID_LENGTH = 63;
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");
    private static final Pattern VERSION = Pattern.compile("[0-9]+([.-].*)?");

    final boolean module;
    String id;
    String source;
    int formatVersion = FORMAT_VERSION;
    String apiVersion;
    String packageName;
    final List<String> apiPackages = new ArrayList<>();
    final List<String> provides = new ArrayList<>();
    final List<String> requires = new ArrayList<>();
    final List<String> optional = new ArrayList<>();

    DescriptorData(boolean module, String id) {
        this.module = module;
        this.id = id;
    }

    static boolean validId(String value) {
        return value.length() <= MAX_ID_LENGTH && ID.matcher(value).matches();
    }

    static boolean validPackage(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (String segment : value.split("\\.", -1)) {
            if (!identifier(segment)) {
                return false;
            }
        }
        return true;
    }

    private static boolean identifier(String segment) {
        if (segment.isEmpty() || !Character.isJavaIdentifierStart(segment.codePointAt(0))) {
            return false;
        }
        return segment.codePoints().allMatch(Character::isJavaIdentifierPart);
    }

    /** Every D-27 violation of the values, as TL0013 diagnostics in a fixed order; empty when valid. */
    List<Diagnostic> validate() {
        List<Diagnostic> found = new ArrayList<>();
        String where = source == null ? "descriptor" : source;
        if (formatVersion > FORMAT_VERSION) {
            found.add(bad(where, "format-version", "descriptor format " + formatVersion
                    + " is newer than this runtime (max " + FORMAT_VERSION + ")", "Upgrade Topolith."));
        } else if (formatVersion < FORMAT_VERSION) {
            found.add(bad(where, "format-version", "must be " + FORMAT_VERSION + ", not " + formatVersion,
                    "Set format-version=" + FORMAT_VERSION + "."));
        }
        if (id == null || id.isEmpty()) {
            found.add(bad(where, "id", "is missing", "Add id=<module id>."));
        } else {
            if (!validId(id)) {
                found.add(bad(where, "id", "'" + id + "' is not a valid id: use lower-case letters, digits and "
                        + "single hyphens, starting with a letter, at most " + MAX_ID_LENGTH + " characters",
                        "Rename the id, for example 'order-history'."));
            }
            if (source != null && !fileName(source).equals(id + ".properties")) {
                found.add(bad(where, "id", "'" + id + "' does not match the file name '" + fileName(source) + "'",
                        "Name the file '" + id + ".properties' or fix the id."));
            }
        }
        if (apiPackages.isEmpty()) {
            found.add(bad(where, "api-packages", "is missing or empty", "List at least one API package."));
        }
        checkList(found, where, "api-packages", apiPackages, DescriptorData::validPackage, "a Java package name");
        if (apiVersion == null || apiVersion.isEmpty()) {
            found.add(bad(where, "api-version", "is missing", "Add api-version=<major>.<minor>.<patch>."));
        } else if (!VERSION.matcher(apiVersion).matches()) {
            found.add(bad(where, "api-version", "'" + apiVersion + "' does not start with a numeric major version",
                    "Start the version with its major number, for example 1.0.0."));
        }
        checkList(found, where, "provides", provides, DescriptorData::validFqcn, "a fully qualified class name");
        for (String entry : provides) {
            if (validFqcn(entry) && !apiPackages.contains(entry.substring(0, entry.lastIndexOf('.')))) {
                found.add(bad(where, "provides", "'" + entry + "' is not in a listed API package",
                        "Move it to one of the api-packages (exact packages, no subpackages) or list its package."));
            }
        }
        if (module) {
            checkPackage(found, where);
            checkList(found, where, "requires", requires, DescriptorData::validId, "a module id");
            checkList(found, where, "optional", optional, DescriptorData::validId, "a module id");
            if (id != null && (requires.contains(id) || optional.contains(id))) {
                found.add(bad(where, requires.contains(id) ? "requires" : "optional",
                        "module '" + id + "' lists itself", "Remove the module's own id from the list."));
            }
            for (String entry : requires) {
                if (optional.contains(entry)) {
                    found.add(bad(where, "optional", "'" + entry + "' is in both requires and optional",
                            "Keep it in one list."));
                }
            }
        }
        return found;
    }

    private void checkPackage(List<Diagnostic> found, String where) {
        if (packageName == null || packageName.isEmpty()) {
            found.add(bad(where, "package", "is missing", "Add package=<the module's root package>."));
        } else if (!validPackage(packageName)) {
            found.add(bad(where, "package", "'" + packageName + "' is not a Java package name",
                    "Use the package that holds the module's implementation, for example 'com.acme.order'."));
        } else if (apiPackages.contains(packageName)) {
            found.add(bad(where, "package", "'" + packageName + "' is also one of the module's api-packages",
                    "The module's root package holds its implementation; keep the API in its own package."));
        }
    }

    private static boolean validFqcn(String value) {
        return value != null && value.indexOf('.') > 0 && validPackage(value);
    }

    private static void checkList(List<Diagnostic> found, String where, String key, List<String> entries,
            Predicate<String> valid, String expected) {
        Set<String> seen = new HashSet<>();
        for (String entry : entries) {
            if (entry == null) {
                found.add(bad(where, key, "has a null entry", "Remove the null entry."));
            } else if (!valid.test(entry)) {
                found.add(bad(where, key, "entry '" + entry + "' is not " + expected,
                        "Fix or remove the entry."));
            } else if (!seen.add(entry)) {
                found.add(bad(where, key, "entry '" + entry + "' is listed twice", "Remove the duplicate."));
            }
        }
    }

    static Diagnostic bad(String where, String key, String problem, String action) {
        return new Diagnostic(TopolithCode.TL0013, where + ": key '" + key + "' " + problem + ".", action);
    }

    static String fileName(String source) {
        return source.substring(Math.max(source.lastIndexOf('/'), source.lastIndexOf('\\')) + 1);
    }
}
