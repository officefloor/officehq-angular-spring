package net.officefloor.hq.app.pool;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A person was added to a pool with a share, and its dates when given (null: none) ({@code POOL_MEMBER_ADDED}). */
public record PoolMemberAdded(String sheetId, String user, String pool, String name, BigDecimal share, LocalDate from,
        LocalDate to) {
}
