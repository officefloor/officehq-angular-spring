package net.officefloor.hq.app.pool;

/** A pool was added to a sheet ({@code POOL_ADDED}). */
public record PoolAdded(String sheetId, String user, String name) {
}
