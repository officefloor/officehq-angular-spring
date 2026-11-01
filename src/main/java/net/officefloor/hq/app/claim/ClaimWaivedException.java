package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The claim's late charge was already waived; it can be waived once. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimWaivedException extends RuntimeException {}
