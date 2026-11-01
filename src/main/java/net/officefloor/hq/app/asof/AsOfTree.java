package net.officefloor.hq.app.asof;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A read of a sheet's figures together with its descendants' (the sheets naming it as their parent,
 * theirs and so on): read as of the user's as-of date, every sheet of the tree is shown as it stood
 * at the end of that date, with that date as its today, not only the sheet itself.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AsOfTree {}
