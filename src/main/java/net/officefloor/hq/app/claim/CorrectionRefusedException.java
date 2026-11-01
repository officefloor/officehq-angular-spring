package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A correction that names no SETTLED claim of its person, or a claim that names one but is no correction. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class CorrectionRefusedException extends RuntimeException {}
