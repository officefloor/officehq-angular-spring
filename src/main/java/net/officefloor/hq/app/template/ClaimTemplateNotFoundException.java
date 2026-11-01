package net.officefloor.hq.app.template;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** No claim template of that name is on the sheet. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ClaimTemplateNotFoundException extends RuntimeException {}
