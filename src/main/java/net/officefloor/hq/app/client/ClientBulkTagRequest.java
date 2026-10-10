package net.officefloor.hq.app.client;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Payload to put one tag, by name, on several clients at once; the tag is created if it does not exist yet. */
public record ClientBulkTagRequest(@NotEmpty List<@NotNull Long> clientIds, @NotBlank @Size(max = 50) String tag) {
}
