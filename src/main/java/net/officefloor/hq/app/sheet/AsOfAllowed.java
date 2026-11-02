package net.officefloor.hq.app.sheet;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a changing request a screen showing the sheet as of a date still takes, because it changes
 *  nothing the screen shows: loading the sheet. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AsOfAllowed {
}
