package net.officefloor.hq.app.adjustmentnote;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Payload to issue an adjustment note: a positive amount charges more, a negative one charges less. */
public record AdjustmentNoteRequest(
        @NotNull @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @NotBlank @Size(max = 500) String reason) {
}
