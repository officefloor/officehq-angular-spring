package net.officefloor.hq.app.contact;

public record ContactResponse(Long id, Long clientId, String name, String email, String role) {

    static ContactResponse from(Contact contact) {
        return new ContactResponse(contact.getId(), contact.getClient().getId(), contact.getName(),
                contact.getEmail(), contact.getRole());
    }
}
