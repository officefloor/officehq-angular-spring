package net.officefloor.hq.app.person;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimComment;
import net.officefloor.hq.app.claim.ClaimCommentRepository;
import net.officefloor.hq.app.claim.ClaimLine;
import net.officefloor.hq.app.claim.ClaimLineRepository;
import net.officefloor.hq.app.claim.ClaimLog;
import net.officefloor.hq.app.claim.ClaimLogRepository;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.RetiredNumber;
import net.officefloor.hq.app.claim.RetiredNumberRepository;
import net.officefloor.hq.app.pool.PoolMember;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.pool.PoolMemberRepository;
import net.officefloor.hq.app.crew.CrewService;
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
    private final ClaimLogRepository claimLog;
    private final ClaimCommentRepository claimComments;
    private final ClaimLineRepository claimLines;
    private final RetiredNumberRepository retiredNumbers;
    private final ClaimService claimService;
    private final PoolMemberRepository poolMembers;
    private final CrewService crews;
    private final SettingsService settings;
    private final SheetService sheets;
    private final RollupService rollup;
    private final PeriodRepository periods;
    private final Audit audit;

    public PersonService(PersonRepository people, PersonStatementRepository statements,
            PersonPatronRepository patrons, PersonWatchRepository watches, ClaimRepository claims,
            ClaimLogRepository claimLog, ClaimCommentRepository claimComments, ClaimLineRepository claimLines, RetiredNumberRepository retiredNumbers,
            ClaimService claimService, PoolMemberRepository poolMembers, CrewService crews, SettingsService settings, SheetService sheets,
            RollupService rollup, PeriodRepository periods, Audit audit) {
        this.people = people;
        this.statements = statements;
        this.patrons = patrons;
        this.watches = watches;
        this.claims = claims;
        this.claimLog = claimLog;
        this.claimComments = claimComments;
        this.claimLines = claimLines;
        this.retiredNumbers = retiredNumbers;
        this.claimService = claimService;
        this.poolMembers = poolMembers;
        this.crews = crews;
        this.settings = settings;
        this.sheets = sheets;
        this.rollup = rollup;
        this.periods = periods;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
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

    /** Name the user (trimmed; blank or null: none) who may approve or reject the person's PENDING
     *  claims as a steward may. Refused (403) on an archived sheet. */
    @Transactional
    public Person setApprover(String sheetId, String user, long id, String approver) {
        if (sheets.archived(sheetId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The sheet is archived: nothing on it may change");
        }
        Person person = people.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        String name = approver == null || approver.isBlank() ? null : approver.trim();
        person.setApprover(name);
        try {
            people.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw savedByAnother(); // saved by another user at the same moment
        }
        audit.recordOnCommit("APPROVER_SET sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " user=" + (name == null ? "" : name));
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
     *  (trimmed and lower-cased, null: none) is refused when another person on the sheet has it;
     *  {@code tags} (as {@link net.officefloor.hq.app.claim.ClaimService#tagsOf} has them, null: none). */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms, String contact,
            String tags) {
        sheets.load(sheetId, user);
        sheets.requireActiveGrade(sheetId, grade);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String trimmed = name.trim();
        requireUnusedName(sheetId, null, trimmed);
        requireUnusedContact(sheetId, null, contact);
        Person person;
        try {
            Person fresh = new Person(sheetId, trimmed, grade);
            fresh.setTerms(terms);
            fresh.setContact(contact);
            fresh.setTags(tags);
            fresh.setPosition(people.nextPosition(sheetId));
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
     *  The person's own terms {@code terms} (null: none) are recorded from {@code termsFrom}, keeping
     *  those already recorded; with no from-date they become those from the beginning of time, unless
     *  they are the same as those in force on the sheet's today (then nothing is recorded). Their contact {@code contact}
     *  (null: none), refused when another person on the sheet has it; their tags become {@code tags}
     *  (null: none). */
    @Transactional
    public Person update(String sheetId, String user, long id, Long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, LocalDate termsFrom, String contact, String tags) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (version == null || version != person.getVersion()) {
            throw savedByAnother();
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String trimmed = name.trim();
        requireUnusedName(sheetId, id, trimmed);
        // a person who clashes with the sheet's tree is saved only once renamed clear of it
        Set<String> treeNames = treeNames(sheetId);
        if (person.getAliases().stream().anyMatch(a -> treeNames.contains(nameKey(a)))) {
            throw nameInUse();
        }
        requireUnusedContact(sheetId, id, contact);
        person.setName(trimmed);
        LocalDate today = sheets.today(sheetId);
        boolean termsChanged;
        if (termsFrom != null) {
            termsChanged = person.recordTerms(terms, termsFrom);
        } else if (Objects.equals(terms, person.termsOn(today))) {
            termsChanged = false;
        } else {
            termsChanged = !Objects.equals(terms, person.getTerms());
            person.setTerms(terms);
        }
        boolean contactChanged = !Objects.equals(contact, person.getContact());
        person.setContact(contact);
        person.setTags(tags);
        if (gradeFrom != null || !grade.equals(person.gradeOn(today))) {
            sheets.requireActiveGrade(sheetId, grade);
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
                (termsChanged ? termsField(person.termsOn(today)) : "") + (contactChanged ? contactField(contact) : ""));
        rollup.recordChanges(sheetId, user, before);
        return person;
    }

    /** Give each of the people of {@code ids}, in list order, {@code grade} from {@code gradeFrom}
     *  (null: the sheet's today) as saving them in the person form would, each written with its
     *  PERSON_EDITED and rollup lines; all or none. Refused (404) when any is not on the sheet. */
    @Transactional
    public int grade(String sheetId, String user, List<Long> ids, String grade, LocalDate gradeFrom) {
        Set<Long> wanted = Set.copyOf(ids);
        List<Person> chosen = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .filter(p -> wanted.contains(p.getId())).toList();
        if (chosen.size() != wanted.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person");
        }
        LocalDate today = sheets.today(sheetId);
        for (Person person : chosen) {
            RollupService.Snapshot before = rollup.snapshot(sheetId);
            if (gradeFrom != null || !grade.equals(person.gradeOn(today))) {
                sheets.requireActiveGrade(sheetId, grade);
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

    /** Merge grade {@code from} into grade {@code into}: every person on the sheet whose grade in
     *  force on the sheet's today is {@code from} is given {@code into} from the sheet's today, in
     *  list order, as saving them in the person form would. Refused (422) when the two are the same
     *  grade or {@code into} is retired, and (403) unless the user is a steward. Each person is
     *  written with their PERSON_EDITED and rollup lines; then the merge is made retroactive: every
     *  grade record of theirs on {@code from}, whatever its date, becomes {@code into} (frozen figures
     *  and those bound by a statement stay as they are); then the merge's rollup lines as a whole.
     *  Resolves to how many people were given the grade. */
    @Transactional
    public int mergeGrade(String sheetId, String user, String from, String into) {
        sheets.load(sheetId, user);
        sheets.lock(sheetId);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward may merge a grade");
        }
        if (from.equals(into)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "A grade cannot be merged into itself");
        }
        sheets.requireGrade(sheetId, from);
        sheets.requireActiveGrade(sheetId, into);
        LocalDate today = sheets.today(sheetId);
        List<Long> ids = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .filter(p -> from.equals(p.gradeOn(today))).map(Person::getId).toList();
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        audit.recordOnCommit("GRADE_MERGED sheet=" + sheetId + " by=" + user + " from=" + from + " into=" + into);
        int changed = ids.isEmpty() ? 0 : grade(sheetId, user, ids, into, null);
        // retroactive: every grade record of theirs on the merged grade becomes the other, whatever its date
        if (!ids.isEmpty()) {
            Set<Long> moved = Set.copyOf(ids);
            people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                    .filter(p -> moved.contains(p.getId())).forEach(p -> p.mergeGrade(from, into));
            try {
                people.flush();
            } catch (ObjectOptimisticLockingFailureException e) {
                throw savedByAnother(); // saved by another user at the same moment
            }
        }
        rollup.recordChanges(sheetId, user, before);
        return changed;
    }

    /** Move a person one place up (towards the top) or down the sheet's list, swapping places with
     *  the person there. Refused (422) for the top person moving up or the bottom one moving down,
     *  (404) for a person not on the sheet and (403) on an archived sheet. Answers with the sheet's
     *  people in their new order. */
    @Transactional
    public List<Person> move(String sheetId, String user, long id, boolean up) {
        if (sheets.archived(sheetId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The sheet is archived: nothing on it may change");
        }
        // held until the transaction ends, so two moves on the sheet are taken one after the other
        sheets.lock(sheetId);
        List<Person> listed = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        int at = -1;
        for (int i = 0; i < listed.size(); i++) {
            if (listed.get(i).getId() == id) {
                at = i;
            }
        }
        if (at < 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person");
        }
        int to = up ? at - 1 : at + 1;
        if (to < 0 || to >= listed.size()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    up ? "The top person cannot move up" : "The bottom person cannot move down");
        }
        Person person = listed.get(at);
        Person other = listed.get(to);
        if (person.getPosition() == other.getPosition()) {
            // two people added at the same moment share a place: number the list afresh first
            for (int i = 0; i < listed.size(); i++) {
                listed.get(i).setPosition(i + 1);
            }
        }
        long was = person.getPosition();
        person.setPosition(other.getPosition());
        other.setPosition(was);
        people.flush();
        audit.recordOnCommit("PERSON_MOVED sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " position=" + (to + 1));
        return people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
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

    /** Unretire a retired person on the sheet's today: they are no longer retired, and their
     *  retirement is kept as a past one, from its date to today. Refused (422) for anyone not retired. */
    @Transactional
    public Person unretire(String sheetId, String user, long id) {
        Person person = people.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (!person.isRetired()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "The person is not retired");
        }
        LocalDate on = sheets.today(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        person.unretire(on);
        people.flush();
        audit.recordOnCommit("PERSON_UNRETIRED sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " on=" + on);
        rollup.recordChanges(sheetId, user, before);
        return person;
    }

    /** Retire each of the people of {@code ids}, in list order, from {@code from} (null: the
     *  sheet's today) as the row's retire would, each written with its PERSON_RETIRED and rollup
     *  lines; all or none. Refused (404) when any is not on the sheet. */
    @Transactional
    public int retire(String sheetId, String user, List<Long> ids, LocalDate from) {
        Set<Long> wanted = Set.copyOf(ids);
        List<Person> chosen = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .filter(p -> wanted.contains(p.getId())).toList();
        if (chosen.size() != wanted.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person");
        }
        LocalDate on = from != null ? from : sheets.today(sheetId);
        for (Person person : chosen) {
            RollupService.Snapshot before = rollup.snapshot(sheetId);
            person.setRetiredFrom(on);
            try {
                people.flush();
            } catch (ObjectOptimisticLockingFailureException e) {
                throw savedByAnother(); // saved by another user at the same moment
            }
            audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + user
                    + " name=" + person.getName() + " from=" + on);
            rollup.recordChanges(sheetId, user, before);
        }
        return chosen.size();
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

    /** Hold each of the people of {@code ids}, in list order, from {@code from} (null: the sheet's
     *  today) to the optional {@code to} as the row's hold would, each written with its PERSON_HELD
     *  and rollup lines; all or none. Refused (404) when any is not on the sheet, and (422) for a
     *  to-date before the from-date. */
    @Transactional
    public int hold(String sheetId, String user, List<Long> ids, LocalDate from, LocalDate to) {
        Set<Long> wanted = Set.copyOf(ids);
        List<Person> chosen = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .filter(p -> wanted.contains(p.getId())).toList();
        if (chosen.size() != wanted.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person");
        }
        for (Person person : chosen) {
            hold(sheetId, user, person.getId(), from, to);
        }
        return chosen.size();
    }

    /** Flag the person, as a steward, with a reason (stored trimmed) until a date (a missing one
     *  takes the sheet's today): while the sheet's today is on or before it the row shows the reason
     *  and only a steward may open the person's claims. Refused (403) unless the user acts as a
     *  steward, and (422) for a blank reason. */
    @Transactional
    public Person flag(String sheetId, String user, long id, String reason, LocalDate until) {
        Person person = people.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward flags a person");
        }
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Give a reason for the flag");
        }
        String why = reason.trim();
        LocalDate to = until != null ? until : sheets.today(sheetId);
        person.flag(why, to);
        people.flush();
        audit.recordOnCommit("PERSON_FLAGGED sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " reason=" + why + " until=" + to);
        return person;
    }

    /** Merge a person into another person on the sheet, as a steward: the merged person's claims
     *  move to the other (numbers and dates kept, listed after the other's in the order they were
     *  added, each keeping the figure a statement bound it to), their name and aliases become the other's aliases,
     *  their patrons are added to the other's (not the other themselves, no repeats), anyone naming
     *  them as patron names the other instead (no repeats), and their pool memberships move to the
     *  other (shares added where the other is a member already). Their statements are carried to
     *  the other, whose statement count becomes their own plus the merged person's, the other's
     *  last statement staying theirs (or, having none, becoming the merged person's). The figures
     *  closed and soft-closed periods fixed for them, and their opening balances, are added to the
     *  other's. Their holds,
     *  watchers and contact go with them. Refused (404) for a person not on the sheet, (403) unless the user
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
        crews.merge(from.getId(), into.getId());

        // claims: moved, listed after the other's in the order they were added, their bindings kept
        long listed = claims.findLastListed(sheetId);
        List<Claim> moved = claims.findByPersonForUpdate(from.getId(), sheetId).stream()
                .sorted(Comparator.comparing(Claim::getId)).toList();
        for (Claim claim : moved) {
            claim.setPersonId(into.getId());
            claim.setListed(++listed);
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

        // the figures closed and soft-closed periods fixed for them, and their opening balances, carried to the other
        periods.findBySheetIdOrderByFromAsc(sheetId).forEach(p -> p.mergePerson(from.getId(), into.getId()));
        periods.flush();

        // statements carried to the other; watchers go with them
        carryStatements(sheetId, statements.findByPersonIdOrderByIdAsc(from.getId()), into);
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

    /** Transfer a person to another sheet (created first if it was never loaded), as a steward: they
     *  arrive with every grade they were given (and its from-date), their terms, contact and
     *  aliases, the id of this sheet as the one they came from, and their claims with their status,
     *  dates, notes, tags, frozen settled figures,
     *  logs, comments and lines, listed there in the order this sheet lists them; each claim that had a number
     *  takes the other sheet's next one, in the order the claims were opened (by the sheet's today
     *  when each was opened, then by the order they were added), each keeping the figure a statement
     *  bound it to; batches and references are not carried. Their statements are carried with them.
     *  They leave this sheet entirely: their patron links (both ways), pool memberships and watches are
     *  dropped, and the numbers their claims had are never given here again. Refused (404) for a
     *  person not on the sheet, (403) unless the user acts as a steward or when either sheet is
     *  archived, (422) for a retired person, this sheet's own id, or a person who has had a grade the
     *  other sheet does not have (when they are not merged there), and (409) when their name or an
     *  alias is a name or alias on another sheet of the other sheet's tree, or when their contact is
     *  already on the other sheet. When someone on the other sheet goes by their name or an alias
     *  they are merged into them there instead, as a merge does: their claims are listed after
     *  that person's, and their name and aliases join that person's aliases, that person's
     *  statement count becoming their own plus the transferred person's (their last statement staying
     *  theirs, or having none, becoming the transferred person's); answers with that person. Refused (409) when more than one person there goes by them, and (422) when that
     *  person is retired. */
    @Transactional
    public Person transfer(String sheetId, String user, long id, String toSheetId) {
        Person person = people.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward may transfer people");
        }
        String target = toSheetId.trim();
        if (target.equals(sheetId)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "A person is transferred to another sheet");
        }
        if (person.isRetired()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "A retired person cannot be transferred");
        }
        if (sheets.archived(sheetId) || sheets.archived(target)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The sheet is archived: nothing on it may change");
        }
        sheets.load(target, user);
        // held until the transaction ends, so two people cannot take the same name there at once
        sheets.lock(target);
        // someone there going by their name or an alias: they are merged into them
        List<String> names = new ArrayList<>();
        names.add(person.getName());
        names.addAll(person.getAliases());
        List<Person> matches = people.findBySheetIdOrderByPositionAscIdAsc(target).stream()
                .filter(p -> names.stream().anyMatch(p::goesBy)).toList();
        if (matches.size() > 1) {
            throw nameInUse();
        }
        Person into = matches.isEmpty() ? null : people.findForUpdate(matches.get(0).getId(), target)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Saved by another user"));
        if (into == null) {
            // a grade the person has had must be one of the other sheet's grades
            person.getGrades().forEach(g -> sheets.requireGrade(target, g.grade()));
            names.forEach(name -> requireUnusedName(target, null, name));
            requireUnusedContact(target, null, person.getContact());
        } else if (into.isRetired()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "A retired person cannot be merged");
        } else {
            names.stream().filter(name -> !into.goesBy(name)).forEach(name -> requireUnusedName(target, null, name));
        }

        RollupService.Snapshot beforeHere = rollup.snapshot(sheetId);
        RollupService.Snapshot beforeThere = rollup.snapshot(target);

        Person arrived;
        if (into != null) {
            arrived = into;
        } else {
            try {
                Person copy = person.transferredTo(target);
                copy.setArrivedOn(sheets.today(target));
                copy.setPosition(people.nextPosition(target));
                arrived = people.saveAndFlush(copy);
            } catch (DataIntegrityViolationException e) {
                throw inUse(e); // taken there by another user at the same moment
            }
        }

        // claims: copied oldest listed first, so they are listed there in the order they are here
        List<Claim> theirs = claims.findByPersonForUpdate(person.getId(), sheetId).stream()
                .sorted(Comparator.comparingLong(Claim::getListed)).toList();
        long listed = claims.findLastListed(target);
        Map<Claim, Claim> copies = new LinkedHashMap<>();
        for (Claim claim : theirs) {
            Claim copy = claim.transferredTo(target, arrived.getId());
            copy.setListed(++listed);
            if (claim.isStated()) {
                copy.bind(claim.getStatedDue());
            }
            copies.put(claim, claims.saveAndFlush(copy));
        }
        Map<Long, List<ClaimLog>> logs = new HashMap<>();
        for (Claim claim : theirs) {
            logs.put(claim.getId(), claimLog.findByClaimIdOrderByIdAsc(claim.getId()));
        }

        // numbers: the other sheet's next, in the order the claims were opened
        Map<String, String> renumbered = new HashMap<>();
        List<Claim> numbered = theirs.stream().filter(c -> c.getNumber() != null)
                .sorted(Comparator.comparing((Claim c) -> openedOn(c, logs.get(c.getId())))
                        .thenComparing(Claim::getId))
                .toList();
        for (Claim claim : numbered) {
            Claim copy = copies.get(claim);
            copy.setNumber(claimService.nextNumber(target, copy.getDueBy()));
            claims.flush();
            renumbered.put(claim.getNumber(), copy.getNumber());
        }
        for (Claim copy : copies.values()) {
            if (copy.getCorrects() != null) {
                copy.setCorrects(renumbered.get(copy.getCorrects()));
            }
            if (copy.getSplitFrom() != null) {
                copy.setSplitFrom(renumbered.get(copy.getSplitFrom()));
            }
            claimService.keepTags(copy);
        }
        claims.flush();
        for (Claim claim : theirs) {
            Long copyId = copies.get(claim).getId();
            for (ClaimLog line : logs.get(claim.getId())) {
                claimLog.save(new ClaimLog(target, copyId, line.getStatus(), line.getBy(), line.getOn(), line.getDue()));
            }
            for (ClaimComment comment : claimComments.findByClaimIdOrderByIdAsc(claim.getId())) {
                claimComments.save(new ClaimComment(target, copyId, comment.getBy(), comment.getOn(), comment.getText()));
            }
            for (ClaimLine line : claimLines.findByClaimIdOrderByIdAsc(claim.getId())) {
                claimLines.save(new ClaimLine(target, copyId, line.getLabel(), line.getQty(), line.getUnit()));
            }
        }
        claimLog.flush();
        claimComments.flush();
        claimLines.flush();
        carryStatements(target, statements.findByPersonIdOrderByIdAsc(person.getId()), arrived);

        // leaving this sheet entirely; the numbers their claims had are never given here again
        for (Claim claim : theirs) {
            if (claim.getNumber() != null) {
                retiredNumbers.save(new RetiredNumber(sheetId, claim.getNumber()));
            }
            claimLog.deleteAll(logs.get(claim.getId()));
            claimComments.deleteAll(claimComments.findByClaimIdOrderByIdAsc(claim.getId()));
            claimLines.deleteAll(claimLines.findByClaimIdOrderByIdAsc(claim.getId()));
        }
        retiredNumbers.flush();
        claimLog.flush();
        claimComments.flush();
        claimLines.flush();
        claims.deleteAll(theirs);
        claims.flush();
        patrons.deleteAll(patrons.findByPersonIdOrderByIdAsc(person.getId()));
        patrons.deleteAll(patrons.findByPatronIdOrderByIdAsc(person.getId()));
        poolMembers.deleteAll(poolMembers.findByPersonIdOrderByIdAsc(person.getId()));
        watches.deleteAll(watches.findByPersonIdOrderByIdAsc(person.getId()));
        patrons.flush();
        poolMembers.flush();
        watches.flush();
        crews.drop(person.getId());
        people.delete(person);
        try {
            people.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw savedByAnother(); // saved by another user at the same moment
        }

        audit.recordOnCommit("PERSON_TRANSFERRED sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " to=" + target);
        rollup.recordChanges(sheetId, user, beforeHere);
        if (into != null) {
            // aliases joined, as a merge joins them
            names.stream().filter(name -> !name.equals(into.getName())).forEach(into::addAlias);
            try {
                people.flush();
            } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException e) {
                throw savedByAnother(); // saved by another user at the same moment
            }
        }
        audit.recordOnCommit("PERSON_TRANSFERRED sheet=" + target + " by=" + user
                + " name=" + person.getName() + " from=" + sheetId);
        if (into != null) {
            audit.recordOnCommit("PERSON_MERGED sheet=" + target + " by=" + user
                    + " from=" + person.getName() + " into=" + into.getName());
        }
        rollup.recordChanges(target, user, beforeThere);
        return arrived;
    }

    /** Undo the transfer that brought a person to this sheet, as a steward, on the sheet's today
     *  they arrived: they are transferred back to the sheet they came from exactly as
     *  {@link #transfer} transfers them, and PERSON_TRANSFER_UNDONE is recorded here. Refused (404)
     *  for a person not on the sheet, (403) unless the user acts as a steward, and (422) for a
     *  person who did not arrive by transfer or on a later day than they arrived; answers with the
     *  person as they arrived back. */
    @Transactional
    public Person undoTransfer(String sheetId, String user, long id) {
        Person person = people.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward may undo a transfer");
        }
        if (person.getOriginSheetId() == null || person.getArrivedOn() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "The person did not arrive by transfer");
        }
        if (!person.getArrivedOn().equals(sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A transfer may be undone only on the day the person arrived");
        }
        String name = person.getName();
        String origin = person.getOriginSheetId();
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person back = transfer(sheetId, user, id, origin);
        audit.recordOnCommit("PERSON_TRANSFER_UNDONE sheet=" + sheetId + " by=" + user
                + " name=" + name + " to=" + origin);
        rollup.recordChanges(sheetId, user, before);
        return back;
    }

    /** Carry a person's statements to {@code into} on {@code sheetId}: they are taken again for
     *  {@code into} ahead of their own, so {@code into}'s count is their own plus these, and their
     *  last statement stays theirs (or, having none, becomes the last of these). */
    private void carryStatements(String sheetId, List<PersonStatement> theirs, Person into) {
        List<PersonStatement> own = statements.findByPersonIdOrderByIdAsc(into.getId());
        List<PersonStatement> carried = new ArrayList<>();
        theirs.forEach(st -> carried.add(st.carriedTo(sheetId, into.getId())));
        own.forEach(st -> carried.add(st.carriedTo(sheetId, into.getId())));
        statements.deleteAll(theirs);
        statements.deleteAll(own);
        statements.flush();
        statements.saveAll(carried);
        statements.flush();
    }

    /** The sheet's today when the claim was opened: its first OPEN log line's, else its first
     *  line's, else its date. */
    private static LocalDate openedOn(Claim claim, List<ClaimLog> log) {
        return log.stream().filter(l -> l.getStatus() == ClaimStatus.OPEN).findFirst()
                .or(() -> log.stream().findFirst())
                .map(ClaimLog::getOn)
                .orElse(claim.getDate());
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
     *  case), or anyone on another sheet of its tree has; {@code exceptId} null checks against
     *  everyone, the person's own name and aliases too. */
    private void requireUnusedName(String sheetId, Long exceptId, String name) {
        boolean taken = people.findBySheetIdAndNameIgnoreCase(sheetId, name).stream()
                .anyMatch(p -> !p.getId().equals(exceptId))
                || !people.findBySheetIdAndAliasIgnoreCase(sheetId, name).isEmpty()
                || treeNames(sheetId).contains(nameKey(name));
        if (taken) {
            throw nameInUse();
        }
    }

    /** Every name and alias of the people on the other sheets of the sheet's tree (its ancestors
     *  and descendants), as names are compared. */
    @Transactional(readOnly = true)
    public Set<String> treeNames(String sheetId) {
        Set<String> names = new HashSet<>();
        for (String other : sheets.tree(sheetId)) {
            for (Person p : people.findBySheetIdOrderByPositionAscIdAsc(other)) {
                names.add(nameKey(p.getName()));
                p.getAliases().forEach(a -> names.add(nameKey(a)));
            }
        }
        return names;
    }

    /** Whether the person's name or an alias is also a name or alias on another sheet of their tree. */
    @Transactional(readOnly = true)
    public boolean clashing(Person person) {
        return clashes(person, treeNames(person.getSheetId()));
    }

    /** Whether the person's name or an alias is among {@code treeNames} (from {@link #treeNames}). */
    public static boolean clashes(Person person, Set<String> treeNames) {
        return !treeNames.isEmpty() && (treeNames.contains(nameKey(person.getName()))
                || person.getAliases().stream().anyMatch(a -> treeNames.contains(nameKey(a))));
    }

    /** A name or alias as names are compared: ignoring case. */
    private static String nameKey(String name) {
        return name.toLowerCase(java.util.Locale.ROOT);
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
