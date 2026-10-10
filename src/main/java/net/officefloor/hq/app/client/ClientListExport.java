package net.officefloor.hq.app.client;

/** The client list exported as CSV text, with how many clients it holds. */
public record ClientListExport(int count, String csv) {
}
