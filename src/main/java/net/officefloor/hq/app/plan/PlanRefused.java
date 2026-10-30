package net.officefloor.hq.app.plan;

/** A plan was refused: nothing was written. */
public class PlanRefused extends RuntimeException {

    public PlanRefused(String message) {
        super(message);
    }
}
