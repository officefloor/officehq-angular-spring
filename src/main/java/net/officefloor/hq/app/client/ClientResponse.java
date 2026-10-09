package net.officefloor.hq.app.client;

public record ClientResponse(Long id, String name, String email) {

    static ClientResponse from(Client client) {
        return new ClientResponse(client.getId(), client.getName(), client.getEmail());
    }
}
