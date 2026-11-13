package net.officefloor.hq.app.person;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.SheetTag;
import net.officefloor.hq.app.claim.SheetTagRepository;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.crew.Crew;
import net.officefloor.hq.app.crew.CrewRepository;
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.pool.Pool;
import net.officefloor.hq.app.pool.PoolRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the people on each sheet. Names arrive validated (not blank) and are stored trimmed; a name
 * is unique on the sheet, compared without regard to case, and is not used as a name or alias on any other sheet
 * of the sheet's tree (its ancestors and descendants).
 */
@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetRepository sheets;
    private final StatementRepository statements;
    private final PersonSnapshotRepository snapshots;
    private final PatronRepository patrons;
    private final WatchRepository watches;
    private final RoleService roles;
    private final Rollup rollup;
    private final Audit audit;
    private final ClaimRepository claims;
    private final PoolRepository pools;
    private final CrewRepository crews;
    private final PeriodRepository periods;
    private final SheetService sheetService;
    private final SheetTagRepository sheetTags;
    private final PersonMergeRepository merges;
    private final History history;
    private final JdbcTemplate jdbc;

    public PersonService(PersonRepository people, SheetRepository sheets, StatementRepository statements,
            PatronRepository patrons, WatchRepository watches, Rollup rollup, Audit audit, RoleService roles,
            ClaimRepository claims, PoolRepository pools, PeriodRepository periods, SheetService sheetService,
            SheetTagRepository sheetTags, PersonSnapshotRepository snapshots, CrewRepository crews,
            PersonMergeRepository merges, History history, JdbcTemplate jdbc) {
        this.merges = merges;
        this.history = history;
        this.jdbc = jdbc;
        this.crews = crews;
        this.snapshots = snapshots;
        this.sheetTags = sheetTags;
        this.sheetService = sheetService;
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
        return people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
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

    /** The sheet's snapshots by person id, each person's oldest first. */
    @Transactional(readOnly = true)
    public Map<Long, List<PersonSnapshot>> snapshots(String sheetId) {
        return snapshots.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.groupingBy(PersonSnapshot::getPersonId));
    }

    /** The person's snapshots, oldest first. */
    @Transactional(readOnly = true)
    public List<PersonSnapshot> snapshotsOf(long personId) {
        return snapshots.findByPersonIdOrderByIdAsc(personId);
    }

    /** Compare two snapshot dates: one line per person in list order, `<name> <then> <now>`, then their
     *  due-total in the snapshot written when the sheet's today moved on from the from-date and now that of
     *  the to-date (their due-total now when there is no to-date); a figure is empty when there is no such
     *  snapshot (n/a when the due-total was), and each line is trimmed. */
    @Transactional(readOnly = true)
    public List<String> compareSnapshots(String sheetId, LocalDate from, LocalDate to) {
        Map<Long, List<PersonSnapshot>> bySheet = snapshots(sheetId);
        List<String> lines = new ArrayList<>();
        rollup.take(sheetId).people().forEach((personId, figures) -> {
            List<PersonSnapshot> own = bySheet.getOrDefault(personId, List.of());
            String then = snapshotFigure(own, from);
            String now = to == null ? figure(figures.dueTotal()) : snapshotFigure(own, to);
            lines.add((figures.name() + " " + then + " " + now).trim());
        });
        return lines;
    }

    /** The due-total of the latest snapshot of a day being left, empty when there is none. */
    private static String snapshotFigure(List<PersonSnapshot> own, LocalDate day) {
        PersonSnapshot found = null;
        for (PersonSnapshot s : own) {
            if (day != null && s.getDay().equals(day)) {
                found = s;
            }
        }
        return found == null ? "" : figure(found.getDueTotal());
    }

    private static String figure(BigDecimal value) {
        return value == null ? "n/a" : value.toPlainString();
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
        return add(sheetId, user, name, grade, terms, contact, List.of());
    }

    /** Add a person carrying the tags (normalised); each counts among the tags used on the sheet. */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms, String contact,
            List<String> tags) {
        requireSheet(sheetId);
        requireNotRetired(sheetId, grade);
        requireFreeName(sheetId, name.trim(), null);
        requireFreeInTree(sheetId, List.of(name.trim()), null);
        String key = contactKey(contact);
        requireFreeContact(sheetId, key, null);
        Rollup.Snapshot before = rollup.take(sheetId);
        Person person = new Person(sheetId, name.trim(), grade);
        person.setTerms(terms);
        person.setContact(key);
        person.setTags(tags);
        person = saved(person);
        recordTags(sheetId, tags);
        audit("PERSON_ADDED", person, user, today(sheetId), (terms == null ? "" : " terms=" + terms)
                + (key == null ? "" : " contact=" + key));
        rollup.recordChanges(sheetId, user, before);
        return person;
    }

    /** A line of the import box: its number, and the person form's fields. */
    private record ImportLine(int number, String name, String grade, Integer terms, String contact) {
    }

    /** What an import did: the people it added and those it updated, each in line order. */
    public record ImportResult(List<Person> added, List<Person> updated) {
    }

    /** The import box: lines of name,grade,terms,contact (the last two may be absent or blank; a blank
     *  grade is the sheet's default grade), blank lines
     *  skipped. A line whose name is a name or alias on the sheet (ignoring case) updates that person; any
     *  other adds one. Every line is checked first with the person form's rules, a name, person or contact
     *  repeated within the import too (ignoring case); a refused line refuses the import, changing no one and
     *  writing nothing. Otherwise each is applied in order: an add exactly as the form adds, an update as the
     *  form saves, keeping their name, recording the grade from the sheet's today, and setting their terms
     *  and contact only when the line gives them. */
    @Transactional
    public ImportResult importPeople(String sheetId, String user, String text) {
        String defaultGrade = defaultGrade(sheetId);
        List<String> retired = sheets.findById(sheetId).map(Sheet::getRetiredGrades).orElse(List.of());
        List<String> grades = sheets.findById(sheetId).map(Sheet::getGrades).orElse(List.of());
        List<ImportLine> lines = new ArrayList<>();
        Map<ImportLine, Person> matched = new HashMap<>();
        Set<String> names = new HashSet<>();
        Set<Long> matchedIds = new HashSet<>();
        Set<String> contacts = new HashSet<>();
        Set<String> tree = namesInTree(sheetId, null);
        String[] raw = text == null ? new String[0] : text.split("\\r?\\n");
        for (int i = 0; i < raw.length; i++) {
            if (raw[i].isBlank()) {
                continue;
            }
            ImportLine line = parseImport(i + 1, raw[i], defaultGrade);
            if (!grades.contains(line.grade())) {
                throw importRefused(line.number(), "choose a grade of " + String.join(", ", grades) + ".");
            }
            if (retired.contains(line.grade())) {
                throw importRefused(line.number(), "grade " + line.grade() + " is retired.");
            }
            String lower = line.name().toLowerCase(Locale.ROOT);
            if (!names.add(lower)) {
                throw importRefused(line.number(), "the name " + line.name() + " is already in this import.");
            }
            Person match = people.findByNameOrAlias(sheetId, line.name()).orElse(null);
            if (match != null) {
                if (!matchedIds.add(match.getId())) {
                    throw importRefused(line.number(), match.getName() + " is already in this import.");
                }
                if (Stream.concat(Stream.of(match.getName()), match.getAliases().stream())
                        .anyMatch(n -> tree.contains(n.toLowerCase(Locale.ROOT)))) {
                    throw importRefused(line.number(), "the name " + match.getName() + " is already in use.");
                }
                matched.put(line, match);
            } else if (tree.contains(lower)) {
                throw importRefused(line.number(), "the name " + line.name() + " is already in use.");
            }
            if (line.contact() != null) {
                if (!contacts.add(line.contact())) {
                    throw importRefused(line.number(), "the contact " + line.contact() + " is already in this import.");
                }
                if (people.contactTaken(sheetId, line.contact(), match == null ? null : match.getId())) {
                    throw importRefused(line.number(), "the contact " + line.contact() + " is already in use.");
                }
            }
            lines.add(line);
        }
        if (lines.isEmpty()) {
            throw new PersonImportException("Paste at least one line of name,grade,terms,contact.");
        }
        List<Person> added = new ArrayList<>();
        List<Person> updated = new ArrayList<>();
        for (ImportLine line : lines) {
            Person match = matched.get(line);
            try {
                if (match == null) {
                    added.add(add(sheetId, user, line.name(), line.grade(), line.terms(), line.contact()));
                } else {
                    Person now = people.findByIdAndSheetId(match.getId(), sheetId)
                            .orElseThrow(PersonNotFoundException::new);
                    updated.add(update(sheetId, user, now.getId(), now.getVersion(), now.getName(), line.grade(),
                            null, line.terms() != null ? line.terms() : now.getTerms(), null,
                            line.contact() != null ? line.contact() : now.getContact(), null, true));
                }
            } catch (PersonNameTakenException | PersonContactTakenException | PersonNotFoundException
                    | PersonVersionConflictException e) {
                // changed by another user at the same moment
                throw importRefused(line.number(), e.getMessage());
            }
        }
        return new ImportResult(added, updated);
    }

    private static ImportLine parseImport(int number, String raw, String defaultGrade) {
        String[] f = raw.split(",", 4);
        String name = f[0].trim();
        if (name.isEmpty()) {
            throw importRefused(number, "enter a name.");
        }
        if (name.length() > 200) {
            throw importRefused(number, "the name is too long.");
        }
        String grade = f.length > 1 ? f[1].trim() : "";
        if (grade.isEmpty()) {
            grade = defaultGrade;
        }
        if (!grade.matches("[A-Z]{1,3}")) {
            throw importRefused(number, "choose a grade of one to three capital letters.");
        }
        String terms = f.length > 2 ? f[2].trim() : "";
        if (!terms.isEmpty() && !terms.matches("\\d{1,6}")) {
            throw importRefused(number, "enter the terms as a whole number of days, or leave them blank.");
        }
        String contact = f.length > 3 ? f[3].trim() : "";
        if (contact.length() > 200) {
            throw importRefused(number, "enter a contact of at most 200 characters.");
        }
        return new ImportLine(number, name, grade, terms.isEmpty() ? null : Integer.valueOf(terms),
                contactKey(contact));
    }

    private static PersonImportException importRefused(int number, String why) {
        return new PersonImportException("Line " + number + ": " + why);
    }

    @Transactional
    /** Change a person's name, and record the grade from a date (the sheet's today when none is given),
     *  keeping the grades already recorded; a save leaving the grade as it is on the sheet's today, with no
     *  from-date, records nothing. Their own terms from the beginning of time are set to those given (none
     *  clears them), and their contact to the one given (blank clears it). */
    public Person update(String sheetId, String user, long personId, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, String contact) {
        return update(sheetId, user, personId, version, name, grade, gradeFrom, terms, null, contact, null, true);
    }

    /** As {@link #update}, recording their own terms (none: none of their own) from a date, keeping those
     *  already recorded (replacing those from that same date), or from the beginning of time when no date is
     *  given; and setting the tags they carry (normalised; none: kept as they are). */
    @Transactional
    public Person update(String sheetId, String user, long personId, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, LocalDate termsFrom, String contact, List<String> tags) {
        return update(sheetId, user, personId, version, name, grade, gradeFrom, terms, termsFrom, contact, tags,
                true);
    }

    /** As the person form saves, which (form) also refuses a name or alias some other sheet of the tree
     *  uses, so a person who clashes keeps being refused until a rename leaves them clashing with no one. */
    private Person update(String sheetId, String user, long personId, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, LocalDate termsFrom, String contact, List<String> tags,
            boolean form) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (person.getVersion() != version) {
            throw new PersonVersionConflictException(person);
        }
        requireFreeName(sheetId, name.trim(), person.getId());
        if (form) {
            List<String> names = new ArrayList<>();
            names.add(name.trim());
            names.addAll(person.getAliases());
            requireFreeInTree(sheetId, names, null);
        }
        String key = contactKey(contact);
        requireFreeContact(sheetId, key, person.getId());
        LocalDate today = today(sheetId);
        // a save leaving the grade as it is, with no from-date, is not a choice of it
        if (gradeFrom != null || !grade.equals(person.gradeOn(today))) {
            requireNotRetired(sheetId, grade);
        }
        Rollup.Snapshot before = rollup.take(sheetId);
        person.setName(name.trim());
        // the terms entered differing from those in force on today count as a change, even when the
        // stored terms they replace were already the same (dated terms still in force over them)
        boolean termsChanged = !Objects.equals(terms, person.termsOn(today)) | person.setTerms(terms, termsFrom);
        boolean contactChanged = !Objects.equals(key, person.getContact());
        person.setContact(key);
        if (tags != null) {
            person.setTags(tags);
            recordTags(sheetId, tags);
        }
        if (gradeFrom != null || !grade.equals(person.gradeOn(today))) {
            person.setGrade(grade, gradeFrom != null ? gradeFrom : today);
        }
        Person updated = saved(person);
        audit("PERSON_EDITED", updated, user, today, (termsChanged ? " terms=" + Objects.toString(updated.termsOn(today), "") : "")
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
        List<Person> picked = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .filter(p -> chosen.contains(p.getId())).toList();
        if (picked.size() != chosen.size()) {
            throw new PersonNotFoundException();
        }
        return picked.stream().map(p -> update(sheetId, user, p.getId(), p.getVersion(), p.getName(), grade, from,
                p.getTerms(), null, p.getContact(), null, false)).toList();
    }

    /** Merge a grade into another, by a steward only, and refused for the same grade twice: every person
     *  whose grade in force on the sheet's today is the merged grade is given the other from the sheet's
     *  today, in list order, exactly as the person form would, then every grade record of theirs on the merged
     *  grade becomes the other (so all their claims, whatever their date, use the other's rates, terms and
     *  thresholds), then the figures the whole merge moved are recorded; the people changed, in list order. */
    @Transactional
    public List<Person> mergeGrade(String sheetId, String user, String from, String into) {
        requireSheet(sheetId);
        if (!roles.isSteward(sheetId, user)) {
            throw new MergeRefusedException("Only a steward may merge grades.");
        }
        if (from.equals(into)) {
            throw new MergeRefusedException("Choose two different grades to merge.");
        }
        List<String> grades = sheets.findById(sheetId).map(Sheet::getGrades).orElse(List.of());
        if (!grades.contains(from) || !grades.contains(into)) {
            throw new MergeRefusedException("Choose two of the sheet's grades to merge.");
        }
        LocalDate today = today(sheetId);
        Rollup.Snapshot before = rollup.take(sheetId);
        audit.recordOnCommit("GRADE_MERGED sheet=" + sheetId + " by=" + user + " from=" + from + " into=" + into);
        List<Person> merged = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .filter(p -> from.equals(p.gradeOn(today)))
                .map(p -> update(sheetId, user, p.getId(), p.getVersion(), p.getName(), into, today,
                        p.getTerms(), null, p.getContact(), null, false))
                .map(p -> {
                    p.regrade(from, into);
                    return saved(p);
                })
                .toList();
        // then the figures the whole merge moved
        rollup.recordChanges(sheetId, user, before);
        return merged;
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
        if (people.nameTaken(sheetId, trimmed, null)
                || namesInTree(sheetId, null).contains(trimmed.toLowerCase(Locale.ROOT))) {
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
     *  dates kept, listed after the other's in the order they were added, each keeping any figure a statement
     *  bound); their statements count as the other's too, the other's last statement staying their last (or,
     *  having none, the merged person's last becoming theirs); their name and aliases become the other's aliases; their patrons are added to the other's (not
     *  themselves, no repeats), anyone naming them as patron names the other instead (no repeats), and their
     *  pool memberships move to the other (shares added, at most 100.00, where the other is already a member), their
     *  crew places move to the other (leading what they led, a membership dropped where the other is in the crew),
     *  as do their figures for closed periods. Their holds, watchers and contact are dropped, and
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
        // kept so the merge can be undone: the merged person as they stand now, and the claims that came from them
        PersonMerge record = new PersonMerge(sheetId, from.getId(), into.getId(), user, today(sheetId),
                history.state(from));
        List<Claim> moving = new ArrayList<>(
                claims.findForUpdateBySheetIdAndPersonIdOrderByListOrderDesc(sheetId, from.getId()));
        moving.sort(Comparator.comparing(Claim::getId));
        moving.forEach(c -> record.getClaimIds().add(c.getId()));
        long order = claims.maxListOrder(sheetId);
        for (Claim claim : moving) {
            claim.setPersonId(into.getId());
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
        for (Crew crew : crews.findBySheetIdOrderByIdAsc(sheetId)) {
            crew.movePerson(from.getId(), into.getId());
        }
        crews.flush();
        for (Period period : periods.findBySheetIdOrderByFromDateAsc(sheetId)) {
            period.moveClosedTotal(from.getId(), into.getId());
        }
        watches.deleteAll(watches.findByPersonIdOrderByIdAsc(from.getId()));
        carryStatements(from, into);
        carrySnapshots(from, into);
        // everything naming them goes before they do
        people.flush();

        List<String> names = new ArrayList<>();
        names.add(from.getName());
        names.addAll(from.getAliases());
        people.delete(from);
        people.flush();
        names.forEach(into::addAlias);
        Person merged = people.saveAndFlush(into);
        merges.saveAndFlush(record);
        audit.recordOnCommit("PERSON_MERGED sheet=" + sheetId + " by=" + user + " from=" + from.getName()
                + " into=" + merged.getName());
        rollup.recordChanges(sheetId, user, before);
        return merged;
    }

    /** A steward transfers a person to another sheet by its id. They arrive there with every grade they were
     *  given, their terms, contact and aliases, and their claims (status, dates, notes, frozen figures, tags
     *  and logs kept, listed in the order they were); each claim that had a number takes the next number of
     *  the other sheet, in the order the claims were opened (by the sheet's today then, then by the order they
     *  were added). Figures a statement bound are kept, as are their statements (counting as those of whom they
     *  are merged into too, whose last statement stays their last unless they have none), and references
     *  are not carried. They leave this sheet entirely: their patron links, pool memberships, crew memberships (a crew they lead
     *  stays, with no lead), watches,
     *  holds and fixed period figures are dropped. Refused for anyone but a steward, a retired
     *  person, this sheet's own id, an archived sheet, and when their name, an alias or their contact is already
     *  on the other sheet or their name or an alias on another sheet of its tree; a sheet never loaded is started.
     *  When their name or an alias is, ignoring case, that of one person on the other sheet, they are instead
     *  merged into that person there exactly as a merge would (claims listed after theirs, names joining their
     *  aliases, contact dropped); refused when it is that of more than one, or that person is retired. Answers
     *  the person as they arrived, or whom they were merged into. */
    @Transactional
    public Person transfer(String sheetId, String user, long personId, String toSheetId) {
        Person from = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (!roles.isSteward(sheetId, user)) {
            throw new TransferRefusedException("Only a steward may transfer people.");
        }
        if (from.isRetired()) {
            throw new TransferRefusedException("A retired person cannot be transferred.");
        }
        String to = toSheetId == null ? "" : toSheetId.trim();
        if (to.isEmpty()) {
            throw new TransferRefusedException("Enter the id of the sheet to transfer to.");
        }
        if (to.equals(sheetId)) {
            throw new TransferRefusedException("A person cannot be transferred to their own sheet.");
        }
        // a sheet never loaded starts empty, as loading it would
        Sheet target = sheets.findById(to).orElseGet(() -> sheetService.load(to, user));
        if (target.isArchived()) {
            throw new TransferRefusedException("Sheet " + to + " is archived.");
        }
        List<String> names = new ArrayList<>();
        names.add(from.getName());
        names.addAll(from.getAliases());
        // someone there with one of their names or aliases is whom they merge into
        Map<Long, Person> matched = new LinkedHashMap<>();
        for (String name : names) {
            people.findByNameOrAlias(to, name).ifPresent(p -> matched.putIfAbsent(p.getId(), p));
        }
        if (matched.size() > 1) {
            throw new TransferRefusedException("The names of " + from.getName() + " belong to more than one person"
                    + " on sheet " + to + ".");
        }
        Person into = matched.values().stream().findFirst().orElse(null);
        if (into != null && into.isRetired()) {
            throw new TransferRefusedException(into.getName() + " on sheet " + to + " is retired, so "
                    + from.getName() + " cannot be merged into them.");
        }
        for (String name : names) {
            if ((into == null && people.nameTaken(to, name, null))
                    || namesInTree(to, from.getId()).contains(name.toLowerCase(Locale.ROOT))) {
                throw new TransferRefusedException("The name " + name + " is already in use on sheet " + to + ".");
            }
        }
        // a merged person's contact is dropped, as a merge drops it
        if (into == null && from.getContact() != null && people.contactTaken(to, from.getContact(), null)) {
            throw new TransferRefusedException("The contact " + from.getContact() + " is already in use on sheet "
                    + to + ".");
        }

        Rollup.Snapshot beforeHere = rollup.take(sheetId);
        Rollup.Snapshot beforeThere = rollup.take(to);
        Person arrived = into != null ? into : saved(from.copyTo(to, target.getToday()));
        List<Claim> leaving = new ArrayList<>(
                claims.findForUpdateBySheetIdAndPersonIdOrderByListOrderDesc(sheetId, from.getId()));
        List<Claim> listed = new ArrayList<>(leaving);
        listed.sort(Comparator.comparingLong(Claim::getListOrder));
        long order = claims.maxListOrder(to);
        Map<Claim, Claim> copies = new LinkedHashMap<>();
        for (Claim claim : listed) {
            copies.put(claim, claim.copyTo(to, arrived.getId(), ++order));
        }
        List<Claim> numbered = new ArrayList<>(listed.stream().filter(c -> c.getNumber() != null).toList());
        numbered.sort(Comparator.comparing(Claim::openedOn, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Claim::getId));
        Map<String, String> renumbered = new HashMap<>();
        for (Claim claim : numbered) {
            String number = sheetService.nextClaimNumber(to, claim.getDueBy());
            copies.get(claim).setNumber(number);
            renumbered.put(claim.getNumber(), number);
        }
        for (Claim copy : copies.values()) {
            // a correction names the claim it corrects, a split the claim it came from, by their numbers there
            if (copy.getCorrects() != null) {
                copy.setCorrects(renumbered.getOrDefault(copy.getCorrects(), copy.getCorrects()));
            }
            if (copy.getSplitFrom() != null) {
                copy.setSplitFrom(renumbered.getOrDefault(copy.getSplitFrom(), copy.getSplitFrom()));
            }
        }
        claims.saveAll(copies.values());

        for (Patron patron : patrons.findBySheetIdOrderByIdAsc(sheetId)) {
            if (patron.getPersonId().equals(from.getId()) || patron.getPatronId().equals(from.getId())) {
                patrons.delete(patron);
            }
        }
        for (Pool pool : pools.findBySheetIdOrderByIdAsc(sheetId)) {
            pool.removeMember(from.getId());
        }
        for (Crew crew : crews.findBySheetIdOrderByIdAsc(sheetId)) {
            if (from.getId().equals(crew.getLeadId())) {
                crew.dropLead();
            } else {
                crew.removeMember(from.getId());
            }
        }
        crews.flush();
        for (Period period : periods.findBySheetIdOrderByFromDateAsc(sheetId)) {
            period.dropClosedTotal(from.getId());
        }
        watches.deleteAll(watches.findByPersonIdOrderByIdAsc(from.getId()));
        carryStatements(from, arrived);
        carrySnapshots(from, arrived);
        claims.deleteAll(leaving);
        // everything naming them goes before they do
        people.flush();
        people.delete(from);
        people.flush();
        if (into != null) {
            names.forEach(into::addAlias);
            arrived = people.saveAndFlush(into);
            // a merge by transfer is the last merge into them, and cannot be undone
            merges.save(new PersonMerge(to, from.getId(), into.getId(), user, target.getToday(), null));
        }

        audit.recordOnCommit("PERSON_TRANSFERRED sheet=" + sheetId + " by=" + user + " name=" + from.getName()
                + " to=" + to);
        rollup.recordChanges(sheetId, user, beforeHere);
        audit.recordOnCommit("PERSON_TRANSFERRED sheet=" + to + " by=" + user + " name=" + from.getName()
                + " from=" + sheetId);
        if (into != null) {
            audit.recordOnCommit("PERSON_MERGED sheet=" + to + " by=" + user + " from=" + from.getName()
                    + " into=" + arrived.getName());
        }
        rollup.recordChanges(to, user, beforeThere);
        return arrived;
    }

    /** The steward who made the last merge into a person undoes it on the same sheet's today it was made: the
     *  merged person is back, with the same id, at the end of the list, with what they had before it (not their
     *  holds, watchers or contact when another now has it), with the claims that came from them as those are now,
     *  and with their own name and aliases, which leave the other's. Patron, pool, crew, period, statement and
     *  snapshot changes the merge made stay. Refused for anyone else, on a later day, for a person whose last
     *  merge into them is undone or was by transfer (or who has none), and when one of the merged person's names
     *  is in use on the sheet's tree other than as the other's alias. */
    @Transactional
    public Person undoMerge(String sheetId, String user, long personId) {
        Sheet sheet = sheets.findForUpdateById(sheetId).orElseThrow(SheetNotFoundException::new);
        Person into = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (!roles.isSteward(sheetId, user)) {
            throw new MergeRefusedException("Only a steward may undo a merge.");
        }
        PersonMerge last = merges.findFirstBySheetIdAndIntoIdOrderByIdDesc(sheetId, into.getId())
                .filter(m -> !m.isUndone() && m.getState() != null)
                .orElseThrow(() -> new MergeRefusedException(into.getName() + " has no merge to undo."));
        if (!last.getMadeBy().equals(user)) {
            throw new MergeRefusedException("Only " + last.getMadeBy() + " can undo the last merge into "
                    + into.getName() + ".");
        }
        if (!last.getMadeOn().equals(sheet.getToday())) {
            throw new MergeRefusedException("The last merge into " + into.getName()
                    + " can only be undone on the day it was made.");
        }
        Person was = history.read(last.getState(), Person.class);
        List<String> names = new ArrayList<>();
        names.add(was.getName());
        names.addAll(was.getAliases());
        Rollup.Snapshot before = rollup.take(sheetId);

        // their names leave the other's aliases first, so they are free for them again
        names.forEach(into::removeAlias);
        Person left = people.saveAndFlush(into);
        Set<String> tree = namesInTree(sheetId, null);
        for (String name : names) {
            if (people.nameTaken(sheetId, name, null) || tree.contains(name.toLowerCase(Locale.ROOT))) {
                throw new MergeRefusedException("The name " + name + " is now in use.");
            }
        }
        // back with the id they had, at the end of the list
        jdbc.update("INSERT INTO person (id, sheet_id, name, list_position, version) VALUES (?, ?, ?, ?, 0)",
                last.getFromId(), sheetId, was.getName(), people.maxPosition(sheetId) + 1);
        Person restored = people.findById(last.getFromId()).orElseThrow(PersonNotFoundException::new);
        restored.restoreFrom(was, was.getContact() == null || !people.contactTaken(sheetId, was.getContact(), null));
        restored = people.saveAndFlush(restored);

        List<Claim> returning = claims.findAllById(last.getClaimIds()).stream()
                .filter(c -> sheetId.equals(c.getSheetId()) && into.getId().equals(c.getPersonId())).toList();
        returning.forEach(c -> c.setPersonId(last.getFromId()));
        claims.saveAll(returning);
        claims.flush();
        last.undo();
        merges.saveAndFlush(last);

        audit.recordOnCommit("PERSON_MERGE_UNDONE sheet=" + sheetId + " by=" + user + " name=" + restored.getName()
                + " from=" + left.getName());
        rollup.recordChanges(sheetId, user, before);
        return restored;
    }

    /** On the sheet a transfer brought a person to, a steward undoes it on the same sheet's today they arrived:
     *  they are transferred back to the sheet they came from, by exactly the rules of a transfer. Refused on a
     *  later day, for anyone but a steward and for a person who did not arrive by transfer. */
    @Transactional
    public Person undoTransfer(String sheetId, String user, long personId) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (!roles.isSteward(sheetId, user)) {
            throw new TransferRefusedException("Only a steward may undo a transfer.");
        }
        if (person.getOriginSheet() == null || person.getArrivedOn() == null) {
            throw new TransferRefusedException(person.getName() + " did not arrive by transfer.");
        }
        if (!person.getArrivedOn().equals(today(sheetId))) {
            throw new TransferRefusedException("The transfer of " + person.getName()
                    + " can only be undone on the day they arrived.");
        }
        String name = person.getName();
        String back = person.getOriginSheet();
        Rollup.Snapshot before = rollup.take(sheetId);
        Person returned = transfer(sheetId, user, personId, back);
        audit.recordOnCommit("PERSON_TRANSFER_UNDONE sheet=" + sheetId + " by=" + user + " name=" + name
                + " to=" + back);
        rollup.recordChanges(sheetId, user, before);
        return returned;
    }

    /** Carry a person's statements to whom they merge into or arrive as (on that person's sheet), ahead of
     *  that person's own so that their own last statement stays their last; theirs are taken again after. */
    private void carryStatements(Person from, Person into) {
        List<Statement> theirs = statements.findByPersonIdOrderByIdAsc(from.getId());
        if (theirs.isEmpty()) {
            return;
        }
        List<Statement> own = statements.findByPersonIdOrderByIdAsc(into.getId());
        statements.deleteAll(theirs);
        statements.deleteAll(own);
        statements.flush();
        theirs.forEach(s -> statements.save(s.copyTo(into.getSheetId(), into.getId())));
        own.forEach(s -> statements.save(s.copyTo(into.getSheetId(), into.getId())));
        statements.flush();
    }

    /** Carry a person's snapshots to whom they merge into or arrive as (on that person's sheet), the two
     *  people's kept oldest first by the day each was written for; on a day both have one, the receiving
     *  person's own is kept. */
    private void carrySnapshots(Person from, Person into) {
        List<PersonSnapshot> theirs = snapshots.findByPersonIdOrderByIdAsc(from.getId());
        if (theirs.isEmpty()) {
            return;
        }
        List<PersonSnapshot> own = snapshots.findByPersonIdOrderByIdAsc(into.getId());
        Set<LocalDate> ownDays = own.stream().map(PersonSnapshot::getDay).collect(Collectors.toSet());
        List<PersonSnapshot> all = new ArrayList<>(
                theirs.stream().filter(s -> !ownDays.contains(s.getDay())).toList());
        all.addAll(own);
        all.sort(Comparator.comparing(PersonSnapshot::getDay));
        snapshots.deleteAll(theirs);
        snapshots.deleteAll(own);
        snapshots.flush();
        all.forEach(s -> snapshots.save(s.copyTo(into.getSheetId(), into.getId())));
        snapshots.flush();
    }

    /** Swap a person with the one above them in the list (up) or below them (down); the top cannot move up
     *  nor the bottom down. Answers the two people swapped, in their new list order. */
    @Transactional
    public List<Person> move(String sheetId, String user, long personId, boolean up) {
        List<Person> listed = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        int at = -1;
        for (int i = 0; i < listed.size(); i++) {
            if (listed.get(i).getId() == personId) {
                at = i;
            }
        }
        if (at < 0) {
            throw new PersonNotFoundException();
        }
        int to = up ? at - 1 : at + 1;
        if (to < 0) {
            throw new MoveRefusedException(listed.get(at).getName() + " is already at the top of the list.");
        }
        if (to >= listed.size()) {
            throw new MoveRefusedException(listed.get(at).getName() + " is already at the bottom of the list.");
        }
        Person person = listed.get(at);
        Person other = listed.get(to);
        Long position = person.getPosition();
        person.setPosition(other.getPosition());
        other.setPosition(position);
        people.saveAllAndFlush(List.of(person, other));
        audit.recordOnCommit("PERSON_MOVED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " position=" + (to + 1));
        return up ? List.of(person, other) : List.of(other, person);
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

    /** Flag a person with a reason until a date; anyone but a steward is refused, as is a blank reason or
     *  no until-date. */
    @Transactional
    public Person flag(String sheetId, String user, long personId, String reason, LocalDate until) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (!roles.isSteward(sheetId, user)) {
            throw new FlagRefusedException("Only a steward may flag a person.");
        }
        if (reason == null || reason.isBlank()) {
            throw new FlagRefusedException("Enter a reason for the flag.");
        }
        if (until == null) {
            throw new FlagRefusedException("Enter the date the flag is in force until.");
        }
        person.flag(reason.trim(), until);
        Person flagged = saved(person);
        audit.recordOnCommit("PERSON_FLAGGED sheet=" + sheetId + " by=" + user + " name=" + flagged.getName()
                + " reason=" + reason.trim() + " until=" + until);
        return flagged;
    }

    /** Name the user who may approve or reject a person's PENDING claims as a steward may; a blank name
     *  clears it. A viewer is refused. */
    @Transactional
    public Person setApprover(String sheetId, String user, long personId, String approver) {
        roles.requireNotViewer(sheetId, user);
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        String name = approver == null || approver.isBlank() ? null : approver.trim();
        person.setApprover(name);
        Person updated = saved(person);
        audit.recordOnCommit("APPROVER_SET sheet=" + sheetId + " by=" + user + " name=" + updated.getName()
                + " user=" + (name == null ? "" : name));
        return updated;
    }

    /** Unretire a retired person on the sheet's today, their retirement kept as a past one; anyone not
     *  retired is refused. */
    @Transactional
    public Person unretire(String sheetId, String user, long personId) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (!person.isRetired()) {
            throw new UnretireRefusedException(person.getName() + " is not retired.");
        }
        LocalDate on = today(sheetId);
        Rollup.Snapshot before = rollup.take(sheetId);
        person.unretire(on);
        Person unretired = saved(person);
        audit.recordOnCommit("PERSON_UNRETIRED sheet=" + sheetId + " by=" + user + " name=" + unretired.getName()
                + " on=" + on);
        rollup.recordChanges(sheetId, user, before);
        return unretired;
    }

    /** Retire each of the chosen people from a date (the sheet's today when none is given), in list order,
     *  exactly as their row's retire would; the people retired, in list order. A viewer's goes through with
     *  no one retired. */
    @Transactional
    public List<Person> bulkRetire(String sheetId, String user, List<Long> personIds, LocalDate from) {
        requireSheet(sheetId);
        if (roles.isViewer(sheetId, user)) {
            return List.of();
        }
        Set<Long> chosen = Set.copyOf(personIds);
        List<Person> picked = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .filter(p -> chosen.contains(p.getId())).toList();
        if (picked.size() != chosen.size()) {
            throw new PersonNotFoundException();
        }
        return picked.stream().map(p -> retire(sheetId, user, p.getId(), from)).toList();
    }

    /** Put each of the chosen people on hold from a date (the sheet's today when none is given) to a date
     *  when one is given, in list order, exactly as their row's hold would; the people held, in list order.
     *  A hold ending before it starts is refused for them all. A viewer's goes through with no one held. */
    @Transactional
    public List<Person> bulkHold(String sheetId, String user, List<Long> personIds, LocalDate from, LocalDate to) {
        requireSheet(sheetId);
        if (roles.isViewer(sheetId, user)) {
            return List.of();
        }
        Set<Long> chosen = Set.copyOf(personIds);
        List<Person> picked = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .filter(p -> chosen.contains(p.getId())).toList();
        if (picked.size() != chosen.size()) {
            throw new PersonNotFoundException();
        }
        return picked.stream().map(p -> hold(sheetId, user, p.getId(), from, to)).toList();
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

    /** Refuse names (compared without regard to case) any other sheet of the sheet's tree uses as a name or
     *  alias. */
    private void requireFreeInTree(String sheetId, List<String> names, Long exceptId) {
        Set<String> used = namesInTree(sheetId, exceptId);
        if (names.stream().anyMatch(n -> used.contains(n.toLowerCase(Locale.ROOT)))) {
            throw new PersonNameTakenException();
        }
    }

    /** The names and aliases, lower-cased, of everyone but {@code exceptId} on the other sheets of the
     *  sheet's tree. */
    private Set<String> namesInTree(String sheetId, Long exceptId) {
        Set<String> tree = sheets.tree(sheetId);
        if (tree.isEmpty()) {
            return Set.of();
        }
        return people.findBySheetIdIn(tree).stream().filter(p -> !p.getId().equals(exceptId))
                .flatMap(p -> Stream.concat(Stream.of(p.getName()),
                        p.getAliases().stream()))
                .map(n -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    /** The names and aliases, lower-cased, used on the other sheets of the sheet's tree. */
    @Transactional(readOnly = true)
    public Set<String> treeNames(String sheetId) {
        return namesInTree(sheetId, null);
    }

    /** Whether the person's name or an alias is used on another sheet of their sheet's tree. */
    @Transactional(readOnly = true)
    public boolean clashing(Person person) {
        return clashing(person, treeNames(person.getSheetId()));
    }

    /** Whether the person's name or an alias is among the tree's names. */
    public static boolean clashing(Person person, Set<String> treeNames) {
        return treeNames.contains(person.getName().toLowerCase(Locale.ROOT))
                || person.getAliases().stream().anyMatch(a -> treeNames.contains(a.toLowerCase(Locale.ROOT)));
    }

    /** Keep the tags a person now carries among those used on the sheet. */
    private void recordTags(String sheetId, List<String> tags) {
        for (String tag : tags) {
            if (!sheetTags.existsBySheetIdAndTag(sheetId, tag)) {
                sheetTags.save(new SheetTag(sheetId, tag));
            }
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
        if (person.getPosition() == null) {
            person.setPosition(people.maxPosition(person.getSheetId()) + 1);
        }
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

    /** The grade a new person takes when none is given: the sheet's default grade (A when none is set). */
    @Transactional(readOnly = true)
    public String defaultGrade(String sheetId) {
        return sheets.findById(sheetId).orElseThrow(SheetNotFoundException::new).defaultGradeOrA();
    }

    /** Refuse a grade chosen for a person that is not one of the sheet's grades, or is retired. */
    private void requireNotRetired(String sheetId, String grade) {
        if (sheets.findById(sheetId).map(s -> !s.isGrade(grade)).orElse(false)) {
            throw new GradeUnknownException(grade);
        }
        if (sheets.findById(sheetId).map(s -> s.isGradeRetired(grade)).orElse(false)) {
            throw new GradeRetiredException(grade);
        }
    }

    private void requireSheet(String sheetId) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
    }
}
