package net.officefloor.hq.app.contacthistory;

import java.time.LocalDate;

/** One entry in a client's contact history. */
public record ContactHistoryResponse(Long id, Long clientId, LocalDate date, String note) {

    static ContactHistoryResponse from(ContactHistoryEntry entry) {
        return new ContactHistoryResponse(entry.getId(), entry.getClientId(), entry.getDate(), entry.getNote());
    }
}
