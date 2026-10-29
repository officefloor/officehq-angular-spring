package net.officefloor.hq.app.person;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

/**
 * The merges made on each sheet, each keeping the merged person as they stood before it, so the merge can be undone:
 * the person brought back with the same id, their grades, holds, terms, past retirements and own aliases.
 */
@Component
public class PersonMergeRecords {

    private static final String PERSON_COLUMNS = "id, sheet_id, name, version, retired_from, terms, added_on,"
            + " retired_on, contact, origin_sheet, tags, arrived_on, arrived_by, flag_reason, flag_until, approver";

    /** A merge made: of the person into the other, by the user while the sheet's today was the date. */
    public record Merge(long id, Long fromId, Long intoId, String madeBy, LocalDate madeOn, boolean undone) {
    }

    private final JdbcTemplate jdbc;

    public PersonMergeRecords(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * Keeps the person, as the store holds them now, and the claims of theirs, for a merge into the other being made
     * by the user while the sheet's today is the date. The id of the merge.
     */
    long keep(String sheetId, Long fromId, Long intoId, String by, LocalDate madeOn) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(c -> {
            PreparedStatement s = c.prepareStatement("INSERT INTO person_merge (sheet_id, from_id, into_id, made_by,"
                    + " made_on) VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            s.setString(1, sheetId);
            s.setLong(2, fromId);
            s.setLong(3, intoId);
            s.setString(4, by);
            s.setObject(5, madeOn);
            return s;
        }, key);
        long id = ((Number) key.getKeys().get("ID")).longValue();
        jdbc.update("INSERT INTO person_merged (merge_id, " + PERSON_COLUMNS + ") SELECT ?, " + PERSON_COLUMNS
                + " FROM person WHERE id = ?", id, fromId);
        jdbc.update("INSERT INTO person_merged_grade (merge_id, grade, from_date, made_on)"
                + " SELECT ?, grade, from_date, made_on FROM person_grade WHERE person_id = ?", id, fromId);
        jdbc.update("INSERT INTO person_merged_hold (merge_id, from_date, to_date, made_on)"
                + " SELECT ?, from_date, to_date, made_on FROM person_hold WHERE person_id = ?", id, fromId);
        jdbc.update("INSERT INTO person_merged_terms (merge_id, from_date, terms, made_on)"
                + " SELECT ?, from_date, terms, made_on FROM person_terms WHERE person_id = ?", id, fromId);
        jdbc.update("INSERT INTO person_merged_retirement (merge_id, from_date, to_date, retired_on, made_on)"
                + " SELECT ?, from_date, to_date, retired_on, made_on FROM person_retirement WHERE person_id = ?",
                id, fromId);
        jdbc.update("INSERT INTO person_merged_alias (merge_id, alias)"
                + " SELECT ?, alias FROM person_alias WHERE person_id = ?", id, fromId);
        jdbc.update("INSERT INTO person_merge_claim (merge_id, claim_id)"
                + " SELECT ?, id FROM claim WHERE sheet_id = ? AND person_id = ?", id, sheetId, fromId);
        return id;
    }

    /** Keeps the aliases the merge gave the person merged into. */
    void keepGiven(long mergeId, Collection<String> aliases) {
        for (String a : aliases) {
            jdbc.update("INSERT INTO person_merge_given_alias (merge_id, alias) VALUES (?, ?)", mergeId, a);
        }
    }

    /** The last merge into the person on the sheet, undone or not; empty when none was made. */
    Optional<Merge> lastInto(String sheetId, Long intoId) {
        return jdbc.query("SELECT id, from_id, into_id, made_by, made_on, undone FROM person_merge"
                + " WHERE sheet_id = ? AND into_id = ? ORDER BY id DESC LIMIT 1",
                (r, n) -> new Merge(r.getLong(1), r.getLong(2), r.getLong(3), r.getString(4),
                        r.getObject(5, LocalDate.class), r.getBoolean(6)), sheetId, intoId).stream().findFirst();
    }

    /** The merged person's name, as kept. */
    String name(long mergeId) {
        return jdbc.queryForObject("SELECT name FROM person_merged WHERE merge_id = ?", String.class, mergeId);
    }

    /** The merged person's contact, as kept; null for none. */
    String contact(long mergeId) {
        return jdbc.queryForObject("SELECT contact FROM person_merged WHERE merge_id = ?", String.class, mergeId);
    }

    /** The merged person's own aliases, as kept. */
    List<String> aliases(long mergeId) {
        return jdbc.queryForList("SELECT alias FROM person_merged_alias WHERE merge_id = ?", String.class, mergeId);
    }

    /** The aliases the merge gave the person merged into. */
    List<String> given(long mergeId) {
        return jdbc.queryForList("SELECT alias FROM person_merge_given_alias WHERE merge_id = ?", String.class,
                mergeId);
    }

    /** The ids of the claims that came from the merged person. */
    List<Long> claims(long mergeId) {
        return jdbc.queryForList("SELECT claim_id FROM person_merge_claim WHERE merge_id = ?", Long.class, mergeId);
    }

    /**
     * Brings the merged person back to the store as kept, at the position in the list, with the contact (null for
     * none), and marks the merge undone.
     */
    void restore(long mergeId, long position, String contact) {
        jdbc.update("INSERT INTO person (" + PERSON_COLUMNS + ", list_position) SELECT "
                + PERSON_COLUMNS.replace("contact", "?") + ", ? FROM person_merged WHERE merge_id = ?", contact,
                position, mergeId);
        Long id = jdbc.queryForObject("SELECT id FROM person_merged WHERE merge_id = ?", Long.class, mergeId);
        String sheetId = jdbc.queryForObject("SELECT sheet_id FROM person_merged WHERE merge_id = ?", String.class,
                mergeId);
        jdbc.update("INSERT INTO person_grade (person_id, grade, from_date, made_on)"
                + " SELECT ?, grade, from_date, made_on FROM person_merged_grade WHERE merge_id = ?", id, mergeId);
        jdbc.update("INSERT INTO person_hold (person_id, from_date, to_date, made_on)"
                + " SELECT ?, from_date, to_date, made_on FROM person_merged_hold WHERE merge_id = ?", id, mergeId);
        jdbc.update("INSERT INTO person_terms (person_id, from_date, terms, made_on)"
                + " SELECT ?, from_date, terms, made_on FROM person_merged_terms WHERE merge_id = ?", id, mergeId);
        jdbc.update("INSERT INTO person_retirement (person_id, from_date, to_date, retired_on, made_on)"
                + " SELECT ?, from_date, to_date, retired_on, made_on FROM person_merged_retirement WHERE merge_id = ?",
                id, mergeId);
        jdbc.update("INSERT INTO person_alias (person_id, sheet_id, alias)"
                + " SELECT ?, ?, alias FROM person_merged_alias WHERE merge_id = ?", id, sheetId, mergeId);
        jdbc.update("UPDATE person_merge SET undone = TRUE WHERE id = ?", mergeId);
    }
}
