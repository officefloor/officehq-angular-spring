package net.officefloor.hq.app.asof;

import javax.sql.DataSource;

/**
 * The store the request in hand reads: while a screen shows a sheet as of a date, the sheet as it then stood (see
 * {@link AsOfService#storeAsOf}); otherwise none, and the request reads the store itself.
 */
public final class AsOfContext {

    private static final ThreadLocal<DataSource> STORE = new ThreadLocal<>();

    private AsOfContext() {
    }

    static void enter(DataSource store) {
        STORE.set(store);
    }

    static void leave() {
        STORE.remove();
    }

    /** The store the request reads as of a date; null when it reads the store itself. */
    static DataSource store() {
        return STORE.get();
    }

    /** Whether the request in hand is answered as of a date: it changes nothing and writes no audit record. */
    public static boolean active() {
        return STORE.get() != null;
    }
}
