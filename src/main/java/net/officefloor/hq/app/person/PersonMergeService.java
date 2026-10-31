package net.officefloor.hq.app.person;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.access.AccessService;
import net.officefloor.hq.app.access.Role;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.crew.Crews;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.pool.PoolMember;
import net.officefloor.hq.app.pool.PoolMemberRepository;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.statement.StatementService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Merging a person into another on the same sheet. Only a steward may; merging a person into themselves, or
 * when either of them is retired, is refused. The merged person's claims move to the other (numbers and dates
 * kept, listed after the other's claims in the order they were added, each keeping any figure a statement
 * bound); their figures fixed for closed or soft-closed periods (and their opening balances) are added to the other's; their statements are carried to the other, who keeps their own last statement (or, having none,
 * takes the merged person's last) with the count of both; their name and aliases become the other's aliases; their patrons are added to the
 * other's (never the other themselves, no repeats); anyone naming them as patron names the other instead
 * (no repeats); their pool memberships move to the other, the shares added up (at most 100.00) where the
 * other is already a member. Their holds, watchers and contact are dropped with them.
 * <p>
 * The steward who made the last merge into a person may undo it on the same sheet's today: the merged person is
 * back (with their id) at the end of the list, as they were (name, grades, terms, contact while no one else has
 * taken it, tags, holds, flag and approver), with the claims that came from them (numbers, statuses and figures
 * as they are now) and their own aliases, which leave the other's (as does their name). Patron, pool, crew,
 * statement and period changes stay. Anyone else, any later day, or a person with no merge to undo is refused.
 */
@Service
public class PersonMergeService {

    private static final BigDecimal FULL_SHARE = new BigDecimal("100.00");

    /** The merged person as they were, kept with the merge so it can be undone. */
    public record Merged(String name, List<Grade> grades, Integer terms, List<Terms> datedTerms, String contact,
            String tags, String origin, LocalDate arrivedOn, String flagReason, LocalDate flagUntil, String approver,
            List<String> aliases, List<Span> holds, List<Span> retirements) {

        static Merged of(Person p) {
            return new Merged(p.getName(),
                    p.getGrades().stream().map(g -> new Grade(g.getGrade(), g.getFromDate())).toList(),
                    p.getTerms(),
                    p.getDatedTerms().stream().map(t -> new Terms(t.getTerms(), t.getFromDate())).toList(),
                    p.getContact(), p.getTags(), p.getOrigin(), p.getArrivedOn(), p.getFlagReason(), p.getFlagUntil(),
                    p.getApprover(), p.getAliases(),
                    p.getHolds().stream().map(h -> new Span(h.getFromDate(), h.getToDate())).toList(),
                    p.getRetirements().stream().map(r -> new Span(r.getFromDate(), r.getToDate())).toList());
        }
    }

    public record Grade(String grade, LocalDate fromDate) {
    }

    public record Terms(Integer terms, LocalDate fromDate) {
    }

    public record Span(LocalDate fromDate, LocalDate toDate) {
    }

    private final PersonRepository people;
    private final PatronRepository patrons;
    private final WatchRepository watches;
    private final ClaimRepository claims;
    private final StatementService statements;
    private final PoolMemberRepository poolMembers;
    private final Crews crews;
    private final PeriodRepository periods;
    private final AccessService access;
    private final SheetRepository sheetRows;
    private final Rollup rollup;
    private final ApplicationEventPublisher events;
    private final PersonMergeRepository merges;
    private final PersonService personService;
    private final SheetService sheets;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public PersonMergeService(PersonRepository people, PatronRepository patrons, WatchRepository watches,
            ClaimRepository claims, StatementService statements, PoolMemberRepository poolMembers,
            Crews crews, PeriodRepository periods, AccessService access, SheetRepository sheetRows, Rollup rollup, ApplicationEventPublisher events,
            PersonMergeRepository merges, PersonService personService, SheetService sheets, JdbcTemplate jdbc,
            JsonMapper json) {
        this.merges = merges;
        this.personService = personService;
        this.sheets = sheets;
        this.jdbc = jdbc;
        this.json = json;
        this.people = people;
        this.patrons = patrons;
        this.watches = watches;
        this.claims = claims;
        this.statements = statements;
        this.poolMembers = poolMembers;
        this.crews = crews;
        this.periods = periods;
        this.access = access;
        this.sheetRows = sheetRows;
        this.rollup = rollup;
        this.events = events;
    }

    /** Merge the person into the other; answers with the other as they are now. */
    @Transactional
    public Person merge(String sheetId, String user, long personId, long intoId) {
        sheetRows.lockById(sheetId);
        if (access.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can merge people");
        }
        if (personId == intoId) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A person cannot be merged into themselves");
        }
        Person from = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        Person into = people.findByIdAndSheetId(intoId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No such person"));
        if (from.isRetired() || into.isRetired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A retired person cannot be merged");
        }
        Rollup.Figures before = rollup.figures(sheetId);
        Merged was = Merged.of(from);

        List<Claim> moving = new ArrayList<>(claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId));
        moving.sort(Comparator.comparing(Claim::getId));
        for (Claim claim : moving) {
            claim.setPersonId(intoId);
        }
        claims.flush();
        for (Claim claim : moving) {
            claims.relist(claim.getId());
        }

        into.addAlias(from.getName());
        from.getAliases().forEach(into::addAlias);

