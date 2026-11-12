package net.officefloor.hq.app.person;

/** A transfer of a person to another sheet that is not allowed: by anyone but a steward, of a retired person,
 *  to this sheet itself or a sheet that cannot take them, or when their name, an alias or their contact is
 *  already on the other sheet. */
public class TransferRefusedException extends RuntimeException {

    public TransferRefusedException(String message) {
        super(message);
    }
}
