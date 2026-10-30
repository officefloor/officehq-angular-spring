package net.officefloor.hq.app.pool;

/** A pool, or a member of one, was refused: nothing was written. */
public class PoolRefused extends RuntimeException {

    public PoolRefused(String message) {
        super(message);
    }
}
