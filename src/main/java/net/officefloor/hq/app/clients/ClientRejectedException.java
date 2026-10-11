package net.officefloor.hq.app.clients;

/** A client that cannot be saved; the message says why, in words the user can act on. */
public class ClientRejectedException extends RuntimeException {

    public ClientRejectedException(String message) {
        super(message);
    }
}
