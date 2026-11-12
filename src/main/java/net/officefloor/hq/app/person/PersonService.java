package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.pool.Pool;
import net.officefloor.hq.app.pool.PoolRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the people on each sheet. Names arrive validated (not blank) and are stored trimmed; a name
 * is unique on the sheet, compared without regard to case.
 */
@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetRepository sheets;
    private final StatementRepository statements;
    private final PatronRepository patrons;
    private final WatchRepository watches;
    private final RoleService roles;
    private final Rollup rollup;
    private final Audit audit;
    private final ClaimRepository claims;
    private final PoolRepository pools;
    private final PeriodRepository periods;

    public PersonService(PersonRepository people, SheetRepository sheets, StatementRepository statements,
            PatronRepository patrons, WatchRepository watches, Rollup rollup, Audit audit, RoleService roles,
            ClaimRepository claims, PoolRepository pools, PeriodRepository periods) {
        this.claims = claims;
        this.pools = pools;
        this.periods = periods;
        this.roles = roles;
        this.watches = watches;
        this.patrons = patrons;
        this.rollup = rollup;
        this.people = people;
        this.sheets = sheets;
        this.statements = statements;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        requireSheet(sheetId);
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** The sheet's statements by person id, each person's in the order they were taken. */
    @Transactional(readOnly = true)
    public Map<Long, List<Statement>> statements(String sheetId) {
        return statements.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.groupingBy(Statement::getPersonId));
    }

    /** The person's statements in the order they were taken. */
    @Transactional(readOnly = true)
    public List<Statement> statementsOf(long personId) {
        return statements.findByPersonIdOrderByIdAsc(personId);
    }

    /** The sheet's patrons: each person's patrons' ids, by person id. */
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> patrons(String sheetId) {
        return patrons.findBySheetIdOrderByIdAsc(sheetId).stream().collect(Collectors.groupingBy(Patron::getPersonId,
                Collectors.mapping(Patron::getPatronId, Collectors.toList())));
    }

    /** The ids of the person's patrons. */
    @Transactional(readOnly = true)
    public List<Long> patronsOf(long personId) {
        return patrons.findByPersonIdOrderByIdAsc(personId).stream().map(Patron::getPatronId).toList();
    }

    /** Name another person on the sheet as the person's patron: not themselves, and not twice. */
    @Transactional
    public Person addPatron(String sheetId, String user, long personId, long patronId) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        Person patron = people.findByIdAndSheetId(patronId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (person.getId().equals(patron.getId())) {
            throw new PatronRefusedException("A person cannot be their own patron.");
        }
        if (patrons.existsByPersonIdAndPatronId(person.getId(), patron.getId())) {
            throw new PatronRefusedException(patron.getName() + " is already a patron of " + person.getName() + ".");
        }
        try {
            patrons.saveAndFlush(new Patron(sheetId, person.getId(), patron.getId()));
        } catch (DataIntegrityViolationException e) {
            // named by another user at the same moment
            throw new PatronRefusedException(patron.getName() + " is already a patron of " + person.getName() + ".");
        }
        audit.recordOnCommit("PATRON_ADDED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " patron=" + patron.getName());
        return person;
    }

    /** The ids of the people on the sheet the user watches. */
    @Transactional(readOnly = true)
    public Set<Long> watchedBy(String sheetId, String user) {
        return watches.findBySheetIdAndUser(sheetId, user).stream().map(Watch::getPersonId)
                .collect(Collectors.toSet());
    }

    /** Whether the user watches the person. */
    @Transactional(readOnly = true)
    public boolean watching(long personId, String user) {
        return watches.findByPersonIdAndUser(personId, user).isPresent();
    }

    /** The users watching the person, in the order they started watching. */
    @Transactional(readOnly = true)
    public List<String> watchers(long personId) {
        return watches.findByPersonIdOrderByIdAsc(personId).stream().map(Watch::getUser).toList();
    }

    /** Start (on) or stop watching a person as the user; asking for the state already held records nothing. */
    @Transactional
    public Person watch(String sheetId, String user, long personId, boolean on) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        var held = watches.findByPersonIdAndUser(person.getId(), user);
        if (on == held.isPresent()) {
            return person;
        }
        if (on) {
            try {
                watches.saveAndFlush(new Watch(sheetId, person.getId(), user));
            } catch (DataIntegrityViolationException e) {
                // started at the same moment in another window
                throw new WatchRefusedException("You already watch " + person.getName() + ".");
            }
        } else {
            watches.delete(held.get());
        }
        audit.recordOnCommit((on ? "PERSON_WATCHED" : "PERSON_UNWATCHED") + " sheet=" + sheetId + " by=" + user
                + " name=" + person.getName());
        return person;
    }

    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms, String contact) {
        requireSheet(sheetId);
        requireFreeName(sheetId, name.trim(), null);
        String key = contactKey(contact);
        requireFreeContact(sheetId, key, null);
        Rollup.Snapshot before = rollup.take(sheetId);
        Person person = new Person(sheetId, name.trim(), grade);
        person.setTerms(terms);
        person.setContact(key);
        person = saved(person);
        audit("PERSON_ADDED", person, user, today(sheetId), (terms == null ? "" : " terms=" + terms)
                + (key == null ? "" : " contact=" + key));
        rollup.recordChanges(sheetId, user, before);
        return person;
    }

    @Transactional
    /** Change a person's name, and record the grade from a date (the sheet's today when none is given),
     *  keeping the grades already recorded; a save leaving the grade as it is on the sheet's today, with no
     *  from-date, records nothing. Their own terms are set to those given (none clears them), and their
     *  contact to the one given (blank clears it). */
    public Person update(String sheetId, String user, long personId, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, String contact) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (person.getVersion() != version) {
            throw new PersonVersionConflictException(person);
        }
        requireFreeName(sheetId, name.trim(), person.getId());
        String key = contactKey(contact);
        requireFreeContact(sheetId, key, person.getId());
        Rollup.Snapshot before = rollup.take(sheetId);
        LocalDate today = today(sheetId);
        person.setName(name.trim());
        boolean termsChanged = !Objects.equals(terms, person.getTerms());
        person.setTerms(terms);
        boolean contactChanged = !Objects.equals(key, person.getContact());
        person.setContact(key);
        if (gradeFrom != null || !grade.equals(person.gradeOn(today))) {
            person.setGrade(grade, gradeFrom != null ? gradeFrom : today);
        }
        Person updated = saved(person);
        audit("PERSON_EDITED", updated, user, today, (termsChanged ? " terms=" + (terms == null ? "" : terms) : "")
                + (contactChanged ? " contact=" + (key == null ? "" : key) : ""));
        rollup.recordChanges(sheetId, user, before);
        return updated;
    }

    /** Give each of the chosen people the grade from a date, in list order, exactly as the person form
     *  would with their name and terms left as they are; the people changed, in list order. A viewer's goes
     *  through with no one changed. */
    @Transactional
    public List<Person> bulkGrade(String sheetId, String user, List<Long> personIds, String grade, LocalDate from) {
        requireSheet(sheetId);
        if (roles.isViewer(sheetId, user)) {
            return List.of();
        }
        Set<Long> chosen = Set.copyOf(personIds);
        List<Person> picked = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(p -> chosen.contains(p.getId())).toList();
        if (picked.size() != chosen.size()) {
            throw new PersonNotFoundException();
        }
        return picked.stream().map(p -> update(sheetId, user, p.getId(), p.getVersion(), p.getName(), grade, from,
                p.getTerms(), p.getContact())).toList();
    }

    /** Give a person an alias, stored trimmed: refused when blank, or when it equals, ignoring case, any
     *  name or alias on the sheet. */
    @Transactional
    public Person addAlias(String sheetId, String user, long personId, String alias) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (alias == null || alias.isBlank()) {
            throw new AliasRefusedException("Enter an alias.");
        }
        String trimmed = alias.trim();
        if (trimmed.length() > 200) {
            throw new AliasRefusedException("The alias is too long.");
        }
        if (people.nameTaken(sheetId, trimmed, null)) {
            throw new AliasRefusedException("That name is already in use.");
        }
        person.addAlias(trimmed);
        Person aliased;
        try {
            aliased = people.saveAndFlush(person);
        } catch (DataIntegrityViolationException e) {
            // taken by another user at the same moment
            throw new AliasRefusedException("That name is already in use.");
        }
        audit.recordOnCommit("PERSON_ALIAS_ADDED sheet=" + sheetId + " by=" + user + " name=" + aliased.getName()
                + " alias=" + trimmed);
        return aliased;
    }

    /** A steward merges a person into another: the merged person's claims move to the other (numbers and
     *  dates kept, listed after the other's in the order they were added, each losing any figure a statement
     *  bound); their name and aliases become the other's aliases; their patrons are added to the other's (not
     *  themselves, no repeats), anyone naming them as patron names the other instead (no repeats), and their
     *  pool memberships move to the other (shares added, at most 100.00, where the other is already a member)
     *  as do their figures for closed periods. Their statements, holds, watchers and contact are dropped, and
     *  they leave the sheet. Refused for anyone but a steward, for the same person, and when either is
     *  retired. */
    @Transactional
    public Person merge(String sheetId, String user, long personId, long intoId) {
        Person from = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        Person into = people.findByIdAndSheetId(intoId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (!roles.isSteward(sheetId, user)) {
            throw new MergeRefusedException("Only a steward may merge people.");
        }
        if (from.getId().equals(into.getId())) {
            throw new MergeRefusedException("A person cannot be merged into themselves.");
        }
        if (from.isRetired() || into.isRetired()) {
            throw new MergeRefusedException("A retired person cannot be merged.");
        }
        Rollup.Snapshot before = rollup.take(sheetId);
        List<Claim> moving = new ArrayList<>(
                claims.findForUpdateBySheetIdAndPersonIdOrderByListOrderDesc(sheetId, from.getId()));
        moving.sort(Comparator.comparing(Claim::getId));
        long order = claims.maxListOrder(sheetId);
        for (Claim claim : moving) {
            claim.setPersonId(into.getId());
            claim.setStated(false, null);
            claim.setListOrder(++order);
        }
        claims.saveAll(moving);

        List<Long> intoPatrons = new ArrayList<>(patronsOf(into.getId()));
        for (Patron patron : patrons.findByPersonIdOrderByIdAsc(from.getId())) {
            if (!patron.getPatronId().equals(into.getId()) && !intoPatrons.contains(patron.getPatronId())) {
                patrons.save(new Patron(sheetId, into.getId(), patron.getPatronId()));
                intoPatrons.add(patron.getPatronId());
            }
            patrons.delete(patron);
        }
        for (Patron patron : patrons.findBySheetIdOrderByIdAsc(sheetId)) {
            if (!patron.getPatronId().equals(from.getId())) {
                continue;
            }
            if (!patron.getPersonId().equals(into.getId())
                    && !patrons.existsByPersonIdAndPatronId(patron.getPersonId(), into.getId())) {
                patrons.save(new Patron(sheetId, patron.getPersonId(), into.getId()));
            }
            patrons.delete(patron);
        }

        for (Pool pool : pools.findBySheetIdOrderByIdAsc(sheetId)) {
            if (pool.hasMember(from.getId())) {
                pool.moveMember(from.getId(), into.getId());
            }
        }
        for (Period period : periods.findBySheetIdOrderByFromDateAsc(sheetId)) {
            period.moveClosedTotal(from.getId(), into.getId());
        }
        watches.deleteAll(watches.findByPersonIdOrderByIdAsc(from.getId()));
        statements.deleteAll(statements.findByPersonIdOrderByIdAsc(from.getId()));
        // everything naming them goes before they do
        people.flush();

        List<String> names = new ArrayList<>();
        names.add(from.getName());
        names.addAll(from.getAliases());
        people.delete(from);
        people.flush();
        names.forEach(into::addAlias);
        Person merged = people.saveAndFlush(into);
        audit.recordOnCommit("PERSON_MERGED sheet=" + sheetId + " by=" + user + " from=" + from.getName()
                + " into=" + merged.getName());
        rollup.recordChanges(sheetId, user, before);
        return merged;
    }

    /** Retire a person from a date, the sheet's today when none is given. */
    @Transactional
    public Person retire(String sheetId, String user, long personId, LocalDate from) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        LocalDate on = from != null ? from : today(sheetId);
        Rollup.Snapshot before = rollup.take(sheetId);
        person.setRetiredFrom(on);
        Person retired = saved(person);
        audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + user + " name=" + retired.getName()
                + " from=" + on);
        rollup.recordChanges(sheetId, user, before);
        return retired;
    }

    /** Put a person on hold from a date (the sheet's today when none is given) to a date when one is
     *  given; a hold ending before it starts is refused. */
    @Transactional
    public Person hold(String sheetId, String user, long personId, LocalDate from, LocalDate to) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        LocalDate on = from != null ? from : today(sheetId);
        if (to != null && to.isBefore(on)) {
            throw new HoldRefusedException("A hold cannot end before it starts.");
        }
        Rollup.Snapshot before = rollup.take(sheetId);
        person.hold(on, to);
        Person held = saved(person);
        audit.recordOnCommit("PERSON_HELD sheet=" + sheetId + " by=" + user + " name=" + held.getName()
                + " from=" + on);
        rollup.recordChanges(sheetId, user, before);
        return held;
    }

    /** The grade recorded is the one in force on the sheet's today; extra ends the line. */
    private void audit(String event, Person person, String user, LocalDate today, String extra) {
        audit.recordOnCommit(event + " sheet=" + person.getSheetId() + " by=" + user
                + " name=" + person.getName() + " grade=" + person.gradeOn(today) + extra);
    }

    /** The sheet's today, the date the grade the screen shows is in force on. */
    @Transactional(readOnly = true)
    public LocalDate today(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).getToday();
    }

    private void requireFreeName(String sheetId, String name, Long exceptId) {
        if (people.nameTaken(sheetId, name, exceptId)) {
            throw new PersonNameTakenException();
        }
    }

    /** A contact as stored: trimmed and lower-cased; none when blank. */
    private static String contactKey(String contact) {
        if (contact == null || contact.isBlank()) {
            return null;
        }
        return contact.trim().toLowerCase(Locale.ROOT);
    }

    private void requireFreeContact(String sheetId, String contact, Long exceptId) {
        if (contact != null && people.contactTaken(sheetId, contact, exceptId)) {
            throw new PersonContactTakenException();
        }
    }

    /** Save now, so a name another user took at the same moment is refused here, not at commit. */
    private Person saved(Person person) {
        try {
            return people.saveAndFlush(person);
        } catch (DataIntegrityViolationException e) {
            throw new PersonNameTakenException();
        }
    }

    /** The person as stored now, for an editor whose save was refused. */
    @Transactional(readOnly = true)
    public Person current(String sheetId, long personId) {
        return people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
    }

    private void requireSheet(String sheetId) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
    }
}
