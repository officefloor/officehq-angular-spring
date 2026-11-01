package net.officefloor.hq.app.asof;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A request handler that only reads the sheet although it is not a GET (loading a sheet): it is shown
 * as of the user's as-of date like any read, not refused like a change.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AsOfRead {}
