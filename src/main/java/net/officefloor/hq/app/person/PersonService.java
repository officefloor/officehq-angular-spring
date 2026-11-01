package net.officefloor.hq.app.person;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.pool.PoolMember;
import net.officefloor.hq.app.pool.PoolMemberRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetParentRepository;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.statement.StatementRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The people on a sheet: listed by their positions (new people at the end), moved up or down one place at a time, added and edited one at a time. A
 * name already used by another person on the sheet (ignoring case and surrounding spaces) is
 * refused, and so is an edit made from a version of the person someone else has saved since. A
 * person can be retired from a date (blank: the sheet's today), and put on hold from a from-date (blank: the sheet's today) to an optional to-date, any number of
 * times. A
 * person can name other people on the sheet as their patrons, one at a time; not themselves, and
 * not the same person twice. A person's grade is dated: the first one given is in force from the
 * beginning of time, and each edit records the grade from its from-date (blank: the sheet's today),
 * unless it leaves the grade as in force on the sheet's today with the from-date blank. A person may
 * have their own terms in days (null: none), given on the add and on each edit, and a contact (blank:
 * none), kept without surrounding spaces and lower-cased; one another person on the sheet has is refused.
 * A person may have aliases, added one at a time; an alias shares the names' uniqueness: it may not be,
 * as names are compared, any name or alias on the sheet, and no name may be an alias on the sheet. The
 * sheet is locked while a name or alias is checked and written, so two users cannot take one at once.
 * Nor may a name or alias be used, as names are compared, when another sheet of the sheet's tree
 * ({@link #treeOf}) already uses it as a name or alias. People who already clash so are kept, shown as
 * clashing ({@link #clashingIds}), and each edit of them is refused until it renames them to a name that
 * does not clash, leaving them no alias that does.
 * A steward can merge a person into another ({@link #merge}), and transfer one to another sheet
 * ({@link #transfer}).
 */
@Service
public class PersonService {

    private final PersonRepository people;
    private final PersonPatronRepository patrons;
    private final PersonWatchRepository watches;
    private final SheetRepository sheets;
    private final ClaimRepository claims;
    private final RollupService rollup;
    private final SettingService settings;
    private final StatementRepository statements;
    private final PoolMemberRepository poolMembers;
    private final PeriodRepository periods;
    private final ClaimService claimService;
    private final SheetParentRepository parents;
    private final SheetService sheetService;
    private final Audit audit;

    public PersonService(PersonRepository people, PersonPatronRepository patrons,
            PersonWatchRepository watches, SheetRepository sheets, ClaimRepository claims, RollupService rollup,
            SettingService settings, StatementRepository statements, PoolMemberRepository poolMembers,
            PeriodRepository periods, ClaimService claimService, SheetParentRepository parents, SheetService sheetService, Audit audit) {
        this.parents = parents;
        this.sheetService = sheetService;
        this.claimService = claimService;
        this.statements = statements;
        this.poolMembers = poolMembers;
        this.periods = periods;
        this.claims = claims;
        this.watches = watches;
        this.people = people;
        this.patrons = patrons;
        this.sheets = sheets;
        this.rollup = rollup;
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
    }

    /** The ids of the sheet's people who are active on its today ({@link Person#activeOn}). */
    @Transactional(readOnly = true)
    public Set<Long> activeIds(String sheetId) {
        LocalDate today = today(sheetId);
        List<Claim> all = claims.findBySheetIdOrderByIdDesc(sheetId);
        Set<Long> ids = new HashSet<>();
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            if (person.activeOn(all, today)) ids.add(person.getId());
        }
        return ids;
    }

    /** Whether the person is active on their sheet's today. */
    @Transactional(readOnly = true)
    public boolean isActive(Person person) {
        return person.activeOn(claims.findBySheetIdAndPersonIdOrderByIdDesc(person.getSheetId(), person.getId()),
                today(person.getSheetId()));
    }

    /** The sheet's today, or null when there is no such sheet. */
    @Transactional(readOnly = true)
    public LocalDate today(String sheetId) {
        return sheets.findById(sheetId).map(Sheet::getToday).orElse(null);
    }

    @Transactional
    public Person add(String sheetId, String user, String name, String grade) {
        return add(sheetId, user, name, grade, null, null);
    }

    /** Add a person with their own terms in days (null: none) and contact (blank: none). */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms, String contact) {
        return add(sheetId, user, name, grade, terms, contact, List.of());
    }

    /** Add a person with their own terms, contact and tags (as {@link Claim#tagsOf} reads them; empty: none). */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms, String contact,
            List<String> tags) {
        if (sheets.findForUpdate(sheetId).isEmpty()) throw new SheetNotFoundException();
        requireUnused(sheetId, name, null);
        requireUnusedInTree(sheetId, name);
        String key = Person.contactOf(contact);
        requireContactUnused(sheetId, key, null);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person draft = new Person(sheetId, name, grade, today(sheetId));
        draft.setTerms(terms);
        draft.setContact(key);
        draft.setTags(tags);
        draft.setPosition(people.maxPosition(sheetId) + 1);
        Person person = saveUnique(draft);
        claimService.keepTags(sheetId, person.getTags());
        record("PERSON_ADDED", sheetId, user, person, person.getGrade(),
                (terms != null ? " terms=" + terms : "") + (key != null ? " contact=" + key : ""));
        rollup.recordChanges(sheetId, user, before);
        return person;
    }

    @Transactional
    public Person edit(String sheetId, String user, Long id, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, String contact) {
        return edit(sheetId, user, id, version, name, grade, gradeFrom, terms, contact, null);
    }

    /** Edit a person, giving them the tags (as {@link Claim#tagsOf} reads them); null: their tags kept. */
    @Transactional
    public Person edit(String sheetId, String user, Long id, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, String contact, List<String> tags) {
        Person person = people.findByIdAndSheetId(id, sheetId).orElseThrow(PersonNotFoundException::new);
        if (person.getVersion() != version) throw new PersonVersionConflictException();
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        requireUnused(sheetId, name, id);
        requireUnusedInTree(sheetId, name);
        for (String alias : person.getAliases()) requireUnusedInTree(sheetId, alias);
        String key = Person.contactOf(contact);
        requireContactUnused(sheetId, key, id);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate today = today(sheetId);
        person.setName(name);
        boolean termsChanged = !Objects.equals(terms, person.getTerms());
        person.setTerms(terms);
        boolean contactChanged = !Objects.equals(key, person.getContact());
        person.setContact(key);
        if (tags != null) person.setTags(tags);
        if (gradeFrom != null || !grade.equals(person.gradeOn(today))) {
            person.setGradeFrom(gradeFrom != null ? gradeFrom : today, grade, today);
        }
        Person saved = saveUnique(person);
        claimService.keepTags(sheetId, saved.getTags());
        record("PERSON_EDITED", sheetId, user, saved, saved.gradeOn(today),
                (termsChanged ? " terms=" + (terms != null ? terms : "") : "")
                        + (contactChanged ? " contact=" + (key != null ? key : "") : ""));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Give each of the people the grade from the date (blank: the sheet's today), in list order, each as
     * its own edit would: name and terms kept, with its PERSON_EDITED and rollup lines, and no grade
     * recorded for one left on the grade in force today with the date blank. One not on the
     * sheet refuses them all. A viewer's, or one on an archived sheet, goes through with nothing done.
     * Returns how many were changed.
     */
    @Transactional
    public int bulkGrade(String sheetId, String user, Set<Long> ids, String grade, LocalDate from) {
        Sheet sheet = sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        if (sheet.isArchived() || settings.roleOf(sheetId, user) == Role.VIEWER) return 0;
        LocalDate today = sheet.getToday();
        List<Person> chosen = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .filter(p -> ids.contains(p.getId())).toList();
        if (chosen.size() != ids.size()) throw new PersonNotFoundException();
        LocalDate on = from != null ? from : today;
        for (Person person : chosen) {
            RollupService.Snapshot before = rollup.snapshot(sheetId);
            if (from != null || !grade.equals(person.gradeOn(today))) person.setGradeFrom(on, grade, today);
            Person saved = saveUnique(person);
            record("PERSON_EDITED", sheetId, user, saved, saved.gradeOn(today), "");
            rollup.recordChanges(sheetId, user, before);
        }
        return chosen.size();
    }

    /**
     * Retire each of the people from the date (blank: the sheet's today), in list order, each as its
     * own retire would, with its PERSON_RETIRED and rollup lines. One not on the sheet refuses them
     * all. A viewer's, or one on an archived sheet, goes through with nothing done. Returns how many
     * were retired.
     */
    @Transactional
    public int bulkRetire(String sheetId, String user, Set<Long> ids, LocalDate from) {
        // Lock the sheet so no claim is added or edited for them meanwhile against the old date.
        Sheet sheet = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        if (sheet.isArchived() || settings.roleOf(sheetId, user) == Role.VIEWER) return 0;
        LocalDate today = sheet.getToday();
        List<Person> chosen = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .filter(p -> ids.contains(p.getId())).toList();
        if (chosen.size() != ids.size()) throw new PersonNotFoundException();
        LocalDate on = from != null ? from : today;
        for (Person person : chosen) {
            RollupService.Snapshot before = rollup.snapshot(sheetId);
            person.retireFrom(on, today);
            Person saved = saveUnique(person);
            audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + user.strip()
                    + " name=" + saved.getName() + " from=" + on);
            rollup.recordChanges(sheetId, user, before);
        }
        return chosen.size();
    }

    /**
     * Swap the person with the one above (up) or below (down) them in the list; the top one cannot move
     * up nor the bottom one down. Returns them in their new place.
     */
    @Transactional
    public Person move(String sheetId, String user, Long id, boolean up) {
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        List<Person> list = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        int at = -1;
        for (int i = 0; i < list.size(); i++) if (list.get(i).getId().equals(id)) at = i;
        if (at < 0) throw new PersonNotFoundException();
        int to = up ? at - 1 : at + 1;
        if (to < 0 || to >= list.size()) throw new PersonMoveRefusedException();
        Person person = list.get(at);
        Person other = list.get(to);
        long mine = person.getPosition();
        long theirs = other.getPosition();
        if (mine == theirs) {
            // positions tied (ordered by id): give the list distinct positions first
            for (int i = 0; i < list.size(); i++) list.get(i).setPosition(i + 1);
            mine = person.getPosition();
            theirs = other.getPosition();
        }
        person.setPosition(theirs);
        other.setPosition(mine);
        people.saveAllAndFlush(list);
        audit.recordOnCommit("PERSON_MOVED sheet=" + sheetId + " by=" + user.strip()
                + " name=" + person.getName() + " position=" + (to + 1));
        return person;
    }

    @Transactional
    public Person retire(String sheetId, String user, Long id, LocalDate from) {
        // Lock the sheet so no claim is added or edited for them meanwhile against the old date.
        LocalDate today = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new).getToday();
        Person person = people.findByIdAndSheetId(id, sheetId).orElseThrow(PersonNotFoundException::new);
        // Once the sheet's today reaches the date, their open claims leave the totals.
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate on = from != null ? from : today;
        person.retireFrom(on, today);
        Person saved = saveUnique(person);
        audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + user.strip()
                + " name=" + saved.getName() + " from=" + on);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Unretire the retired person on the sheet's today; anyone not retired is refused. Their retirement
     * is kept as a past one, and their open claims count in the totals again.
     */
    @Transactional
    public Person unretire(String sheetId, String user, Long id) {
        LocalDate today = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new).getToday();
        Person person = people.findByIdAndSheetId(id, sheetId).orElseThrow(PersonNotFoundException::new);
        if (!person.isRetired()) throw new PersonNotRetiredException();
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        person.unretire(today);
        Person saved = saveUnique(person);
        audit.recordOnCommit("PERSON_UNRETIRED sheet=" + sheetId + " by=" + user.strip()
                + " name=" + saved.getName() + " on=" + today);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Put the person on hold from the date (blank: the sheet's today) to the optional to-date; their
     * open (OPEN or PENDING) claims dated within it, unless bound by a statement, leave the totals.
     */
    @Transactional
    public Person hold(String sheetId, String user, Long id, LocalDate from, LocalDate to) {
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findByIdAndSheetId(id, sheetId).orElseThrow(PersonNotFoundException::new);
        LocalDate on = from != null ? from : today(sheetId);
        if (on == null) throw new SheetNotFoundException();
        if (to != null && to.isBefore(on)) throw new HoldDatesException();
        person.addHold(new PersonHold(on, to, today(sheetId)));
        Person saved = saveUnique(person);
        audit.recordOnCommit("PERSON_HELD sheet=" + sheetId + " by=" + user.strip()
                + " name=" + saved.getName() + " from=" + on);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /** Give the person an alias, unless it is, as names are compared, a name or an alias on the sheet. */
    @Transactional
    public Person addAlias(String sheetId, String user, Long id, String alias) {
        sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new);
        Person person = people.findByIdAndSheetId(id, sheetId).orElseThrow(PersonNotFoundException::new);
        if (alias.isBlank() || people.findByNameOrAlias(sheetId, alias).isPresent()) {
            throw new DuplicatePersonNameException();
        }
        requireUnusedInTree(sheetId, alias);
        person.addAlias(alias);
        Person saved = saveUnique(person);
        audit.recordOnCommit("PERSON_ALIAS_ADDED sheet=" + sheetId + " by=" + user.strip()
                + " name=" + saved.getName() + " alias=" + alias.strip());
        return saved;
    }

    /** Each person's patrons' ids on the sheet, by person id, in the order they were named. */
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> patronIds(String sheetId) {
        Map<Long, List<Long>> by = new HashMap<>();
        for (PersonPatron p : patrons.findBySheetIdOrderByIdAsc(sheetId)) {
            by.computeIfAbsent(p.getPersonId(), k -> new ArrayList<>()).add(p.getPatronId());
        }
        return by;
    }

    /** The person's patrons' ids, in the order they were named. */
    @Transactional(readOnly = true)
    public List<Long> patronIdsOf(Long personId) {
        return patrons.findByPersonIdOrderByIdAsc(personId).stream().map(PersonPatron::getPatronId).toList();
    }

    /** Name another person on the sheet as the person's patron; not themselves, nor one already named. */
    @Transactional
    public Person addPatron(String sheetId, String user, Long id, Long patronId) {
        Person person = people.findByIdAndSheetId(id, sheetId).orElseThrow(PersonNotFoundException::new);
        Person patron = people.findByIdAndSheetId(patronId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (person.getId().equals(patron.getId())) throw new PatronRefusedException();
        if (patrons.existsByPersonIdAndPatronId(person.getId(), patron.getId())) throw new PatronRefusedException();
        try {
            patrons.saveAndFlush(new PersonPatron(sheetId, person.getId(), patron.getId()));
        } catch (DataIntegrityViolationException e) {
            // named by another user meanwhile
            throw new PatronRefusedException();
        }
        audit.recordOnCommit("PATRON_ADDED sheet=" + sheetId + " by=" + user.strip()
                + " name=" + person.getName() + " patron=" + patron.getName());
        return person;
    }

    /**
     * A steward merges the person into another on the sheet; not into themselves, and neither of them
     * retired. The person's claims move to the other, numbers, dates and due-bys kept, listed as the
     * other's newest in the order they were added, each losing any figure a statement bound it to. Their
     * name and aliases become the other's aliases; their patrons are added to the other's (not the other
     * themselves, no repeats), and anyone naming them as patron names the other instead (no repeats, not
     * themselves). Their pool memberships move to the other, a share added to the other's where the other
     * is already a member, and their due-total fixed for a closed period is added to the other's. Their
     * statements, holds, watchers and contact are dropped, and they leave the sheet.
     */
    @Transactional
    public Person merge(String sheetId, String user, Long id, Long intoId) {
        LocalDate today = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new).getToday();
        Person from = people.findByIdAndSheetId(id, sheetId).orElseThrow(PersonNotFoundException::new);
        Person into = people.findByIdAndSheetId(intoId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (settings.roleOf(sheetId, user) != Role.STEWARD || from.getId().equals(into.getId())
                || from.isRetired() || into.isRetired()) {
            throw new PersonMergeRefusedException();
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person saved = absorb(sheetId, today, from, into, leave(from, into));
        audit.recordOnCommit("PERSON_MERGED sheet=" + sheetId + " by=" + user.strip()
                + " from=" + from.getName() + " into=" + saved.getName());
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * The person leaves for the other, merged into them ({@link Person#mergeInto}); returns their name and
     * aliases, as given, which are to become the other's aliases.
     */
    private static List<String> leave(Person from, Person into) {
        List<String> names = new ArrayList<>();
        names.add(from.getName());
        names.addAll(from.mergeInto(into.getId()));
        return names;
    }

    /**
     * Merge the person, who has left for the other ({@link #leave}), into the other on the sheet (locked by
     * the caller), as {@link #merge} describes; returns the other, saved. The names they left with join
     * the other's aliases, none repeated as given.
     */
    private Person absorb(String sheetId, LocalDate today, Person from, Person into, List<String> names) {
        // claims: listed as the other's newest, in the order they were added
        List<Claim> moving = new ArrayList<>(claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, from.getId()));
        Collections.reverse(moving);
        for (Claim claim : moving) {
            claim.unstate(today);
            claim.moveTo(into.getId(), claims.nextListed());
        }
        claims.saveAllAndFlush(moving);

        // patrons
        List<PersonPatron> named = patrons.findBySheetIdOrderByIdAsc(sheetId);
        Set<Long> intoPatrons = new HashSet<>();
        for (PersonPatron p : named) if (p.getPersonId().equals(into.getId())) intoPatrons.add(p.getPatronId());
        List<Long> added = new ArrayList<>();
        List<PersonPatron> repointed = new ArrayList<>();
        List<PersonPatron> dropped = new ArrayList<>();
        Map<Long, Set<Long>> byPerson = new HashMap<>();
        for (PersonPatron p : named) byPerson.computeIfAbsent(p.getPersonId(), k -> new HashSet<>()).add(p.getPatronId());
        for (PersonPatron p : named) {
            if (p.getPersonId().equals(from.getId())) {
                dropped.add(p);
                Long patron = p.getPatronId();
                if (!patron.equals(into.getId()) && intoPatrons.add(patron)) added.add(patron);
            } else if (p.getPatronId().equals(from.getId())) {
                if (p.getPersonId().equals(into.getId()) || byPerson.get(p.getPersonId()).contains(into.getId())) {
                    dropped.add(p);
                } else {
                    repointed.add(p);
                }
            }
        }
        patrons.deleteAll(dropped);
        patrons.flush();
        for (PersonPatron p : repointed) p.setPatronId(into.getId());
        patrons.saveAll(repointed);
        for (Long patron : added) patrons.save(new PersonPatron(sheetId, into.getId(), patron));
        patrons.flush();

        // pools
        List<PoolMember> members = poolMembers.findBySheetIdOrderByIdAsc(sheetId);
        List<PoolMember> leaving = new ArrayList<>();
        for (PoolMember m : members) {
            if (!from.getId().equals(m.getPersonId())) continue;
            PoolMember theirs = members.stream()
                    .filter(o -> o.getPoolId().equals(m.getPoolId()) && into.getId().equals(o.getPersonId()))
                    .findFirst().orElse(null);
            if (theirs != null) {
                theirs.addShare(m.getShare());
                leaving.add(m);
            } else {
                m.setPersonId(into.getId());
            }
        }
        poolMembers.deleteAll(leaving);
        poolMembers.flush();
        poolMembers.saveAllAndFlush(members.stream().filter(m -> !leaving.contains(m)).toList());

        // closed periods' due-totals
        for (Period period : periods.findBySheetIdOrderByFromDateAsc(sheetId)) {
            Map<Long, BigDecimal> dues = period.getClosedPersonDues();
            BigDecimal theirs = dues.remove(from.getId());
            if (theirs != null) dues.merge(into.getId(), theirs, BigDecimal::add);
        }
        periods.flush();

        // statements and watchers dropped
        statements.deleteAll(statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, from.getId()));
        statements.flush();
        watches.deleteAll(watches.findByPersonIdOrderByIdAsc(from.getId()));
        watches.flush();

        // the person leaves, their name and aliases the other's aliases
        saveUnique(from);
        for (String alias : names) {
            if (!into.getAliases().contains(alias.strip())) into.addAlias(alias);
        }
        return saveUnique(into);
    }

    /**
     * A steward transfers the person to another sheet by its id; not a retired person, not to this sheet
     * nor to an archived one (an id never used starts that sheet, as loading it would; a refused transfer
     * leaves it unstarted), and not when their contact is, as compared, already on the other sheet. They
     * arrive with every grade (and its from-date), their terms, contact, aliases and holds, and their claims
     * ({@link ClaimService#receive}); their statements, patron links either way, pool memberships and
     * watchers are dropped, and they leave this sheet. When a person on the other sheet has, as names are
     * compared, their name or an alias, they are merged into that person there, as {@link #merge} does
     * (their contact then no bar); refused when that person is retired, or when their names are those of
     * more than one person there. Each sheet audits the transfer, the other sheet then the merge, each
     * followed by its rollup lines.
     */
    @Transactional
    public void transfer(String sheetId, String user, Long id, String toSheetId) {
        if (sheetId.equals(toSheetId)) throw new PersonTransferRefusedException();
        if (sheets.existsById(sheetId)) sheetService.load(toSheetId, user.strip());
        // lock both sheets, always in the same order, so two transfers between them cannot deadlock
        boolean thisFirst = sheetId.compareTo(toSheetId) < 0;
        Sheet first = sheets.findForUpdate(thisFirst ? sheetId : toSheetId).orElse(null);
        Sheet second = sheets.findForUpdate(thisFirst ? toSheetId : sheetId).orElse(null);
        Sheet here = thisFirst ? first : second;
        Sheet there = thisFirst ? second : first;
        if (here == null) throw new SheetNotFoundException();
        Person person = people.findByIdAndSheetId(id, sheetId).orElseThrow(PersonNotFoundException::new);
        if (settings.roleOf(sheetId, user) != Role.STEWARD || person.isRetired() || there == null
                || there.isArchived()) {
            throw new PersonTransferRefusedException();
        }
        // one there of their name or an alias, as names are compared, is who they are merged into
        Set<Person> matching = new LinkedHashSet<>();
        for (String name : person.namesAndAliases()) people.findByNameOrAlias(toSheetId, name).ifPresent(matching::add);
        if (matching.size() > 1) throw new PersonTransferRefusedException();
        Person into = matching.stream().findFirst().orElse(null);
        if (into != null && into.isRetired()) throw new PersonTransferRefusedException();
        if (into == null && person.getContact() != null
                && people.findBySheetIdAndContact(toSheetId, person.getContact()).isPresent()) {
            throw new PersonTransferRefusedException();
        }
        RollupService.Snapshot beforeHere = rollup.snapshot(sheetId);
        RollupService.Snapshot beforeThere = rollup.snapshot(toSheetId);

        // what ties them to this sheet is dropped
        patrons.deleteAll(patrons.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(p -> p.getPersonId().equals(id) || p.getPatronId().equals(id)).toList());
        patrons.flush();
        poolMembers.deleteAll(poolMembers.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(m -> id.equals(m.getPersonId())).toList());
        poolMembers.flush();
        statements.deleteAll(statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, id));
        statements.flush();
        watches.deleteAll(watches.findByPersonIdOrderByIdAsc(id));
        watches.flush();

        // they and their claims arrive on the other sheet
        List<Claim> moving = claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, id);
        String name = person.getName();
        List<String> names = into != null ? leave(person, into) : List.of();
        person.transferTo(toSheetId);
        person.setPosition(people.maxPosition(toSheetId) + 1);
        Person saved = saveUnique(person);
        claimService.receive(there, moving);
        claimService.keepTags(toSheetId, saved.getTags());

        audit.recordOnCommit("PERSON_TRANSFERRED sheet=" + sheetId + " by=" + user.strip()
                + " name=" + name + " to=" + toSheetId);
        rollup.recordChanges(sheetId, user, beforeHere);
        audit.recordOnCommit("PERSON_TRANSFERRED sheet=" + toSheetId + " by=" + user.strip()
                + " name=" + name + " from=" + sheetId);
        if (into != null) {
            Person merged = absorb(toSheetId, there.getToday(), saved, into, names);
            audit.recordOnCommit("PERSON_MERGED sheet=" + toSheetId + " by=" + user.strip()
                    + " from=" + name + " into=" + merged.getName());
        }
        rollup.recordChanges(toSheetId, user, beforeThere);
    }

    /** The ids of the people on the sheet the user watches. */
    @Transactional(readOnly = true)
    public Set<Long> watchedIds(String sheetId, String user) {
        return watches.findBySheetIdAndUserName(sheetId, user.strip()).stream()
                .map(PersonWatch::getPersonId).collect(Collectors.toSet());
    }

    /** Whether the user watches the person. */
    @Transactional(readOnly = true)
    public boolean watches(Long personId, String user) {
        return watches.findByPersonIdAndUserName(personId, user.strip()).isPresent();
    }

    /** The users watching the person, in the order they started watching. */
    @Transactional(readOnly = true)
    public List<String> watchersOf(Long personId) {
        return watches.findByPersonIdOrderByIdAsc(personId).stream().map(PersonWatch::getUserName).toList();
    }

    /** Start (true) or stop (false) watching the person as the user; already so, nothing changes. */
    @Transactional
    public Person watch(String sheetId, String user, Long id, boolean on) {
        Person person = people.findByIdAndSheetId(id, sheetId).orElseThrow(PersonNotFoundException::new);
        String name = user.strip();
        var existing = watches.findByPersonIdAndUserName(person.getId(), name);
        if (on == existing.isPresent()) return person;
        if (on) {
            try {
                watches.saveAndFlush(new PersonWatch(sheetId, person.getId(), name));
            } catch (DataIntegrityViolationException e) {
                // started meanwhile in another window
                return person;
            }
        } else {
            watches.delete(existing.get());
            watches.flush();
        }
        audit.recordOnCommit((on ? "PERSON_WATCHED" : "PERSON_UNWATCHED") + " sheet=" + sheetId + " by=" + name
                + " name=" + person.getName());
        return person;
    }

    private void record(String event, String sheetId, String user, Person person, String grade, String tail) {
        audit.recordOnCommit(event + " sheet=" + sheetId + " by=" + user.strip()
                + " name=" + person.getName() + " grade=" + grade + tail);
    }

    private void requireUnused(String sheetId, String name, Long self) {
        people.findBySheetIdAndNameKey(sheetId, Person.keyOf(name))
                .filter(other -> !other.getId().equals(self))
                .ifPresent(other -> { throw new DuplicatePersonNameException(); });
        if (people.findBySheetIdAndAliasKey(sheetId, Person.keyOf(name)).isPresent()) {
            throw new DuplicatePersonNameException();
        }
    }

    /**
     * The other sheets of the sheet's tree: its topmost ancestor and every descendant of that, each once,
     * never the sheet itself.
     */
    @Transactional(readOnly = true)
    public List<String> treeOf(String sheetId) {
        return parents.treeOf(sheetId).stream().filter(at -> !at.equals(sheetId)).toList();
    }

    /** The ids of the sheet's people whose name or an alias another sheet of its tree uses as a name or alias. */
    @Transactional(readOnly = true)
    public Set<Long> clashingIds(String sheetId) {
        Set<String> used = keysUsedIn(treeOf(sheetId));
        Set<Long> ids = new HashSet<>();
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            if (clashes(person, used)) ids.add(person.getId());
        }
        return ids;
    }

    /** Whether the person's name or an alias another sheet of their sheet's tree uses as a name or alias. */
    @Transactional(readOnly = true)
    public boolean isClashing(Person person) {
        return clashes(person, keysUsedIn(treeOf(person.getSheetId())));
    }

    private static boolean clashes(Person person, Set<String> used) {
        return person.namesAndAliases().stream().map(Person::keyOf).anyMatch(used::contains);
    }

    private Set<String> keysUsedIn(List<String> sheetIds) {
        Set<String> keys = new HashSet<>();
        for (String other : sheetIds) {
            for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(other)) {
                for (String name : person.namesAndAliases()) keys.add(Person.keyOf(name));
            }
        }
        return keys;
    }

    private void requireUnusedInTree(String sheetId, String name) {
        for (String other : treeOf(sheetId)) {
            if (people.findByNameOrAlias(other, name).isPresent()) throw new DuplicatePersonNameException();
        }
    }

    private void requireContactUnused(String sheetId, String contact, Long self) {
        if (contact == null) return;
        people.findBySheetIdAndContact(sheetId, contact)
                .filter(other -> !other.getId().equals(self))
                .ifPresent(other -> { throw new DuplicatePersonContactException(); });
    }

    /** Writes the person now, so a name taken meanwhile by another user is refused by the store. */
    private Person saveUnique(Person person) {
        try {
            return people.saveAndFlush(person);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicatePersonNameException();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new PersonVersionConflictException();
        }
    }
}
