package net.officefloor.hq.app.pool;

import java.math.BigDecimal;

/**
 * A member's cut in a pool: the pool by its id (a crew's id negated), the member as {@code person:<id>} or
 * {@code pool:<id>}, and the cut (two decimals).
 */
public record PoolCut(Long poolId, String member, BigDecimal cut) {

    /** The member key of a person. */
    public static String person(Long personId) {
        return "person:" + personId;
    }

    /** The member key of a pool. */
    public static String pool(Long poolId) {
        return "pool:" + poolId;
    }
}
