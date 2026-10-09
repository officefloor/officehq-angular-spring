package net.officefloor.hq.app.client;

/** At-a-glance counts of a client's projects and contacts. */
public record ClientSummaryResponse(long projectCount, long contactCount) {
}
