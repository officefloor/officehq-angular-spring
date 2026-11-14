package net.officefloor.hq.app.plan;

/** A plan with a bad amount or interval, or one that ends before it starts. */
public class PlanRefusedException extends RuntimeException {

    public PlanRefusedException(String message) {
        super(message);
    }
}
