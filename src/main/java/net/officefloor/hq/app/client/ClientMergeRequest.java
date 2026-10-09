package net.officefloor.hq.app.client;

import jakarta.validation.constraints.NotNull;

/** Payload to merge a duplicate client into the client it duplicates. */
public record ClientMergeRequest(@NotNull Long targetId) {
}
