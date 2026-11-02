package net.officefloor.hq.app.sheet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The user's role may not change the sheet's today by the sheet's permission matrix. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class SheetTodayRefusedException extends RuntimeException {}
