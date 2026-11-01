package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Only a steward can transfer a person to another sheet: not a retired person, not to this sheet, not to
 * an archived one, and not to one where their name, an alias or their contact is already taken.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonTransferRefusedException extends RuntimeException {}
