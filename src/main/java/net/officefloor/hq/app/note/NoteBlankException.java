package net.officefloor.hq.app.note;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A note added with a blank text. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class NoteBlankException extends RuntimeException {
}
