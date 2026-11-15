package net.officefloor.hq.app.person;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.pool.PoolMember;
import net.officefloor.hq.app.pool.PoolMemberRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.Role;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PersonService {

    private final PersonRepository people;
    private final PersonStatementRepository statements;
    private final PersonPatronRepository patrons;
    private final PersonWatchRepository watches;
    private final ClaimRepository claims;
    private final PoolMemberRepository poolMembers;
    private final SettingsService settings;
    private final SheetService sheets;
    private final RollupService rollup;
    private final Audit audit;

    public PersonService(PersonRepository people, PersonStatementRepository statements,
            PersonPatronRepository patrons, PersonWatchRepository watches, ClaimRepository claims,
            PoolMemberRepository poolMembers, SettingsService settings, SheetService sheets, RollupService rollup,
            Audit audit) {
        this.people = people;
        this.statements = statements;
        this.patrons = patrons;
        this.watches = watches;
        this.claims = claims;
        this.poolMembers = poolMembers;
        this.settings = settings;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** The sheet's today: the date the grade a person shows is in force on. */
    @Transactional(readOnly = true)
    public LocalDate today(String sheetId) {
        return sheets.today(sheetId);
    }

    @Transactional(readOnly = true)
    public Person get(String sheetId, long id) {
        return people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
    }

    /** Each person's statement summary, by person id; a person with none is missing. */
    @Transactional(readOnly = true)
    public Map<Long, StatementSummary> statementSummaries(String sheetId) {
        return statements.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.groupingBy(PersonStatement::getPersonId,
                        Collectors.collectingAndThen(Collectors.toList(), StatementSummary::of)));
    }

    @Transactional(readOnly = true)
    public StatementSummary statementSummary(long personId) {
        return StatementSummary.of(statements.findByPersonIdOrderByIdAsc(personId));
    }

    /** Each person's patron ids in the order they were named, by person id; a person with none is missing. */
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> patronIds(String sheetId) {
        return patrons.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.groupingBy(PersonPatron::getPersonId,
                        Collectors.mapping(PersonPatron::getPatronId, Collectors.toList())));
    }

    @Transactional(readOnly = true)
    public List<Long> patronIds(long personId) {
        return patrons.findByPersonIdOrderByIdAsc(personId).stream().map(PersonPatron::getPatronId).toList();
    }

    /** The ids of the people on the sheet the user watches. */
    @Transactional(readOnly = true)
    public Set<Long> watchedIds(String sheetId, String user) {
        return watches.findBySheetIdAndUserName(sheetId, user).stream().map(PersonWatch::getPersonId)
                .collect(Collectors.toSet());
    }

    @Transactional(readOnly = true)
    public boolean isWatching(long personId, String user) {
        return watches.existsByPersonIdAndUserName(personId, user);
    }

    /** Start (on) or stop watching the person as the user; already so, nothing changes; refused
     *  (403) on an archived sheet. */
    @Transactional
    public Person watch(String sheetId, String user, long id, boolean on) {
        if (sheets.archived(sheetId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The sheet is archived: nothing on it may change");
        }
        Person person = people.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (on) {
            if (watches.existsByPersonIdAndUserName(person.getId(), user)) {
                return person;
            }
            watches.saveAndFlush(new PersonWatch(sheetId, person.getId(), user));
            audit.recordOnCommit("PERSON_WATCHED sheet=" + sheetId + " by=" + user + " name=" + person.getName());
        } else if (watches.deleteByPersonIdAndUserName(person.getId(), user) > 0) {
            audit.recordOnCommit("PERSON_UNWATCHED sheet=" + sheetId + " by=" + user + " name=" + person.getName());
        }
        return person;
    }

    /** Name another person on the sheet as the person's patron; refused for the person themselves
     *  and for a patron they already have. */
    @Transactional
    public Person addPatron(String sheetId, String user, long id, long patronId) {
        Person person = people.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        Person patron = people.findByIdAndSheetId(patronId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such patron"));
        if (person.getId().equals(patron.getId())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "A person cannot be their own patron");
        }
        if (patrons.existsByPersonIdAndPatronId(person.getId(), patron.getId())) {
            throw patronAlready();
        }
        try {
            patrons.saveAndFlush(new PersonPatron(sheetId, person.getId(), patron.getId()));
        } catch (DataIntegrityViolationException e) {
            throw patronAlready(); // named by another user at the same moment
        }
        audit.recordOnCommit("PATRON_ADDED sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " patron=" + patron.getName());
        return person;
    }

    /** Give the person an alias (trimmed); refused when blank, or when it equals, ignoring case, any
     *  name or alias on the sheet. */
    @Transactional
    public Person addAlias(String sheetId, String user, long id, String alias) {
        if (sheets.archived(sheetId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The sheet is archived: nothing on it may change");
        }
        Person person = people.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        String trimmed = alias == null ? "" : alias.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "An alias may not be blank");
        }
        requireUnusedName(sheetId, null, trimmed);
        person.addAlias(trimmed);
        try {
            people.flush();
        } catch (DataIntegrityViolationException e) {
            throw nameInUse(); // given by another user at the same moment
        } catch (ObjectOptimisticLockingFailureException e) {
            throw savedByAnother(); // saved by another user at the same moment
        }
        audit.recordOnCommit("PERSON_ALIAS_ADDED sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " alias=" + trimmed);
        return person;
    }

    /** Add a person to the sheet (the sheet is created first if it was never loaded); {@code contact}
     *  (trimmed and lower-cased, null: none) is refused when another person on the sheet has it. */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms, String contact) {
        sheets.load(sheetId, user);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String trimmed = name.trim();
        requireUnusedName(sheetId, null, trimmed);
        requireUnusedContact(sheetId, null, contact);
        Person person;
        try {
            Person fresh = new Person(sheetId, trimmed, grade);
            fresh.setTerms(terms);
            fresh.setContact(contact);
            person = people.saveAndFlush(fresh);
        } catch (DataIntegrityViolationException e) {
            throw inUse(e); // added by another user at the same moment
        }
        record("PERSON_ADDED", sheetId, user, person, sheets.today(sheetId),
                (terms == null ? "" : termsField(terms)) + (contact == null ? "" : contactField(contact)));
        rollup.recordChanges(sheetId, user, before);
        return person;
    }

    /** Save a person; refused when {@code version} (the one the editor loaded) is no longer the stored one.
     *  The grade is recorded from {@code gradeFrom} (null: the sheet's today), keeping those already
     *  recorded; with no from-date, a grade the same as the one in force on the sheet's today records nothing.
     *  The person's own terms become {@code terms} (null: none), and their contact {@code contact}
     *  (null: none), refused when another person on the sheet has it. */
    @Transactional
    public Person update(String sheetId, String user, long id, Long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, String contact) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (version == null || version != person.getVersion()) {
            throw savedByAnother();
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String trimmed = name.trim();
        requireUnusedName(sheetId, id, trimmed);
        requireUnusedContact(sheetId, id, contact);
        person.setName(trimmed);
        boolean termsChanged = !Objects.equals(terms, person.getTerms());
        person.setTerms(terms);
        boolean contactChanged = !Objects.equals(contact, person.getContact());
        person.setContact(contact);
        LocalDate today = sheets.today(sheetId);
        if (gradeFrom != null || !grade.equals(person.gradeOn(today))) {
            person.recordGrade(grade, gradeFrom != null ? gradeFrom : today);
        }
        try {
            people.flush();
        } catch (DataIntegrityViolationException e) {
            throw inUse(e); // taken by another user at the same moment
        } catch (ObjectOptimisticLockingFailureException e) {
            throw savedByAnother(); // saved by another user at the same moment
        }
        record("PERSON_EDITED", sheetId, user, person, today,
                (termsChanged ? termsField(terms) : "") + (contactChanged ? contactField(contact) : ""));
        rollup.recordChanges(sheetId, user, before);
        return person;
    }

    /** Give each of the people of {@code ids}, in list order, {@code grade} from {@code gradeFrom}
     *  (null: the sheet's today) as saving them in the person form would, each written with its
     *  PERSON_EDITED and rollup lines; all or none. Refused (404) when any is not on the sheet. */
    @Transactional
    public int grade(String sheetId, String user, List<Long> ids, String grade, LocalDate gradeFrom) {
        Set<Long> wanted = Set.copyOf(ids);
        List<Person> chosen = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(p -> wanted.contains(p.getId())).toList();
        if (chosen.size() != wanted.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person");
        }
        LocalDate today = sheets.today(sheetId);
        for (Person person : chosen) {
            RollupService.Snapshot before = rollup.snapshot(sheetId);
            if (gradeFrom != null || !grade.equals(person.gradeOn(today))) {
                person.recordGrade(grade, gradeFrom != null ? gradeFrom : today);
            }
            try {
                people.flush();
            } catch (ObjectOptimisticLockingFailureException e) {
                throw savedByAnother(); // saved by another user at the same moment
            }
            record("PERSON_EDITED", sheetId, user, person, today, "");
            rollup.recordChanges(sheetId, user, before);
        }
        return chosen.size();
    }

    /** Retire a person from a date (a missing one takes the sheet's today); they count as retired
     *  at once. Retiring them again moves the date. From the day the date is reached their open
     *  claims, unless bound by a statement, leave the live totals. */
    @Transactional
    public Person retire(String sheetId, String user, long id, LocalDate from) {
        Person person = people.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate on = from != null ? from : sheets.today(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        person.setRetiredFrom(on);
        people.flush();
        audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " from=" + on);
        rollup.recordChanges(sheetId, user, before);
        return person;
    }

    /** Add a hold to a person from a date (a missing one takes the sheet's today) to an optional
     *  one; their OPEN claims dated within it then count in no live total, unless bound by a
     *  statement. A to-date before the from-date is refused (422). */
    @Transactional
    public Person hold(String sheetId, String user, long id, LocalDate from, LocalDate to) {
        Person person = people.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate on = from != null ? from : sheets.today(sheetId);
        if (to != null && to.isBefore(on)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "The to-date is before the from-date");
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        person.addHold(on, to);
        people.flush();
        audit.recordOnCommit("PERSON_HELD sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " from=" + on);
        rollup.recordChanges(sheetId, user, before);
        return person;
    }

    /** Merge a person into another person on the sheet, as a steward: the merged person's claims
     *  move to the other (numbers and dates kept, listed after the other's in the order they were
     *  added, each unbound from any statement), their name and aliases become the other's aliases,
     *  their patrons are added to the other's (not the other themselves, no repeats), anyone naming
     *  them as patron names the other instead (no repeats), and their pool memberships move to the
     *  other (shares added where the other is a member already). Their statements, holds, watchers
     *  and contact go with them. Refused (404) for a person not on the sheet, (403) unless the user
     *  acts as a steward, (422) for the same person, either of them retired, or a pool share added
     *  past 100.00. */
    @Transactional
    public Person merge(String sheetId, String user, long id, long intoId) {
        Person from = people.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        Person into = people.findForUpdate(intoId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward may merge people");
        }
        if (from.getId().equals(into.getId())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "A person cannot be merged into themselves");
        }
        if (from.isRetired() || into.isRetired()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "A retired person cannot be merged");
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);

        // pool memberships: moved, or the share added to the other's
        for (PoolMember member : poolMembers.findByPersonIdOrderByIdAsc(from.getId())) {
            var theirs = poolMembers.findByPoolIdAndPersonId(member.getPoolId(), into.getId());
            if (theirs.isPresent()) {
                BigDecimal share = theirs.get().getShare().add(member.getShare());
                if (share.compareTo(BigDecimal.valueOf(100)) > 0) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                            "The merged shares would be over 100.00 in a pool");
                }
                theirs.get().setShare(share);
                poolMembers.delete(member);
            } else {
                member.setPersonId(into.getId());
            }
        }
        poolMembers.flush();

        // claims: moved, listed after the other's in the order they were added, unbound
        long listed = claims.findLastListed(sheetId);
        List<Claim> moved = claims.findByPersonForUpdate(from.getId(), sheetId).stream()
                .sorted(Comparator.comparing(Claim::getId)).toList();
        for (Claim claim : moved) {
            claim.setPersonId(into.getId());
            claim.setListed(++listed);
            claim.unbind();
        }
        claims.flush();

        // patrons: the merged person's added to the other's; those naming the merged person name the other
        Set<Long> intoPatrons = new LinkedHashSet<>(patronIds(into.getId()));
        List<PersonPatron> theirPatrons = patrons.findByPersonIdOrderByIdAsc(from.getId());
        List<PersonPatron> namingThem = patrons.findByPatronIdOrderByIdAsc(from.getId());
        for (PersonPatron named : theirPatrons) {
            if (!named.getPatronId().equals(into.getId()) && intoPatrons.add(named.getPatronId())) {
                patrons.save(new PersonPatron(sheetId, into.getId(), named.getPatronId()));
            }
        }
        for (PersonPatron naming : namingThem) {
            if (!naming.getPersonId().equals(into.getId())
                    && !naming.getPersonId().equals(from.getId())
                    && !patrons.existsByPersonIdAndPatronId(naming.getPersonId(), into.getId())) {
                patrons.save(new PersonPatron(sheetId, naming.getPersonId(), into.getId()));
            }
        }
        patrons.deleteAll(theirPatrons);
        patrons.deleteAll(namingThem);
        patrons.flush();

        // statements and watchers go with them
        statements.deleteAll(statements.findByPersonIdOrderByIdAsc(from.getId()));
        watches.deleteAll(watches.findByPersonIdOrderByIdAsc(from.getId()));
        statements.flush();
        watches.flush();

        String fromName = from.getName();
        List<String> fromAliases = from.getAliases();
        people.delete(from);
        people.flush();
        into.addAlias(fromName);
        fromAliases.forEach(into::addAlias);
        try {
            people.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw savedByAnother(); // saved by another user at the same moment
        }
        audit.recordOnCommit("PERSON_MERGED sheet=" + sheetId + " by=" + user + " from=" + fromName
                + " into=" + into.getName());
        rollup.recordChanges(sheetId, user, before);
        return into;
    }

    /** Written with the grade in force on {@code today}, the sheet's today, then {@code extra}. */
    private void record(String event, String sheetId, String user, Person person, LocalDate today, String extra) {
        audit.recordOnCommit(event + " sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " grade=" + person.gradeOn(today) + extra);
    }

    /** The terms field of a person's record: the days, empty when none. */
    private static String termsField(Integer terms) {
        return " terms=" + (terms == null ? "" : terms);
    }

    /** The contact field of a person's record: the contact, empty when none. */
    private static String contactField(String contact) {
        return " contact=" + (contact == null ? "" : contact);
    }

    /** Refuse a contact another person on the sheet already has; none is never refused. */
    private void requireUnusedContact(String sheetId, Long exceptId, String contact) {
        if (contact == null) {
            return;
        }
        boolean taken = people.findBySheetIdAndContact(sheetId, contact).stream()
                .anyMatch(p -> !p.getId().equals(exceptId));
        if (taken) {
            throw contactInUse();
        }
    }

    /** A unique name or contact taken by another user at the same moment. */
    private static ResponseStatusException inUse(DataIntegrityViolationException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage()).toUpperCase(java.util.Locale.ROOT);
        return message.contains("PERSON_SHEET_CONTACT") ? contactInUse() : nameInUse();
    }

    /** Refuse a name another person on the sheet already has as their name or an alias (ignoring
     *  case); {@code exceptId} null checks against everyone, the person's own name and aliases too. */
    private void requireUnusedName(String sheetId, Long exceptId, String name) {
        boolean taken = people.findBySheetIdAndNameIgnoreCase(sheetId, name).stream()
                .anyMatch(p -> !p.getId().equals(exceptId))
                || !people.findBySheetIdAndAliasIgnoreCase(sheetId, name).isEmpty();
        if (taken) {
            throw nameInUse();
        }
    }

    private static ResponseStatusException savedByAnother() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Saved by someone else since it was loaded");
    }

    private static ResponseStatusException patronAlready() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Already a patron");
    }

    private static ResponseStatusException contactInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Contact already in use");
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Name already in use");
    }
}
