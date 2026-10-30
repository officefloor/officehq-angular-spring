package net.officefloor.hq.app.pool;

import java.math.BigDecimal;

/** A person was added to a pool with a share ({@code POOL_MEMBER_ADDED}). */
public record PoolMemberAdded(String sheetId, String user, String pool, String name, BigDecimal share) {
}
