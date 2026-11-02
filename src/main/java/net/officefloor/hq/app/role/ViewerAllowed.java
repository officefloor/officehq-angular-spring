package net.officefloor.hq.app.role;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a changing request a viewer may still send, because it changes nothing for one: loading a
 *  sheet, or a bulk change that refuses each item a viewer selects (so it reports 0 done). */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ViewerAllowed {
}
