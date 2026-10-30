package net.officefloor.hq.app.claim;

/** A void reason was added to a sheet's list ({@code VOID_REASON_ADDED}). */
public record VoidReasonAdded(String sheetId, String user, String reason) {
}
