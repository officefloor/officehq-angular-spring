package net.officefloor.hq.app.note;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A note's text cannot be blank. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class NoteBlankException extends RuntimeException {}
