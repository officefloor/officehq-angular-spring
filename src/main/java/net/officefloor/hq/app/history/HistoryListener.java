package net.officefloor.hq.app.history;

import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.settings.SheetSetting;
import net.officefloor.hq.app.sheet.Sheet;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostCollectionRecreateEvent;
import org.hibernate.event.spi.PostCollectionRecreateEventListener;
import org.hibernate.event.spi.PostCollectionUpdateEvent;
import org.hibernate.event.spi.PostCollectionUpdateEventListener;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.stereotype.Component;

/**
 * Writes a line to the sheet's history (see {@code V51__sheet_history.sql}) for every row of what a
 * sheet's figures are made of — the sheet, its people, claims, rates and dated settings — as it stands
 * after each change, with the sheet's today then. The line is written on the change's own connection,
 * so it commits or rolls back with it.
 */
@Component
public class HistoryListener implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener,
        PostCollectionRecreateEventListener, PostCollectionUpdateEventListener {

    /** The entities kept, by the name their lines carry. */
    static final Map<Class<?>, String> KEPT = Map.of(Sheet.class, "sheet", Person.class, "person",
            Claim.class, "claim", Rate.class, "rate", SheetSetting.class, "setting");

    public HistoryListener(EntityManagerFactory entityManagerFactory) {
        EventListenerRegistry registry = entityManagerFactory.unwrap(SessionFactoryImplementor.class)
                .getServiceRegistry().getService(EventListenerRegistry.class);
        registry.appendListeners(EventType.POST_INSERT, this);
        registry.appendListeners(EventType.POST_UPDATE, this);
        registry.appendListeners(EventType.POST_DELETE, this);
        registry.appendListeners(EventType.POST_COLLECTION_RECREATE, this);
        registry.appendListeners(EventType.POST_COLLECTION_UPDATE, this);
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        keep(event.getSession(), event.getEntity(), event.getId(), false);
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        keep(event.getSession(), event.getEntity(), event.getId(), false);
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        keep(event.getSession(), event.getEntity(), event.getId(), true);
    }

    @Override
    public void onPostRecreateCollection(PostCollectionRecreateEvent event) {
        keep(event.getSession(), event.getAffectedOwnerOrNull(), event.getAffectedOwnerIdOrNull(), false);
    }

    @Override
    public void onPostUpdateCollection(PostCollectionUpdateEvent event) {
        keep(event.getSession(), event.getAffectedOwnerOrNull(), event.getAffectedOwnerIdOrNull(), false);
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }

    private void keep(SharedSessionContractImplementor session, Object entity, Object id, boolean removed) {
        if (entity == null || id == null) return;
        String name = KEPT.get(entity.getClass());
        if (name == null) return;
        String sheetId = entity instanceof Sheet sheet ? sheet.getId()
                : entity instanceof Person person ? person.getSheetId()
                : entity instanceof Claim claim ? claim.getSheetId()
                : entity instanceof Rate rate ? rate.getSheetId()
                : entity instanceof SheetSetting setting ? setting.getSheetId()
                : null;
        if (sheetId == null) return;
        Connection connection = session.getJdbcCoordinator().getLogicalConnection().getPhysicalConnection();
        try {
            LocalDate madeOn = entity instanceof Sheet sheet ? sheet.getToday() : todayOf(connection, sheetId);
            if (madeOn == null) return;
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO sheet_history (sheet_id, made_on, entity, entity_id, state) VALUES (?, ?, ?, ?, ?)")) {
                insert.setString(1, sheetId);
                insert.setDate(2, Date.valueOf(madeOn));
                insert.setString(3, name);
                insert.setString(4, id.toString());
                insert.setString(5, removed ? null : HistoryJson.MAPPER.writeValueAsString(entity));
                insert.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("The sheet's history could not be written", e);
        }
    }

    private static LocalDate todayOf(Connection connection, String sheetId) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT today FROM sheet WHERE id = ?")) {
            select.setString(1, sheetId);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? rows.getDate(1).toLocalDate() : null;
            }
        }
    }
}
