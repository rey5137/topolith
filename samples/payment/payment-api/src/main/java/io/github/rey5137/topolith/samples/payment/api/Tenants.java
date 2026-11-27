package io.github.rey5137.topolith.samples.payment.api;

/** The sample's tenant holder: a thread local that hosts adapt to Topolith's tenant context, JDK types only. */
public final class Tenants {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private Tenants() {
    }

    /** The calling thread's tenant, or null. */
    public static String current() {
        return CURRENT.get();
    }

    /** Makes {@code tenant} current on this thread; null clears it. */
    public static void set(String tenant) {
        if (tenant == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(tenant);
        }
    }
}
