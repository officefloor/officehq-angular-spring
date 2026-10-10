package net.officefloor.hq.app.project;

import jakarta.validation.constraints.Size;

/** Payload to put a job into a category; leave it out or blank to take it out of its category. */
public record ProjectCategoryRequest(@Size(max = 50) String category) {
}
