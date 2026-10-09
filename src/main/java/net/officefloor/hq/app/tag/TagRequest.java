package net.officefloor.hq.app.tag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Payload to create a tag. */
public record TagRequest(@NotBlank @Size(max = 50) String name) {
}
