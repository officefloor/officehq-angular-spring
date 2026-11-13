package net.officefloor.hq.app.plan;

/** A plan that cannot be added (a bad amount or interval, or an end before its start), or one already paused or
 *  not paused that is paused or resumed. */
public class PlanRefusedException extends RuntimeException {

    public PlanRefusedException(String message) {
        super(message);
    }
}
