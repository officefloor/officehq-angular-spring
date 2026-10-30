package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The people on a sheet: listed by their position, a new person going to the end and a person moved up
 * or down swapping with their neighbour, added and edited one at a time;
 * an edit made from a version someone else has saved since is refused. A person can be retired from a
 * date; no claim dated after it can then be added for them. */
@Service
public class PersonService {

    private final PersonRepository people;
    private final PatronRepository patrons;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final Rollup rollup;
    private final ApplicationEventPublisher events;

    public PersonService(PersonRepository people, PatronRepository patrons, SheetService sheets,
            SheetRepository sheetRows, Rollup rollup, ApplicationEventPublisher events) {
        this.patrons = patrons;
        this.sheetRows = sheetRows;
        this.rollup = rollup;
        this.people = people;
        this.sheets = sheets;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
    }

    @Transactional(readOnly = true)
    public Person get(String sheetId, long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
    }

    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms, String contact) {
        sheets.load(sheetId, user);
        sheetRows.lockById(sheetId);
        String trimmed = name.trim();
        if (inUse(sheetId, trimmed, null)) {
            throw nameInUse();
        }
        if (contact != null && people.existsBySheetIdAndContact(sheetId, contact)) {
            throw contactInUse();
        }
        Rollup.Figures before = rollup.figures(sheetId);
        Person added = new Person(sheetId, trimmed, grade);
        added.setTerms(terms);
        added.setContact(contact);
        added.setPosition(people.maxPosition(sheetId) + 1);
        Person person = people.saveAndFlush(added);
        events.publishEvent(new PersonChanged("PERSON_ADDED", sheetId, user, trimmed, grade,
                terms == null ? null : terms.toString(), contact));
        rollup.changed(sheetId, user, before);
        return person;
    }

    /**
     * Save the person's name and record the grade from a date (none: the sheet's today), keeping the
     * grades already recorded; a grade left as the row showed it (in force on the sheet's today) with no
     * date records nothing. The person's own terms and contact are set to those given (null: none); a contact
     * another person on the sheet has is refused. The edit names the grade in force on the sheet's today after
     * it and, when they changed, the terms and the contact.
     */
    @Transactional
    public Person edit(String sheetId, String user, long personId, Long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, String contact) {
        sheetRows.lockById(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (version != null && version != person.getVersion()) {
            throw edited();
        }
        String trimmed = name.trim();
        if (inUse(sheetId, trimmed, personId)
                || person.getAliases().stream().anyMatch(alias -> inUse(sheetId, alias, personId))) {
            throw nameInUse();
        }
        if (contact != null && people.existsBySheetIdAndContactAndIdNot(sheetId, contact, personId)) {
            throw contactInUse();
        }
        Rollup.Figures before = rollup.figures(sheetId);
        person.setName(trimmed);
        LocalDate today = sheets.today(sheetId);
        if (gradeFrom != null || !grade.equals(person.gradeOn(today))) {
            person.gradeFrom(grade, gradeFrom != null ? gradeFrom : today);
        }
        String termsChange = Objects.equals(terms, person.getTerms()) ? null : terms == null ? "" : terms.toString();
        person.setTerms(terms);
        String contactChange = Objects.equals(contact, person.getContact()) ? null : contact == null ? "" : contact;
        person.setContact(contact);
        people.flush();
        events.publishEvent(new PersonChanged("PERSON_EDITED", sheetId, user, trimmed, person.gradeOn(today),
                termsChange, contactChange));
        rollup.changed(sheetId, user, before);
        return person;
    }

    /**
     * Retire the person from a date; a date left out takes the sheet's today. Once the sheet's today reaches
     * it, their OPEN and PENDING claims not bound by a statement leave the live totals, so the figures it
     * moves are announced. The sheet stays locked
     * until the change commits, so a claim added meanwhile is checked against the new date.
     */
    @Transactional
    public Person retire(String sheetId, String user, long personId, LocalDate from) {
        sheetRows.lockById(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate on = from != null ? from : sheets.today(sheetId);
        Rollup.Figures before = rollup.figures(sheetId);
        person.setRetiredFrom(on);
        people.flush();
        events.publishEvent(new PersonRetired(sheetId, user, person.getName(), on));
        rollup.changed(sheetId, user, before);
        return person;
    }

    /**
     * Move the person up or down the list: they swap places with the person above or below. The top person
     * cannot move up nor the bottom one down. Their versions stay as they are: a move edits neither of them.
     * Takes turns with other changes on the sheet.
     */
    @Transactional
    public Person move(String sheetId, String user, long personId, boolean up) {
        sheetRows.lockById(sheetId);
        List<Person> listed = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        int at = -1;
        for (int i = 0; i < listed.size(); i++) {
            if (listed.get(i).getId() == personId) {
                at = i;
            }
        }
        if (at < 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person");
        }
        int to = up ? at - 1 : at + 1;
        if (to < 0 || to >= listed.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    up ? "The top person cannot move up" : "The bottom person cannot move down");
        }
        Person person = listed.get(at);
        // the list is numbered afresh from 1 with the two swapped, so positions left by people who have
        // gone from the sheet close up
        for (int i = 0; i < listed.size(); i++) {
            int position = (i == at ? to : i == to ? at : i) + 1;
            if (listed.get(i).getPosition() != position) {
                people.setPosition(listed.get(i).getId(), position);
            }
        }
        events.publishEvent(new PersonMoved(sheetId, user, person.getName(), to + 1));
        return get(sheetId, personId);
    }

    /**
     * Add a hold on the person from a date (none: the sheet's today) to an optional date, keeping their
     * other holds and moving the figures their OPEN claims dated within it count in. Refused when the
     * to-date is before the from-date. Takes turns with claim changes on the sheet.
     */
    @Transactional
    public Person hold(String sheetId, String user, long personId, LocalDate from, LocalDate to) {
        sheetRows.lockById(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate on = from != null ? from : sheets.today(sheetId);
        if (to != null && to.isBefore(on)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A hold cannot end before it starts.");
        }
        Rollup.Figures before = rollup.figures(sheetId);
        person.hold(on, to);
        people.flush();
        events.publishEvent(new PersonHeld(sheetId, user, person.getName(), on));
        rollup.changed(sheetId, user, before);
        return person;
    }

    /**
     * The person on the sheet whose name or an alias is the given one, compared without regard to case.
     */
    @Transactional(readOnly = true)
    public Optional<Person> findByNameOrAlias(String sheetId, String name) {
        return people.findBySheetIdAndNameOrAlias(sheetId, name.trim()).stream().findFirst();
    }

    /**
     * Give the person an alias, stored trimmed. A blank alias, or one equal (without regard to case) to any
     * name or alias on a sheet of the sheet's tree, is
     * refused. Takes turns with other changes on the sheet.
     */
    @Transactional
    public Person addAlias(String sheetId, String user, long personId, String alias) {
        sheetRows.lockById(sheetId);
        Person person = get(sheetId, personId);
        String trimmed = alias == null ? "" : alias.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give an alias");
        }
        if (inUse(sheetId, trimmed, null)) {
            throw nameInUse();
        }
        person.addAlias(trimmed);
        people.flush();
        events.publishEvent(new PersonAliasAdded(sheetId, user, person.getName(), trimmed));
        return person;
    }

    /**
     * Whether a person on a sheet of the sheet's tree (see {@link SheetService#tree}), other than the one with
     * the id (none: any), has the name or alias, compared without regard to case.
     */
    @Transactional(readOnly = true)
    public boolean inUse(String sheetId, String name, Long exceptId) {
        String key = key(name);
        return people.findBySheetIdIn(sheets.tree(sheetId)).stream()
                .filter(p -> !p.getId().equals(exceptId))
                .anyMatch(p -> names(p).contains(key));
    }

    /**
     * The ids of the people on the sheet who clash: whose name or an alias another person on a sheet of the
     * sheet's tree also has, compared without regard to case. People who clashed before names became unique
     * across a tree keep their names; each edit of them is refused until they no longer clash.
     */
    @Transactional(readOnly = true)
    public Set<Long> clashing(String sheetId) {
        Map<String, Set<Long>> holders = new HashMap<>();
        for (Person p : people.findBySheetIdIn(sheets.tree(sheetId))) {
            for (String name : names(p)) {
                holders.computeIfAbsent(name, n -> new HashSet<>()).add(p.getId());
            }
        }
        Set<Long> clashing = new HashSet<>();
        for (Person p : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            if (names(p).stream().anyMatch(n -> holders.getOrDefault(n, Set.of()).stream()
                    .anyMatch(id -> !id.equals(p.getId())))) {
                clashing.add(p.getId());
            }
        }
        return clashing;
    }

    /** Whether the person clashes (see {@link #clashing(String)}). */
    @Transactional(readOnly = true)
    public boolean clashing(String sheetId, Person person) {
        return inUse(sheetId, person.getName(), person.getId())
                || person.getAliases().stream().anyMatch(alias -> inUse(sheetId, alias, person.getId()));
    }

    private static Set<String> names(Person person) {
        Set<String> names = new HashSet<>();
        names.add(key(person.getName()));
        person.getAliases().forEach(alias -> names.add(key(alias)));
        return names;
    }

    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    /** The ids of each person's patrons on the sheet, by person id, in the order they were named. */
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> patronsBySheet(String sheetId) {
        return patrons.findBySheetIdOrderByIdAsc(sheetId).stream().collect(Collectors.groupingBy(
                Patron::getPersonId, Collectors.mapping(Patron::getPatronId, Collectors.toList())));
    }

    /** The ids of the person's patrons, in the order they were named. */
    @Transactional(readOnly = true)
    public List<Long> patrons(String sheetId, long personId) {
        return patrons.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId).stream()
                .map(Patron::getPatronId).toList();
    }

    /**
     * The person names another person on the sheet as their patron. Naming themselves, someone not on
     * the sheet or someone already their patron is refused. Takes turns with other changes on the sheet.
     */
    @Transactional
    public Person addPatron(String sheetId, String user, long personId, long patronId) {
        sheetRows.lockById(sheetId);
        Person person = get(sheetId, personId);
        if (patronId == personId) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A person cannot be their own patron");
        }
        Person patron = people.findByIdAndSheetId(patronId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No such person"));
        if (patrons.existsByPersonIdAndPatronId(personId, patronId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already a patron");
        }
        patrons.saveAndFlush(new Patron(sheetId, personId, patronId));
        events.publishEvent(new PatronAdded(sheetId, user, person.getName(), patron.getName()));
        return person;
    }

    /** Someone else saved the person after the editor loaded them. */
    public static ResponseStatusException edited() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Saved by someone else since loaded");
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Name already in use");
    }

    private static ResponseStatusException contactInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Contact already in use");
    }
}
