package net.officefloor.hq.app.client;

/** A segment clients are grouped into, and how many clients are in it. */
public record ClientSegmentResponse(String segment, long count) {
}
