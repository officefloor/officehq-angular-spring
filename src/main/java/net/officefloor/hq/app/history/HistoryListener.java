package net.officefloor.hq.app.history;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import org.springframework.beans.factory.ObjectProvider;

/** Writes a version of a claim, person, rate or sheet to the sheet's history each time a change
 *  stores it (a sheet's dated settings, kept apart from its row, are written by the change itself). */
public class HistoryListener {

    private final ObjectProvider<HistoryService> history;

    public HistoryListener(ObjectProvider<HistoryService> history) {
        this.history = history;
    }

    @PostPersist
    @PostUpdate
    void stored(Object entity) {
        history.getObject().record(entity);
    }
}
