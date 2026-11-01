package net.officefloor.hq.app.crew;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The person is the crew's lead, or already in the crew. */
@ResponseStatus(HttpStatus.CONFLICT)
public class CrewMemberRefusedException extends RuntimeException {}
