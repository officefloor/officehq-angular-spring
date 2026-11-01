package net.officefloor.hq.app.rate;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A pasted line of rates is not one the rate form would accept. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class RateImportRefusedException extends RuntimeException {}
