package net.officefloor.hq.app.setting;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A request handler a viewer may call although it is not a read: loading a sheet, or a bulk change
 * that itself refuses each selected item for a viewer.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OpenToViewer {}
