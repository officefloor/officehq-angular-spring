package net.officefloor.hq.app.pool;

/** A pool that cannot be added (a blank name, or one already taken), or a member that cannot be added to
 *  one (a person already in it, a share that is not more than 0.00 and at most 100.00 in two decimals, or a
 *  pool that would contain itself, is already in a pool, or would make a chain of pools more than three deep). */
public class PoolRefusedException extends RuntimeException {

    public PoolRefusedException(String message) {
        super(message);
    }
}
