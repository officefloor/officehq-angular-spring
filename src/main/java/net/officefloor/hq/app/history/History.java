package net.officefloor.hq.app.history;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.sheet.Sheet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The versions of a sheet, its people, their claims and its rates: each change keeps the state it left the
 * row in, with the sheet's today when it was made, so the rows can be read back as of a date — as the
 * changes made while the sheet's today was on or before it left them, each row's latest such change by the
 * order they were made, a change made later not counting whatever its today.
 * Kept by the entities' listener once the change is written, in the same transaction.
 */
@Component
public class History {

    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultVisibility(v -> v.withVisibility(PropertyAccessor.ALL, Visibility.NONE)
                    .withVisibility(PropertyAccessor.FIELD, Visibility.ANY))
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public History(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostPersist
    @PostUpdate
    public void saved(Object entity) {
        keep(entity, json.writeValueAsString(entity));
    }

    @PostRemove
    public void removed(Object entity) {
        keep(entity, null);
    }

    /** Keep the sheet's state now: for a change Hibernate does not see as an update of the sheet itself
     *  (its dated settings). */
    public void keep(Sheet sheet) {
        saved(sheet);
    }

    private void keep(Object entity, String state) {
        String sheetId;
        String entityId;
        if (entity instanceof Sheet sheet) {
            sheetId = sheet.getId();
            entityId = sheet.getId();
        } else if (entity instanceof Claim claim) {
            sheetId = claim.getSheetId();
            entityId = String.valueOf(claim.getId());
        } else if (entity instanceof Person person) {
            sheetId = person.getSheetId();
            entityId = String.valueOf(person.getId());
        } else if (entity instanceof Rate rate) {
            sheetId = rate.getSheetId();
            entityId = String.valueOf(rate.getId());
        } else {
            throw new IllegalArgumentException("No history kept of " + entity.getClass());
        }
        LocalDate today = entity instanceof Sheet sheet ? sheet.getToday()
                : jdbc.queryForList("SELECT today FROM sheet WHERE id = ?", Date.class, sheetId).stream()
                        .filter(Objects::nonNull).findFirst().map(Date::toLocalDate).orElse(null);
        if (today == null) {
            return;
        }
        jdbc.update("INSERT INTO entity_version (sheet_id, kind, entity_id, recorded_on, state) VALUES (?, ?, ?, ?, ?)",
                sheetId, entity.getClass().getSimpleName(), entityId, Date.valueOf(today), state);
    }

    /** The sheet's rows of a kind as of a date, by id in the order first kept: each one's latest version made
     *  while the sheet's today was on or before it; none for a row removed then or not yet made. */
    public <T> Map<String, T> asOf(Class<T> type, String sheetId, LocalDate date) {
        Map<String, String> latest = new LinkedHashMap<>();
        jdbc.query("SELECT entity_id, state FROM entity_version WHERE sheet_id = ? AND kind = ? AND recorded_on <= ?"
                + " ORDER BY id", rs -> {
                    latest.put(rs.getString(1), rs.getString(2));
                }, sheetId, type.getSimpleName(), Date.valueOf(date));
        Map<String, T> rows = new LinkedHashMap<>();
        latest.forEach((id, state) -> {
            if (state != null) {
                rows.put(id, json.readValue(state, type));
            }
        });
        return rows;
    }

    /** The sheet's rows of a kind as of a date, in the order first kept. */
    public <T> List<T> listAsOf(Class<T> type, String sheetId, LocalDate date) {
        return List.copyOf(asOf(type, sheetId, date).values());
    }

    /** A row as of a date; none when it was not made by then, or removed. */
    public <T> Optional<T> findAsOf(Class<T> type, String sheetId, Object id, LocalDate date) {
        return Optional.ofNullable(asOf(type, sheetId, date).get(String.valueOf(id)));
    }
}
