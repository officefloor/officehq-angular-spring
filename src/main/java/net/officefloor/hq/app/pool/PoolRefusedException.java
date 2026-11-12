package net.officefloor.hq.app.pool;

/** A pool that cannot be added (a blank name, or one already taken), or a member that cannot be added to
 *  one (a person already in it, or a share that is not more than 0.00 and at most 100.00 in two decimals). */
public class PoolRefusedException extends RuntimeException {

    public PoolRefusedException(String message) {
        super(message);
    }
}
