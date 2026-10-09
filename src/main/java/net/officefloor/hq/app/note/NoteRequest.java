package net.officefloor.hq.app.note;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Payload to write a note. */
public record NoteRequest(@NotBlank @Size(max = 2000) String text) {
}
