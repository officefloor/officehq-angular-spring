package net.officefloor.hq.app.person;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.grade.GradeService;
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The people kept on a sheet. */
@Service
public class PersonService {

    private final PersonRepository people;
    private final PersonPatronRepository patrons;
    private final PersonGradeService grades;
    private final PersonTermsService terms;
    private final PersonHoldRepository holds;
    private final PersonWatchRepository watches;
    private final PersonAliasRepository aliases;
    private final PersonRetirementRepository retirements;
    private final PersonSnapshotRepository snapshots;
    private final SheetService sheets;
    private final EntityManager entityManager;
    private final History history;
    private final Audit audit;
    private final ClaimRepository claims;
    private final SettingService settings;
    private final GradeService gradeRules;

    public PersonService(PersonRepository people, PersonPatronRepository patrons, PersonGradeService grades,
            PersonTermsService terms, PersonHoldRepository holds, PersonWatchRepository watches, PersonAliasRepository aliases,
            PersonRetirementRepository retirements, PersonSnapshotRepository snapshots, SheetService sheets,
            EntityManager entityManager, History history, Audit audit, ClaimRepository claims,
            SettingService settings, GradeService gradeRules) {
        this.settings = settings;
        this.gradeRules = gradeRules;
        this.claims = claims;
        this.snapshots = snapshots;
        this.retirements = retirements;
        this.aliases = aliases;
        this.history = history;
        this.people = people;
        this.watches = watches;
        this.patrons = patrons;
        this.grades = grades;
        this.terms = terms;
        this.holds = holds;
        this.entityManager = entityManager;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The person's own terms in force on a date (null: none). */
    public Integer termsOn(Person person, LocalDate date) {
        return terms.termsOn(person, date);
    }

    /** The person's own terms in force on the sheet's today (null: none). */
    public Integer termsToday(Person person) {
        return terms.termsOn(person, sheets.today(person.getSheetId()));
    }

    /** Each person's dated own terms on the sheet, by person id. */
    public Map<Long, List<PersonTerms>> datedTerms(String sheetId) {
        return terms.bySheet(sheetId);
    }

    /** The sheet's people in list order. */
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Move a person up or down the list, swapping them with the person above or below; retired people
     *  are passed over unless {@code includeRetired} (the list shows them). Refused when there is no one
     *  to swap with: the top person cannot move up nor the bottom one down. */
    @Transactional
    public Person move(String sheetId, String user, Long id, boolean up, boolean includeRetired) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + id + " on sheet " + sheetId));
        List<Person> listed = list(sheetId);
        List<Person> shown = listed.stream()
                .filter(p -> includeRetired || !p.isRetired() || p.getId().equals(id)).toList();
        int at = shown.indexOf(person);
        int to = up ? at - 1 : at + 1;
        if (at < 0 || to < 0 || to >= shown.size()) {
            throw new MoveRefusedException(person.getName() + " cannot move " + (up ? "up" : "down"));
        }
        Person other = shown.get(to);
        person.swapPositionWith(other);
        people.saveAndFlush(person);
        people.saveAndFlush(other);
        int position = list(sheetId).indexOf(person) + 1;
        audit.recordOnCommit("PERSON_MOVED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " position=" + position);
        return person;
    }

    /** Add a person to the sheet; the grade they are given applies from the beginning of time. */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade) {
        return add(sheetId, user, name, grade, null);
    }

    /** Add a person to the sheet with their own terms (null for none); the grade they are given
     *  applies from the beginning of time. */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms) {
        return add(sheetId, user, name, grade, terms, null);
    }

    /** Add a person to the sheet with their own terms (null for none) and a contact (blank for none);
     *  the grade they are given applies from the beginning of time. */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms, String contact) {
        return add(sheetId, user, name, grade, terms, contact, null);
    }

    /** Add a person as above, carrying the tags typed comma separated (blank: none); see
     *  {@link ClaimService#tagsOf}. */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms, String contact,
            String tags) {
        return add(sheetId, user, name, grade, terms, null, contact, tags);
    }

    /** Add a person as above, their own terms (null for none) recorded from a from-date (null: from the
     *  beginning of time). */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms, LocalDate termsFrom,
            String contact, String tags) {
        LocalDate today = sheets.today(sheetId);
        gradeRules.requireChoosable(sheetId, grade);
        String trimmed = name.trim();
        requireFreeName(sheetId, trimmed, null);
        String key = contactKey(contact);
        requireFreeContact(sheetId, key, null);
        Person person = new Person(sheetId, trimmed, today);
        person.setTerms(termsFrom == null ? terms : null);
        person.setContact(key);
        person.setTags(ClaimService.tagsOf(tags));
        Person added = people.saveAndFlush(person);
        useTags(sheetId, added);
        grades.give(added, grade, null);
        if (termsFrom != null) {
            this.terms.record(added, terms, termsFrom);
        }
        Integer inForce = this.terms.termsOn(added, today);
        record("PERSON_ADDED", sheetId, user, added, grade, (inForce != null ? " terms=" + inForce : "")
                + (key != null ? " contact=" + key : ""));
        return added;
    }

    /** Change a person's name, and give them the grade from a date (blank takes the sheet's today),
     *  keeping the grades already given, and set their own terms (null for none) and contact (blank for none), from an editor that loaded the given version of them. A
     *  blank from-date with the grade in force on the sheet's today gives no grade. */
    @Transactional
    public Person update(String sheetId, String user, Long id, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, String contact) {
        return update(sheetId, user, id, version, name, grade, gradeFrom, terms, contact, null);
    }

    /** Update a person as above, giving them the tags typed comma separated (blank: none; null keeps
     *  the tags they have); see {@link ClaimService#tagsOf}. */
    @Transactional
    public Person update(String sheetId, String user, Long id, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, String contact, String tags) {
        return update(sheetId, user, id, version, name, grade, gradeFrom, terms, null, contact, tags);
    }

    /** Update a person as above, recording their own terms (null for none) from a from-date, keeping
     *  those already recorded (those for the same from-date are replaced). Without a from-date the
     *  terms are recorded from the beginning of time, unless they are the terms in force on the
     *  sheet's today, which are then left as they are. */
    @Transactional
    public Person update(String sheetId, String user, Long id, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, LocalDate termsFrom, String contact, String tags) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + id + " on sheet " + sheetId));
        if (person.getVersion() != version) {
            throw new StalePersonException(person);
        }
        // A grade left as it is, with no from-date, is not a choice of it: a retired one is kept.
        if (gradeFrom != null || !grade.equals(grades.gradeOn(id, sheets.today(sheetId)))) {
            gradeRules.requireChoosable(sheetId, grade);
        }
        String trimmed = name.trim();
        requireFreeName(sheetId, trimmed, id);
        List<String> tree = sheets.tree(sheetId);
        if (!tree.isEmpty()) {
            for (PersonAlias alias : aliases.findByPersonIdOrderByIdAsc(id)) {
                if (inUseOnTree(tree, alias.getAlias())) {
                    throw new NameInUseException(alias.getAlias());
                }
            }
        }
        String key = contactKey(contact);
        requireFreeContact(sheetId, key, id);
        person.setName(trimmed);
        LocalDate today = sheets.today(sheetId);
        Integer before = this.terms.termsOn(person, today);
        boolean termsGiven = termsFrom != null || !Objects.equals(before, terms);
        if (termsFrom != null) {
            this.terms.record(person, terms, termsFrom);
            // Terms recorded change the person, so an editor that loaded them before is stale.
            entityManager.lock(person, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        } else if (termsGiven) {
            person.setTerms(terms);
        }
        String contactBefore = person.getContact();
        person.setContact(key);
        if (tags != null) {
            person.setTags(ClaimService.tagsOf(tags));
        }
        if (gradeFrom != null || !grade.equals(grades.gradeOn(id, today))) {
            grades.give(person, grade, gradeFrom != null ? gradeFrom : today);
            // A grade given changes the person, so an editor that loaded them before is stale.
            entityManager.lock(person, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        }
        Person saved = people.saveAndFlush(person);
        useTags(sheetId, saved);
        record("PERSON_EDITED", sheetId, user, saved, grades.gradeOn(id, today),
                (termsGiven ? " terms=" + Objects.requireNonNullElse(this.terms.termsOn(saved, today), "") : "")
                + (Objects.equals(contactBefore, key) ? "" : " contact=" + (key != null ? key : "")));
        return saved;
    }

    /** Retire a person from a date; a missing date takes the sheet's today. */
    @Transactional
    public Person retire(String sheetId, String user, Long id, LocalDate from) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + id + " on sheet " + sheetId));
        LocalDate on = from != null ? from : sheets.today(sheetId);
        person.setRetiredFrom(on);
        Person saved = people.saveAndFlush(person);
        audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                + " from=" + on);
        return saved;
    }

    /** Unretire a retired person on the sheet's today: they are no longer retired, and the retirement
     *  is kept as a past one from its from-date to that today. Anyone not retired is refused. */
    @Transactional
    public Person unretire(String sheetId, String user, Long id) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + id + " on sheet " + sheetId));
        if (!person.isRetired()) {
            throw new UnretireRefusedException(person.getName() + " is not retired");
        }
        LocalDate today = sheets.today(sheetId);
        retirements.saveAndFlush(new PersonRetirement(sheetId, id, person.getRetiredFrom(), today));
        person.setRetiredFrom(null);
        Person saved = people.saveAndFlush(person);
        audit.recordOnCommit("PERSON_UNRETIRED sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                + " on=" + today);
        return saved;
    }

    /** A person's past retirements, in the order they ended. */
    public List<PersonRetirement> retirements(Long personId) {
        return retirements.findByPersonIdOrderByIdAsc(personId);
    }

    /** Each person's past retirements on the sheet that had ended by the end of a day (null: now), in
     *  the order they ended, by person id; a person with none is absent. */
    public Map<Long, List<PersonRetirement>> retirements(String sheetId, LocalDate asOf) {
        return retirements.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(r -> asOf == null || !r.getTo().isAfter(asOf))
                .collect(Collectors.groupingBy(PersonRetirement::getPersonId));
    }

    /** Keep a SNAPSHOT written for a person: the day being left, their due-total then as shown and
     *  the day the sheet's today moves to. */
    public void snapshot(String sheetId, Long personId, LocalDate day, String dueTotal, LocalDate movingTo) {
        snapshots.save(new PersonSnapshot(sheetId, personId, day, dueTotal, movingTo));
    }

    /** A person's snapshots, in the order they were written. */
    public List<PersonSnapshot> snapshots(Long personId) {
        return snapshots.findByPersonIdOrderByIdAsc(personId);
    }

    /** Each person's snapshots on the sheet as they stood at the end of a day (null: all), in the order
     *  they were written, by person id; a person with none is absent. A snapshot is written as the
     *  sheet's today moves to another day, so only those written as it moved to that day or an earlier
     *  one had been written by then. */
    public Map<Long, List<PersonSnapshot>> snapshots(String sheetId, LocalDate asOf) {
        return snapshots.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(s -> asOf == null || s.writtenBy(asOf))
                .collect(Collectors.groupingBy(PersonSnapshot::getPersonId));
    }

    /** Put a hold on a person from a date (a missing date takes the sheet's today) to an optional
     *  to-date, keeping the holds they have; refused when the to-date is before the from-date. */
    @Transactional
    public Person hold(String sheetId, String user, Long id, LocalDate from, LocalDate to) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + id + " on sheet " + sheetId));
        LocalDate on = from != null ? from : sheets.today(sheetId);
        if (to != null && to.isBefore(on)) {
            throw new HoldRefusedException("The hold's to-date " + to + " is before its from-date " + on);
        }
        holds.saveAndFlush(new PersonHold(sheetId, id, on, to, sheets.today(sheetId)));
        audit.recordOnCommit("PERSON_HELD sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " from=" + on);
        return person;
    }

    /** A steward flags a person with a reason until a date (a missing date takes the sheet's today),
     *  replacing any flag they had; anyone else, or a blank reason, is refused. */
    @Transactional
    public Person flag(String sheetId, String user, Long id, String reason, LocalDate until) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + id + " on sheet " + sheetId));
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new FlagRefusedException("Only a steward may flag a person");
        }
        if (reason == null || reason.isBlank()) {
            throw new FlagRefusedException("A flag needs a reason");
        }
        LocalDate to = until != null ? until : sheets.today(sheetId);
        person.flag(reason.trim(), to, sheets.today(sheetId));
        people.saveAndFlush(person);
        audit.recordOnCommit("PERSON_FLAGGED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " reason=" + reason.trim() + " until=" + to);
        return person;
    }

    /** Give a person an approver: the user who may approve or reject their PENDING claims as a
     *  steward may (blank for none). */
    @Transactional
    public Person setApprover(String sheetId, String user, Long id, String approver) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + id + " on sheet " + sheetId));
        String name = approver == null || approver.isBlank() ? null : approver.trim();
        person.setApprover(name);
        people.saveAndFlush(person);
        audit.recordOnCommit("APPROVER_SET sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " user=" + (name == null ? "" : name));
        return person;
    }

    /** Each person's holds on the sheet, in the order they were put on, by person id; a person with
     *  none is absent. */
    public Map<Long, List<PersonHold>> holds(String sheetId) {
        return holds.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.groupingBy(PersonHold::getPersonId));
    }

    /** A person's holds, in the order they were put on. */
    public List<PersonHold> holds(Long personId) {
        return holds.findByPersonIdOrderByIdAsc(personId);
    }

    /** A person's holds as they stood at the end of a day: those put on while the sheet's today was
     *  on or before it, in the order they were put on. */
    public List<PersonHold> holdsAsOf(Long personId, LocalDate day) {
        return holds(personId).stream().filter(hold -> hold.madeAsOf(day)).toList();
    }

    /** Each person's patron ids on the sheet, in the order they were named, by person id; a person
     *  with none is absent. */
    public Map<Long, List<Long>> patronIds(String sheetId) {
        return patrons.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.groupingBy(PersonPatron::getPersonId,
                        Collectors.mapping(PersonPatron::getPatronId, Collectors.toList())));
    }

    /** Each person's patron ids on the sheet as they stood at the end of a day, in the order they were
     *  named, by person id (null: as they stand now); a person with none is absent. */
    public Map<Long, List<Long>> patronIds(String sheetId, LocalDate asOf) {
        return patrons.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(patron -> asOf == null || patron.madeAsOf(asOf))
                .collect(Collectors.groupingBy(PersonPatron::getPersonId,
                        Collectors.mapping(PersonPatron::getPatronId, Collectors.toList())));
    }

    /** Each person's holds on the sheet as they stood at the end of a day, in the order they were put
     *  on, by person id (null: as they stand now); a person with none is absent. */
    public Map<Long, List<PersonHold>> holds(String sheetId, LocalDate asOf) {
        return holds.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(hold -> asOf == null || hold.madeAsOf(asOf))
                .collect(Collectors.groupingBy(PersonHold::getPersonId));
    }

    /** The ids of the people a user watched on the sheet at the end of a day (null: now). */
    public Set<Long> watchedIds(String sheetId, String user, LocalDate asOf) {
        Set<Long> now = watchedIds(sheetId, user);
        return asOf == null ? now : history.watched(sheetId, user, now, asOf);
    }

    /** The sheet's people as they stood at the end of a day, in the order they were added: those added
     *  by then (those since merged into another included), each with the name, own terms, retirement and
     *  contact they had then (null: as they stand now), and a flag put on since left out. */
    public List<Person> list(String sheetId, LocalDate asOf) {
        if (asOf == null) {
            return list(sheetId);
        }
        Map<Long, History.PersonThen> then = history.people(sheetId, asOf);
        return people.findEverBySheetId(sheetId).stream().filter(person -> person.onSheetAsOf(asOf)).map(person -> {
            History.PersonThen was = then.get(person.getId());
            return (was == null ? person : person.asItWas(was.name(), was.terms(), was.retiredFrom(), was.contact()))
                    .flaggedAsOf(asOf);
        }).toList();
    }

    /** A person's patron ids, in the order they were named. */
    public List<Long> patronIds(Long personId) {
        return patrons.findByPersonIdOrderByIdAsc(personId).stream().map(PersonPatron::getPatronId).toList();
    }

    /** Name another person on the sheet as a person's patron; refused for the person themselves and
     *  for someone already their patron. */
    @Transactional
    public Person addPatron(String sheetId, String user, Long id, Long patronId) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + id + " on sheet " + sheetId));
        Person patron = people.findByIdAndSheetId(patronId, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + patronId + " on sheet " + sheetId));
        if (person.getId().equals(patron.getId())) {
            throw new PatronRefusedException(person.getName() + " cannot be their own patron");
        }
        if (patrons.existsByPersonIdAndPatronId(person.getId(), patron.getId())) {
            throw new PatronRefusedException(patron.getName() + " is already a patron of " + person.getName());
        }
        patrons.saveAndFlush(new PersonPatron(sheetId, person.getId(), patron.getId(), sheets.today(sheetId)));
        audit.recordOnCommit("PATRON_ADDED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " patron=" + patron.getName());
        return person;
    }

    /** Give a person an alias, trimmed; refused when blank, or when it equals, as names are compared,
     *  any name or alias on the sheet or on any other sheet of its tree. */
    @Transactional
    public Person addAlias(String sheetId, String user, Long id, String alias) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + id + " on sheet " + sheetId));
        String trimmed = alias == null ? "" : alias.trim();
        if (trimmed.isEmpty()) {
            throw new AliasRefusedException("An alias may not be blank");
        }
        if (people.nameInUse(sheetId, trimmed, null) || aliases.aliasInUse(sheetId, trimmed)
                || inUseOnTree(sheets.tree(sheetId), trimmed)) {
            throw new NameInUseException(trimmed);
        }
        aliases.saveAndFlush(new PersonAlias(sheetId, id, trimmed, sheets.today(sheetId)));
        audit.recordOnCommit("PERSON_ALIAS_ADDED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " alias=" + trimmed);
        return person;
    }

    /** A person's aliases, sorted as names are compared. */
    public List<String> aliases(Long personId) {
        return sorted(aliases.findByPersonIdOrderByIdAsc(personId).stream());
    }

    /** Each person's aliases on the sheet as they stood at the end of a day, sorted as names are
     *  compared, by person id (null: as they stand now); a person with none is absent. */
    public Map<Long, List<String>> aliases(String sheetId, LocalDate asOf) {
        return aliases.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(alias -> asOf == null || alias.madeAsOf(asOf))
                .collect(Collectors.groupingBy(PersonAlias::getPersonId,
                        Collectors.collectingAndThen(Collectors.toList(), list -> sorted(list.stream()))));
    }

    private static List<String> sorted(java.util.stream.Stream<PersonAlias> stream) {
        return stream.map(PersonAlias::getAlias)
                .sorted(java.util.Comparator.comparing((String a) -> a.toLowerCase(Locale.ROOT))
                        .thenComparing(java.util.Comparator.naturalOrder()))
                .toList();
    }

    /** Start or stop a user watching a person; a person already in that state is left as they are,
     *  writing nothing. */
    @Transactional
    public Person watch(String sheetId, String user, Long id, boolean on) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + id + " on sheet " + sheetId));
        var existing = watches.findByPersonIdAndUser(id, user);
        if (on && existing.isEmpty()) {
            watches.saveAndFlush(new PersonWatch(sheetId, id, user));
            history.watchSet(sheetId, id, user, true);
            audit.recordOnCommit("PERSON_WATCHED sheet=" + sheetId + " by=" + user + " name=" + person.getName());
        } else if (!on && existing.isPresent()) {
            watches.delete(existing.get());
            watches.flush();
            history.watchSet(sheetId, id, user, false);
            audit.recordOnCommit("PERSON_UNWATCHED sheet=" + sheetId + " by=" + user + " name=" + person.getName());
        }
        return person;
    }

    /** The ids of the people a user watches on the sheet. */
    public Set<Long> watchedIds(String sheetId, String user) {
        return watches.findBySheetIdAndUser(sheetId, user).stream().map(PersonWatch::getPersonId)
                .collect(Collectors.toSet());
    }

    /** Whether a user watches a person. */
    public boolean watches(Long personId, String user) {
        return watches.findByPersonIdAndUser(personId, user).isPresent();
    }

    /** A person's watchers, in the order they started watching. */
    public List<String> watchers(Long personId) {
        return watches.findByPersonIdOrderByIdAsc(personId).stream().map(PersonWatch::getUser).toList();
    }

    private void record(String event, String sheetId, String user, Person person, String grade, String terms) {
        audit.recordOnCommit(event + " sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " grade=" + grade + terms);
    }

    /** Keep the person's tags on the sheet's list of the tags ever used. */
    private void useTags(String sheetId, Person person) {
        person.getTags().forEach(tag -> claims.useTag(sheetId, tag));
    }

    /** A contact as kept: trimmed and lower-cased; null for a blank one. */
    private static String contactKey(String contact) {
        if (contact == null || contact.isBlank()) {
            return null;
        }
        return contact.trim().toLowerCase(Locale.ROOT);
    }

    private void requireFreeContact(String sheetId, String contact, Long exceptId) {
        if (contact != null && people.contactInUse(sheetId, contact, exceptId)) {
            throw new ContactInUseException(contact);
        }
    }

    /** Refuse a name used, as names are compared, by another person on the sheet (not {@code exceptId}),
     *  by an alias on the sheet, or as a name or alias on any other sheet of its tree. */
    private void requireFreeName(String sheetId, String name, Long exceptId) {
        if (people.nameInUse(sheetId, name, exceptId) || aliases.aliasInUse(sheetId, name)
                || inUseOnTree(sheets.tree(sheetId), name)) {
            throw new NameInUseException(name);
        }
    }

    /** Whether a name or alias on any of the tree's sheets is this name, as names are compared. */
    private boolean inUseOnTree(List<String> tree, String name) {
        return !tree.isEmpty() && (people.nameInUseOn(tree, name) || aliases.aliasInUseOn(tree, name));
    }

    /** The names and aliases on the other sheets of the sheet's tree, lower-cased. */
    private Set<String> treeKeys(String sheetId) {
        List<String> tree = sheets.tree(sheetId);
        if (tree.isEmpty()) {
            return Set.of();
        }
        Set<String> keys = new java.util.HashSet<>(people.nameKeysOn(tree));
        keys.addAll(aliases.aliasKeysOn(tree));
        return keys;
    }

    private static boolean clashes(Set<String> treeKeys, String name, List<String> theirAliases) {
        return treeKeys.contains(name.toLowerCase(Locale.ROOT))
                || theirAliases.stream().anyMatch(alias -> treeKeys.contains(alias.toLowerCase(Locale.ROOT)));
    }

    /** Whether the person clashes: their name or an alias of theirs is used as a name or alias on
     *  another sheet of their sheet's tree. */
    public boolean clashing(Person person) {
        return clashes(treeKeys(person.getSheetId()), person.getName(), aliases(person.getId()));
    }

    /** The ids of the people on the sheet who clash, as {@link #clashing(Person)} judges, with the
     *  names and aliases given them. */
    public Set<Long> clashingIds(String sheetId, List<Person> them, Map<Long, List<String>> theirAliases) {
        Set<String> keys = treeKeys(sheetId);
        if (keys.isEmpty()) {
            return Set.of();
        }
        return them.stream().filter(p -> clashes(keys, p.getName(), theirAliases.getOrDefault(p.getId(), List.of())))
                .map(Person::getId).collect(Collectors.toSet());
    }
}
