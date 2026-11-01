package net.officefloor.hq.app.setting;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A viewer can change nothing on the sheet. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ViewerRefusedException extends RuntimeException {}
