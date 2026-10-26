package net.officefloor.hq.app.asof;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a request that is not a GET yet changes nothing, so a screen showing the sheet as of a date may still make it
 * (and is answered as of that date): loading a sheet.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AsOfReads {
}
