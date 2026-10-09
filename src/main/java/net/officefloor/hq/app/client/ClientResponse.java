package net.officefloor.hq.app.client;

import net.officefloor.hq.app.contact.Contact;

public record ClientResponse(Long id, String name, String email, boolean archived, PrimaryContact primaryContact) {

    /** Who the client's main contact is; null when none has been chosen. */
    public record PrimaryContact(Long id, String name) {
    }

    public static ClientResponse from(Client client) {
        Contact primary = client.getPrimaryContact();
        return new ClientResponse(client.getId(), client.getName(), client.getEmail(), client.isArchived(),
                primary == null ? null : new PrimaryContact(primary.getId(), primary.getName()));
    }
}
