package net.officefloor.hq.app.project;

import jakarta.validation.constraints.Size;

/** Payload to note a file reference against a job; leave it out or blank to clear it. */
public record ProjectFileRefRequest(@Size(max = 100) String fileRef) {
}
