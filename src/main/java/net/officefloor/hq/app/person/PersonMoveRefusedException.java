package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The top person cannot move up the list, nor the bottom one down. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonMoveRefusedException extends RuntimeException {}
