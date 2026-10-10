package net.officefloor.hq.app.project;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Payload to put jobs into an order: their ids, first to last, as the list shows them. */
public record ProjectOrderRequest(@NotEmpty List<@NotNull Long> ids) {
}
