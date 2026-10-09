package net.officefloor.hq.app.contact;

/** A contact; {@code primary} marks the client's main contact. */
public record ContactResponse(Long id, Long clientId, String name, String email, String role, boolean primary) {

    static ContactResponse from(Contact contact) {
        Contact primary = contact.getClient().getPrimaryContact();
        return new ContactResponse(contact.getId(), contact.getClient().getId(), contact.getName(),
                contact.getEmail(), contact.getRole(), primary != null && primary.getId().equals(contact.getId()));
    }
}