        mergePatrons(sheetId, personId, intoId);
        mergePools(personId, intoId);
        crews.merge(personId, intoId);
        watches.deleteAll(watches.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId));
        statements.carry(sheetId, personId, sheetId, intoId);
        periods.findBySheetIdOrderByFromDateAsc(sheetId).forEach(p -> p.carryPerson(personId, intoId));
        periods.flush();
        watches.flush();
        people.delete(from);
        people.flush();
        merges.saveAndFlush(new PersonMerge(sheetId, intoId, personId, user, sheets.today(sheetId),
                json.writeValueAsString(was), moving.stream().map(Claim::getId).toList()));

        events.publishEvent(new PersonMerged(sheetId, user, from.getName(), into.getName()));
        rollup.changed(sheetId, user, before);
        return into;
    }

    /** Undo the last merge into the person; answers with them as they are now. */
    @Transactional
    public Person undo(String sheetId, String user, long intoId) {
        sheetRows.lockById(sheetId);
        if (access.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can undo a merge");
        }
        Person into = people.findByIdAndSheetId(intoId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        PersonMerge merge = merges.findFirstBySheetIdAndIntoIdOrderByIdDesc(sheetId, intoId)
                .filter(m -> !m.isUndone() && m.getMergedBy().equals(user)
                        && m.getMergedOn().equals(sheets.today(sheetId)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Only the last merge into a person, by whoever made it, on the day it was made, can be undone"));
        Merged was = json.readValue(merge.getPerson(), Merged.class);
        Rollup.Figures before = rollup.figures(sheetId);

        into.removeAlias(was.name());
        was.aliases().forEach(into::removeAlias);
        people.saveAndFlush(into);
        if (personService.inUse(sheetId, was.name(), null)
                || was.aliases().stream().anyMatch(alias -> personService.inUse(sheetId, alias, null))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Name already in use");
        }
        String contact = was.contact() != null && people.existsBySheetIdAndContact(sheetId, was.contact())
                ? null : was.contact();
        long id = merge.getPersonId();
        jdbc.update("INSERT INTO person (id, sheet_id, name, terms, contact, tags, origin, arrived_on, flag_reason,"
                + " flag_until, approver, list_position, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                id, sheetId, was.name(), was.terms(), contact, was.tags(), was.origin(), was.arrivedOn(),
                was.flagReason(), was.flagUntil(), was.approver(), people.maxPosition(sheetId) + 1);
        was.grades().forEach(g -> jdbc.update("INSERT INTO person_grade (person_id, grade, from_date) VALUES (?, ?, ?)",
                id, g.grade(), g.fromDate()));
        was.datedTerms().forEach(t -> jdbc.update(
                "INSERT INTO person_terms (person_id, terms, from_date) VALUES (?, ?, ?)", id, t.terms(), t.fromDate()));
        was.aliases().forEach(a -> jdbc.update("INSERT INTO person_alias (person_id, alias) VALUES (?, ?)", id, a));
        was.holds().forEach(h -> jdbc.update("INSERT INTO person_hold (person_id, from_date, to_date) VALUES (?, ?, ?)",
                id, h.fromDate(), h.toDate()));
        was.retirements().forEach(r -> jdbc.update(
                "INSERT INTO person_retirement (person_id, from_date, to_date) VALUES (?, ?, ?)",
                id, r.fromDate(), r.toDate()));

        List<Claim> returning = claims.findAllById(merge.getClaimIds()).stream()
                .filter(c -> c.getSheetId().equals(sheetId) && c.getPersonId().equals(intoId))
                .sorted(Comparator.comparing(Claim::getId)).toList();
        for (Claim claim : returning) {
            claim.setPersonId(id);
        }
        claims.flush();
        for (Claim claim : returning) {
            claims.relist(claim.getId());
        }
        merge.undo();
        merges.flush();

        events.publishEvent(new PersonMergeUndone(sheetId, user, was.name(), into.getName()));
        rollup.changed(sheetId, user, before);
        return into;
    }

    /** The merged person's patrons join the other's, and whoever named the merged person names the other. */
    private void mergePatrons(String sheetId, long personId, long intoId) {
        List<Patron> theirs = patrons.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId);
        List<Patron> naming = patrons.findBySheetIdAndPatronIdOrderByIdAsc(sheetId, personId);
        Set<Long> intoPatrons = patrons.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, intoId).stream()
                .map(Patron::getPatronId).collect(Collectors.toCollection(LinkedHashSet::new));
        patrons.deleteAll(theirs);
        patrons.deleteAll(naming);
        patrons.flush();
        List<Patron> added = new ArrayList<>();
        for (Patron p : theirs) {
            long patronId = p.getPatronId();
            if (patronId != intoId && intoPatrons.add(patronId)) {
                added.add(new Patron(sheetId, intoId, patronId));
            }
        }
        for (Patron p : naming) {
            long namer = p.getPersonId();
            if (namer != intoId && !patrons.existsByPersonIdAndPatronId(namer, intoId)
                    && added.stream().noneMatch(a -> a.getPersonId() == namer)) {
                added.add(new Patron(sheetId, namer, intoId));
            }
        }
        patrons.saveAllAndFlush(added);
    }

    /** The merged person's pool memberships move to the other, adding shares where the other is a member. */
    private void mergePools(long personId, long intoId) {
        Map<Long, PoolMember> intoByPool = poolMembers.findByPersonId(intoId).stream()
                .collect(Collectors.toMap(PoolMember::getPoolId, Function.identity()));
        for (PoolMember member : poolMembers.findByPersonId(personId)) {
            PoolMember existing = intoByPool.get(member.getPoolId());
            if (existing == null) {
                member.setPersonId(intoId);
            } else {
                existing.setShare(existing.getShare().add(member.getShare()).min(FULL_SHARE));
                poolMembers.delete(member);
            }
        }
        poolMembers.flush();
    }
}
