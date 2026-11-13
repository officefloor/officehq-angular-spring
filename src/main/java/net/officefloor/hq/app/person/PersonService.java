package net.officefloor.hq.app.person;

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
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.pool.Pool;
import net.officefloor.hq.app.pool.PoolRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final PatronRepository patrons;
    private final WatchRepository watches;
    private final RoleService roles;
    private final Rollup rollup;
    private final Audit audit;
    private final ClaimRepository claims;
    private final PoolRepository pools;
    private final PeriodRepository periods;
    private final SheetService sheetService;

    public PersonService(PersonRepository people, SheetRepository sheets, StatementRepository statements,
            PatronRepository patrons, WatchRepository watches, Rollup rollup, Audit audit, RoleService roles,
            ClaimRepository claims, PoolRepository pools, PeriodRepository periods, SheetService sheetService) {
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
        requireFreeInTree(sheetId, List.of(name.trim()), null);
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

    /** A line of the import box: its number, and the person form's fields. */
    private record ImportLine(int number, String name, String grade, Integer terms, String contact) {
    }

    /** What an import did: the people it added and those it updated, each in line order. */
    public record ImportResult(List<Person> added, List<Person> updated) {
    }

    /** The import box: lines of name,grade,terms,contact (the last two may be absent or blank), blank lines
     *  skipped. A line whose name is a name or alias on the sheet (ignoring case) updates that person; any
     *  other adds one. Every line is checked first with the person form's rules, a name, person or contact
     *  repeated within the import too (ignoring case); a refused line refuses the import, changing no one and
     *  writing nothing. Otherwise each is applied in order: an add exactly as the form adds, an update as the
     *  form saves, keeping their name, recording the grade from the sheet's today, and setting their terms
     *  and contact only when the line gives them. */
    @Transactional
    public ImportResult importPeople(String sheetId, String user, String text) {
        requireSheet(sheetId);
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
            ImportLine line = parseImport(i + 1, raw[i]);
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
                            null, line.terms() != null ? line.terms() : now.getTerms(),
                            line.contact() != null ? line.contact() : now.getContact(), true));
                }
            } catch (PersonNameTakenException | PersonContactTakenException | PersonNotFoundException
                    | PersonVersionConflictException e) {
                // changed by another user at the same moment
                throw importRefused(line.number(), e.getMessage());
            }
        }
        return new ImportResult(added, updated);
    }

    private static ImportLine parseImport(int number, String raw) {
        String[] f = raw.split(",", 4);
        String name = f[0].trim();
        if (name.isEmpty()) {
            throw importRefused(number, "enter a name.");
        }
        if (name.length() > 200) {
            throw importRefused(number, "the name is too long.");
        }
        String grade = f.length > 1 ? f[1].trim() : "";
        if (!grade.matches("[ABC]")) {
            throw importRefused(number, "choose a grade of A, B or C.");
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
     *  from-date, records nothing. Their own terms are set to those given (none clears them), and their
     *  contact to the one given (blank clears it). */
    public Person update(String sheetId, String user, long personId, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, String contact) {
        return update(sheetId, user, personId, version, name, grade, gradeFrom, terms, contact, true);
    }

    /** As the person form saves, which (form) also refuses a name or alias some other sheet of the tree
     *  uses, so a person who clashes keeps being refused until a rename leaves them clashing with no one. */
    private Person update(String sheetId, String user, long personId, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, String contact, boolean form) {
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
        List<Person> picked = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .filter(p -> chosen.contains(p.getId())).toList();
        if (picked.size() != chosen.size()) {
            throw new PersonNotFoundException();
        }
        return picked.stream().map(p -> update(sheetId, user, p.getId(), p.getVersion(), p.getName(), grade, from,
                p.getTerms(), p.getContact(), false)).toList();
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

    /** A steward transfers a person to another sheet by its id. They arrive there with every grade they were
     *  given, their terms, contact and aliases, and their claims (status, dates, notes, frozen figures, tags
     *  and logs kept, listed in the order they were); each claim that had a number takes the next number of
     *  the other sheet, in the order the claims were opened (by the sheet's today then, then by the order they
     *  were added). Figures a statement bound are lost, their statement count starts again, and references
     *  are not carried. They leave this sheet entirely: their patron links, pool memberships, watches,
     *  statements, holds and fixed period figures are dropped. Refused for anyone but a steward, a retired
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
        Person arrived = into != null ? into : saved(from.copyTo(to));
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
        for (Period period : periods.findBySheetIdOrderByFromDateAsc(sheetId)) {
            period.dropClosedTotal(from.getId());
        }
        watches.deleteAll(watches.findByPersonIdOrderByIdAsc(from.getId()));
        statements.deleteAll(statements.findByPersonIdOrderByIdAsc(from.getId()));
        claims.deleteAll(leaving);
        // everything naming them goes before they do
        people.flush();
        people.delete(from);
        people.flush();
        if (into != null) {
            names.forEach(into::addAlias);
            arrived = people.saveAndFlush(into);
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

    private void requireSheet(String sheetId) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
    }
}
