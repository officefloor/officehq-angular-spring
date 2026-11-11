package net.officefloor.hq.app.person;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.crew.CrewService;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.pool.PoolMember;
import net.officefloor.hq.app.pool.PoolMemberRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.statement.StatementRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Merging a person into another on the sheet (a steward only). The merged person's claims move to
 * the other, keeping their numbers and dates, listed after the other's in the order they were added,
 * each keeping any figure a statement bound it to; their statements go to the other, listed before the
 * other's own (so the other's last statement stays theirs, or, having none, becomes the merged
 * person's); their name and aliases become aliases of the other; their patrons
 * are added to the other's (not the other themselves, no repeats), anyone naming them as patron names
 * the other instead (no repeats), and their pool memberships move to the other (shares added when the
 * other is already a member), as do their crew memberships and the crews they lead (dropped where the
 * other is already in the crew or leads it). The figures fixed for them in closed or soft-closed periods,
 * and their opening balances, are added to the other's. Their holds, watchers and contact are dropped, and they leave the sheet.
 *
 * <p>The steward who made the last merge into a person can undo it on the same sheet-today: the
 * merged person is back, under their own id, at the end of the list, as they stood when merged, with
 * the claims that came from them (as those are now) and their own aliases, which leave the other's.
 * The patron, pool, crew, share, statement and period changes the merge made stay.
 */
@Service
public class PersonMergeService {

    private final PersonRepository people;
    private final PatronRepository patrons;
    private final WatchRepository watches;
    private final ClaimRepository claims;
    private final StatementRepository statements;
    private final PoolMemberRepository poolMembers;
    private final CrewService crews;
    private final PeriodRepository periods;
    private final SheetService sheets;
    private final RoleService roles;
    private final RollupService rollup;
    private final Audit audit;
    private final JdbcTemplate jdbc;

    /** The merged person's own tables, kept with the person row to be put back by an undo. */
    private static final List<String> PERSON_TABLES = List.of("PERSON_GRADE", "PERSON_TERMS", "PERSON_HOLD",
            "PERSON_ALIAS", "PERSON_RETIREMENT", "PERSON_SNAPSHOT");

    public PersonMergeService(PersonRepository people, PatronRepository patrons, WatchRepository watches,
            ClaimRepository claims, StatementRepository statements, PoolMemberRepository poolMembers,
            CrewService crews, PeriodRepository periods, SheetService sheets, RoleService roles, RollupService rollup,
            Audit audit, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.periods = periods;
        this.people = people;
        this.patrons = patrons;
        this.watches = watches;
        this.claims = claims;
        this.statements = statements;
        this.poolMembers = poolMembers;
        this.crews = crews;
        this.sheets = sheets;
        this.roles = roles;
        this.rollup = rollup;
        this.audit = audit;
    }

