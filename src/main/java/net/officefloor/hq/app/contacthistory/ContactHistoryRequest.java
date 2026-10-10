package net.officefloor.hq.app.contacthistory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Payload to record that a client was contacted. */
public record ContactHistoryRequest(@NotNull LocalDate date, @NotBlank @Size(max = 1000) String note) {
}
