package net.officefloor.hq.app.history;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a changing request that is not refused while the screen shows the sheet as of a date: it
 *  changes nothing on the sheet (a sheet load). */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AsOfAllowed {
}
