package net.officefloor.hq.app.rate;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A withdrawal of a rate the grade does not have for that from-date. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class RateNotFoundException extends RuntimeException {
}
