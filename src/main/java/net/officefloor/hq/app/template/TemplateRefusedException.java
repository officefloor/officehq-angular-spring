package net.officefloor.hq.app.template;

/** A claim template that cannot be saved (a blank name, or an amount a claim would refuse), or used (none by
 *  the name). */
public class TemplateRefusedException extends RuntimeException {

    public TemplateRefusedException(String message) {
        super(message);
    }
}
