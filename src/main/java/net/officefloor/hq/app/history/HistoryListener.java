package net.officefloor.hq.app.history;

import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.springframework.beans.factory.ObjectProvider;

/** Writes a version of an entity of a sheet to the sheet's history each time a change stores or
 *  removes it (a sheet's dated settings, kept apart from its row, are written by the change itself). */
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

    @PostRemove
    void removed(Object entity) {
        history.getObject().removed(entity);
    }
}