    /**
     * Merge the person into another. Refused (403) to anyone but a steward, (409) for a merge into the
     * person themselves or when either of them is retired.
     */
    @Transactional
    public Person merge(String sheetId, String user, long id, long intoId) {
        roles.refuseViewer(sheetId, user);
        roles.refuseUnlessSteward(sheetId, user);
        sheets.lock(sheetId);
        Person from = person(sheetId, id);
        Person into = person(sheetId, intoId);
        if (from.getId().equals(into.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A person cannot be merged into themselves.");
        }
        if (from.isRetired() || into.isRetired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A retired person cannot be merged.");
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String fromName = from.getName();
        List<String> names = new ArrayList<>(List.of(fromName));
        names.addAll(from.getAliases());
        byte[] kept = keep(from.getId());

        // the claims, oldest listed first, each listed first in turn: after the other's, in their order
        List<Claim> moved = claims.findBySheetIdAndPersonIdOrderByListedAsc(sheetId, from.getId());
        for (Claim claim : moved) {
            claim.setPersonId(into.getId());
        }
        claims.saveAllAndFlush(moved);
        for (Claim claim : moved) {
            claims.listFirst(claim.getId());
        }

        // shares the merged person held go to the other
        List<Claim> shared = claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .filter(c -> c.getShares().stream().anyMatch(sh -> sh.getHolderId().equals(from.getId())
                        || sh.getHolderId().equals(c.getPersonId())))
                .toList();
        shared.forEach(c -> c.mergeHolder(from.getId(), into.getId()));
        claims.saveAllAndFlush(shared);

        // patrons: the merged person's go to the other, and whoever named them names the other
        List<Patron> links = patrons.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(l -> l.getPersonId().equals(from.getId()) || l.getPatronId().equals(from.getId())).toList();
        patrons.deleteAll(links);
        patrons.flush();
        for (Patron link : links) {
            long personId = link.getPersonId().equals(from.getId()) ? into.getId() : link.getPersonId();
            long patronId = link.getPatronId().equals(from.getId()) ? into.getId() : link.getPatronId();
            if (personId != patronId && !patrons.existsByPersonIdAndPatronId(personId, patronId)) {
                patrons.saveAndFlush(new Patron(sheetId, personId, patronId));
            }
        }

        // pool memberships move, adding the share where the other is already a member
        for (PoolMember member : poolMembers.findByPersonIdOrderByIdAsc(from.getId())) {
            var existing = poolMembers.findByPoolIdAndPersonId(member.getPoolId(), into.getId());
            if (existing.isPresent()) {
                existing.get().addShare(member.getShare());
                poolMembers.save(existing.get());
                poolMembers.delete(member);
            } else {
                member.setPersonId(into.getId());
                poolMembers.save(member);
            }
        }
        poolMembers.flush();
        crews.merge(from.getId(), into.getId());

        // the figures fixed for them in closed periods, and their opening balances, go to the other
        List<Period> theirPeriods = periods.findBySheetIdOrderByFromDateAsc(sheetId);
        theirPeriods.forEach(p -> p.mergePerson(from.getId(), into.getId()));
        periods.saveAllAndFlush(theirPeriods);

        statements.moveStatements(sheetId, from.getId(), sheetId, into.getId());
        watches.deleteAll(watches.findByPersonIdOrderByIdAsc(from.getId()));
        watches.flush();

        // listing the claims first cleared what was read: read both people again
        people.delete(person(sheetId, from.getId()));
        people.flush();
        Person merged = person(sheetId, into.getId());
        names.forEach(merged::addAlias);
        Person saved = people.saveAndFlush(merged);
        jdbc.update("INSERT INTO person_merge (sheet_id, from_id, into_id, merged_by, merged_on, claim_ids, data)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)", sheetId, from.getId(), into.getId(), user, sheets.today(sheetId),
                moved.stream().map(c -> String.valueOf(c.getId())).collect(Collectors.joining(",")), kept);

        audit.recordOnCommit("PERSON_MERGED sheet=" + sheetId + " by=" + user + " from=" + fromName + " into="
                + saved.getName());
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /** The last merge into a person not yet undone. */
    private record Merge(long id, long fromId, String mergedBy, LocalDate mergedOn, List<Long> claimIds, byte[] data) {
    }

    /**
     * Undo the last merge into the person. Refused (403) to anyone but a steward, and to anyone but
     * the one who made it; (409) when the person has no merge to undo, the sheet's today has moved
     * since it was made, or the merged person's name or an alias of theirs is now taken on the sheet.
     * Answers the person put back.
     */
    @Transactional
    public Person undo(String sheetId, String user, long intoId) {
        roles.refuseViewer(sheetId, user);
        roles.refuseUnlessSteward(sheetId, user);
        sheets.lock(sheetId);
        Person into = person(sheetId, intoId);
        Merge merge = jdbc.query("SELECT id, from_id, merged_by, merged_on, claim_ids, data FROM person_merge"
                + " WHERE sheet_id = ? AND into_id = ? ORDER BY id DESC LIMIT 1",
                (rs, n) -> new Merge(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getObject(4, LocalDate.class), ids(rs.getString(5)), rs.getBytes(6)),
                sheetId, into.getId()).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "There is no merge into " + into.getName() + " to undo."));
        if (!merge.mergedBy().equals(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the steward who made the merge can undo it.");
        }
        if (!merge.mergedOn().equals(sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A merge can only be undone on the day it was made.");
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Map<String, List<List<Object>>> data = read(merge.data());
        Map<String, Object> row = columns(data.get("PERSON")).get(0);
        String name = (String) row.get("NAME");
        List<String> aliases = columns(data.get("PERSON_ALIAS")).stream().map(a -> (String) a.get("ALIAS")).toList();

        // their name and aliases leave the other's aliases, and must then be free on the sheet
        Set<String> leaving = new java.util.HashSet<>();
        leaving.add(name.toLowerCase(Locale.ROOT));
        aliases.forEach(a -> leaving.add(a.toLowerCase(Locale.ROOT)));
        into.removeAliases(leaving);
        people.saveAndFlush(into);
        List<String> names = new ArrayList<>(List.of(name));
        names.addAll(aliases);
        for (String n : names) {
            if (people.existsBySheetIdAndNameIgnoreCase(sheetId, n) || people.existsAliasOnSheet(sheetId, n)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "The name " + n + " is now taken on the sheet.");
            }
        }

        // the person as they stood, at the end of the list; a contact since taken stays dropped
        row.put("POSITION", people.maxPosition(sheetId) + 1);
        Object contact = row.get("CONTACT");
        if (contact != null && people.existsBySheetIdAndContact(sheetId, (String) contact)) {
            row.put("CONTACT", null);
        }
        insert("PERSON", List.of(row));
        for (String table : PERSON_TABLES) {
            insert(table, columns(data.get(table)));
        }

        // the claims that came from them, as they are now, go back to them
        List<Claim> back = claims.findAllById(merge.claimIds()).stream()
                .filter(c -> c.getSheetId().equals(sheetId) && c.getPersonId().equals(into.getId())).toList();
        back.forEach(c -> c.setPersonId(merge.fromId()));
        claims.saveAllAndFlush(back);
        jdbc.update("DELETE FROM person_merge WHERE id = ?", merge.id());

        Person restored = person(sheetId, merge.fromId());
        audit.recordOnCommit("PERSON_MERGE_UNDONE sheet=" + sheetId + " by=" + user + " name=" + restored.getName()
                + " from=" + into.getName());
        rollup.recordChanges(sheetId, user, before);
        return restored;
    }

    /** The person's row and the rows of their own tables as they stand, each table's first row its column names. */
    private byte[] keep(long personId) {
        people.flush();
        LinkedHashMap<String, List<List<Object>>> data = new LinkedHashMap<>();
        data.put("PERSON", rows("PERSON", "id", personId));
        for (String table : PERSON_TABLES) {
            data.put(table, rows(table, "person_id", personId));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** A table's stored (not generated) columns of the rows naming the person, the first row the column names. */
    private List<List<Object>> rows(String table, String key, long personId) {
        List<String> columns = jdbc.queryForList("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS"
                + " WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ? AND IS_GENERATED = 'NEVER' ORDER BY ORDINAL_POSITION",
                String.class, table);
        return jdbc.query("SELECT " + String.join(", ", columns) + " FROM " + table + " WHERE " + key + " = ?",
                (ResultSet rs) -> {
                    ResultSetMetaData meta = rs.getMetaData();
                    List<List<Object>> rows = new ArrayList<>();
                    List<Object> names = new ArrayList<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        names.add(meta.getColumnName(i));
                    }
                    rows.add(names);
                    while (rs.next()) {
                        List<Object> r = new ArrayList<>();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            // a large object is kept as its bytes or text, to be serialized
                            int type = meta.getColumnType(i);
                            r.add(type == java.sql.Types.BLOB ? rs.getBytes(i)
                                    : type == java.sql.Types.CLOB ? rs.getString(i) : rs.getObject(i));
                        }
                        rows.add(r);
                    }
                    return rows;
                }, personId);
    }

    /** Kept rows as column-to-value maps, in column order; none when none were kept. */
    private static List<Map<String, Object>> columns(List<List<Object>> rows) {
        List<Map<String, Object>> found = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return found;
        }
        List<Object> names = rows.get(0);
        for (List<Object> r : rows.subList(1, rows.size())) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < names.size(); i++) {
                m.put(String.valueOf(names.get(i)), r.get(i));
            }
            found.add(m);
        }
        return found;
    }

    private void insert(String table, List<Map<String, Object>> rows) {
        for (Map<String, Object> r : rows) {
            jdbc.update("INSERT INTO " + table + " (" + String.join(", ", r.keySet()) + ") VALUES ("
                    + r.keySet().stream().map(c -> "?").collect(Collectors.joining(", ")) + ")", r.values().toArray());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<List<Object>>> read(byte[] data) {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return (Map<String, List<List<Object>>>) in.readObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Long> ids(String text) {
        return text.isEmpty() ? List.of() : Arrays.stream(text.split(",")).map(Long::valueOf).toList();
    }

    private Person person(String sheetId, long id) {
        return people.lockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
    }
}
