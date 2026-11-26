package io.github.rey5137.topolith.spring.test;

import java.lang.reflect.Type;

/** Writes a value as JSON and reads it back, as the host's HTTP message converters would (R-TEST-04). */
interface ByValueSerializer {
    /** A copy read back by {@code type}, with the JSON tree of the original. */
    Copy copy(Object value, Type type);

    /** The JSON tree of {@code value} written as {@code type}. */
    Object tree(Object value, Type type);

    record Copy(Object copy, Object tree) { }
}
