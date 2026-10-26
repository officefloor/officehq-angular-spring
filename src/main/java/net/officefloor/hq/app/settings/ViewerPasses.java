package net.officefloor.hq.app.settings;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a changing request a viewer may still make: loading a sheet or previewing a rate, which change nothing, or a bulk or generate
 * action, which goes through for a viewer with nothing done and every item refused.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ViewerPasses {
}
