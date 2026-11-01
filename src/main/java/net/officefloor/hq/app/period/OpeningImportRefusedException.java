package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A pasted line of opening balances is not a person and a period on the sheet and an amount of 0.00 or more. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class OpeningImportRefusedException extends RuntimeException {}
