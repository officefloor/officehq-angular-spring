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
import net.officefloor.hq.app.claim.ClaimShare;
import net.officefloor.hq.app.crew.SheetCrew;
import net.officefloor.hq.app.crew.SheetCrewMember;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonPatron;
import net.officefloor.hq.app.person.PersonWatch;
import net.officefloor.hq.app.pool.SheetPool;
import net.officefloor.hq.app.pool.SheetPoolMember;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.settings.SheetDelegation;
import net.officefloor.hq.app.settings.SheetPermission;
import net.officefloor.hq.app.settings.SheetRole;
import net.officefloor.hq.app.settings.SheetSetting;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.statement.Statement;
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
 * sheet's screen is made of — the sheet, its people, claims and their shares, rates, dated settings, periods, statements,
 * patrons, watches, roles, permissions, delegations, pools and their members, and crews and their members — as it stands
 * after each change, with the sheet's today then. The line is written on the change's own connection,
 * so it commits or rolls back with it.
 */
@Component
public class HistoryListener implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener,
        PostCollectionRecreateEventListener, PostCollectionUpdateEventListener {

    /** The entities kept, by the name their lines carry. */
    static final Map<Class<?>, String> KEPT = Map.ofEntries(Map.entry(Sheet.class, "sheet"),
            Map.entry(Person.class, "person"), Map.entry(Claim.class, "claim"), Map.entry(ClaimShare.class, "claim-share"),
            Map.entry(Rate.class, "rate"),
            Map.entry(SheetSetting.class, "setting"), Map.entry(Period.class, "period"),
            Map.entry(Statement.class, "statement"), Map.entry(PersonPatron.class, "patron"),
            Map.entry(PersonWatch.class, "watch"), Map.entry(SheetRole.class, "role"),
            Map.entry(SheetPermission.class, "permission"), Map.entry(SheetDelegation.class, "delegation"),
            Map.entry(SheetPool.class, "pool"), Map.entry(SheetPoolMember.class, "pool-member"),
            Map.entry(SheetCrew.class, "crew"), Map.entry(SheetCrewMember.class, "crew-member"));

    /** The sheet's today the lines written on this thread are made on instead of its today now; null when
     * they are made on its today now (see {@link #madeOn}). */
    private static final ThreadLocal<LocalDate> MADE_ON = new ThreadLocal<>();

    /** Run {@code change} with every line it writes made on {@code date}: the changes a move of the sheet's
     * today brings about (its automatic period closes) are made while its today is still the day being left. */
    public static void madeOn(LocalDate date, Runnable change) {
        LocalDate was = MADE_ON.get();
        MADE_ON.set(date);
        try {
            change.run();
        } finally {
            if (was == null) MADE_ON.remove();
            else MADE_ON.set(was);
        }
    }

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
                : entity instanceof ClaimShare share ? share.getSheetId()
                : entity instanceof Rate rate ? rate.getSheetId()
                : entity instanceof SheetSetting setting ? setting.getSheetId()
                : entity instanceof Period period ? period.getSheetId()
                : entity instanceof Statement statement ? statement.getSheetId()
                : entity instanceof PersonPatron patron ? patron.getSheetId()
                : entity instanceof PersonWatch watch ? watch.getSheetId()
                : entity instanceof SheetRole role ? role.getSheetId()
                : entity instanceof SheetPermission permission ? permission.getSheetId()
                : entity instanceof SheetDelegation delegation ? delegation.getSheetId()
                : entity instanceof SheetPool pool ? pool.getSheetId()
                : entity instanceof SheetCrew crew ? crew.getSheetId()
                : null;
        Connection connection = session.getJdbcCoordinator().getLogicalConnection().getPhysicalConnection();
        try {
            if (entity instanceof SheetPoolMember member) {
                sheetId = sheetOf(connection, "sheet_pool", member.getPoolId());
            } else if (entity instanceof SheetCrewMember member) {
                sheetId = sheetOf(connection, "sheet_crew", member.getCrewId());
            }
            if (sheetId == null) return;
            LocalDate madeOn = MADE_ON.get() != null ? MADE_ON.get()
                    : entity instanceof Sheet sheet ? sheet.getToday() : todayOf(connection, sheetId);
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

    /** The sheet of the pool or crew (by its table) with the id; null when there is none. */
    private static String sheetOf(Connection connection, String table, Long id) throws SQLException {
        if (id == null) return null;
        try (PreparedStatement select = connection.prepareStatement("SELECT sheet_id FROM " + table + " WHERE id = ?")) {
            select.setLong(1, id);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
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
