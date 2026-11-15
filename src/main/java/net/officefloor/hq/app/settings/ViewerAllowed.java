package net.officefloor.hq.app.settings;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a changing request a viewer, or anyone on an archived sheet, is not refused at the door:
 *  it changes nothing (a sheet load) or the handler itself does nothing for a viewer or on an
 *  archived sheet (a bulk action or a generate: 0 done, every selected item refused). */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ViewerAllowed {
}
