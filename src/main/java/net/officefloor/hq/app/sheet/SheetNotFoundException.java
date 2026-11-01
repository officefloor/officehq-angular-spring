package net.officefloor.hq.app.sheet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** No sheet has been started with that id. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class SheetNotFoundException extends RuntimeException {}
