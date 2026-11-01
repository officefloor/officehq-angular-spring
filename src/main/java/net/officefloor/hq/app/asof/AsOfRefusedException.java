package net.officefloor.hq.app.asof;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** While a user views a sheet as of a date, they can change nothing on it. */
@ResponseStatus(HttpStatus.CONFLICT)
public class AsOfRefusedException extends RuntimeException {}
