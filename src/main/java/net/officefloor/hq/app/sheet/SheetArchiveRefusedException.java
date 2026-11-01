package net.officefloor.hq.app.sheet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Only a steward can archive the sheet. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class SheetArchiveRefusedException extends RuntimeException {}
