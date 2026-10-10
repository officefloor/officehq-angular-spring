package net.officefloor.hq.app.statementemail;

import java.time.Instant;

/** A note that a client's statement was emailed. */
public record StatementEmailResponse(Long id, Long clientId, String email, Instant sentAt) {

    static StatementEmailResponse from(StatementEmail sent) {
        return new StatementEmailResponse(sent.getId(), sent.getClientId(), sent.getEmail(), sent.getSentAt());
    }
}
