package net.officefloor.hq.app.sheet;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a changing request an archived sheet still takes, because it changes nothing on one:
 *  loading the sheet, or a bulk change that refuses each item on an archived sheet (so it reports 0
 *  done). */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ArchivedAllowed {
}
