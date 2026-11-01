package net.officefloor.hq.app.sheet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A sheet cannot name itself as its parent, nor a sheet that has it as an ancestor, nor a sheet never loaded. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class SheetParentRefusedException extends RuntimeException {}
