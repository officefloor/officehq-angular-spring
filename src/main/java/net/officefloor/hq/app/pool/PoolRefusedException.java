package net.officefloor.hq.app.pool;

/** A pool or a pool member that cannot be added: a blank or used name, a person already in the
 *  pool, or a share that is not more than 0.00 and at most 100.00 with two decimals. */
public class PoolRefusedException extends RuntimeException {

    public PoolRefusedException(String message) {
        super(message);
    }
}
