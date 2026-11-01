package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A pasted line that is not name,amount,date,note as the claim form takes them, or names no one on the sheet. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class ClaimPasteRefusedException extends RuntimeException {}
