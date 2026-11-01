package net.officefloor.hq.app.statement;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Only a steward can cancel a person's last statement, and only when they have one. */
@ResponseStatus(HttpStatus.CONFLICT)
public class StatementCancelRefusedException extends RuntimeException {}
