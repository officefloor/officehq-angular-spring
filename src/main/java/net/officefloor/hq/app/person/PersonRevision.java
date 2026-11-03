package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Objects;
import java.util.stream.Collectors;

/** A revision of a person in their sheet's history: their name, grades, holds, retirement, terms,
 *  contact, tags and aliases after a change, recorded on the sheet's today then. Grades are kept as "from:grade" and holds as
 *  "from..to" (a day left blank when there is none), separated by ";"; past retirements likewise as "from..to", and aliases separated by ";". */
@Entity
@Table(name = "person_revision")
public class PersonRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    /** The sheet's today when the revision was recorded. */
    @Column(name = "recorded_on", nullable = false)
    private LocalDate recordedOn;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "grades", nullable = false)
    private String grades;

    @Column(name = "holds", nullable = false)
    private String holds;

    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    @Column(name = "retirements", nullable = false)
    private String retirements;

    @Column(name = "terms")
    private Integer terms;

    @Column(name = "contact")
    private String contact;

    @Column(name = "tags")
    private String tags;

    @Column(name = "aliases", nullable = false)
    private String aliases;

    /** Whether the person had left the sheet by then (see {@link #gone(LocalDate)}). */
    @Column(name = "gone", nullable = false)
    private boolean gone;

    protected PersonRevision() {
    }

    /** A revision of the person as they are now, recorded on the day. */
    public PersonRevision(Person person, LocalDate recordedOn) {
        this.sheetId = person.getSheetId();
        this.personId = person.getId();
        this.recordedOn = recordedOn;
        this.name = person.getName();
        this.grades = grades(person);
        this.holds = holds(person);
        this.retiredFrom = person.getRetiredFrom();
        this.retirements = retirements(person);
        this.terms = person.getTerms();
        this.contact = person.getContact();
        this.tags = person.getTags();
        this.aliases = aliases(person);
    }

    /** The revision recording, on the day, that the person has left the sheet: as this one has them
     *  otherwise. */
    public PersonRevision gone(LocalDate recordedOn) {
        PersonRevision left = new PersonRevision();
        left.sheetId = sheetId;
        left.personId = personId;
        left.recordedOn = recordedOn;
        left.name = name;
        left.grades = grades;
        left.holds = holds;
        left.retiredFrom = retiredFrom;
        left.retirements = retirements;
        left.terms = terms;
        left.contact = contact;
        left.tags = tags;
        left.aliases = aliases;
        left.gone = true;
        return left;
    }

    /** Whether the person had left the sheet by this revision. */
    public boolean isGone() {
        return gone;
    }

    /** Whether the person is now as this revision has them. */
    public boolean matches(Person person) {
        return name.equals(person.getName()) && grades.equals(grades(person)) && holds.equals(holds(person))
                && Objects.equals(retiredFrom, person.getRetiredFrom())
                && retirements.equals(retirements(person)) && Objects.equals(terms, person.getTerms())
                && Objects.equals(contact, person.getContact())
                && Objects.equals(tags, person.getTags()) && aliases.equals(aliases(person));
    }

    /** The person as this revision has them, detached: for working out figures only, never saved. */
    public Person toPerson() {
        Person person = new Person(sheetId, personId, name);
        for (String grade : split(grades)) {
            int at = grade.lastIndexOf(':');
            person.setGradeFrom(grade.substring(at + 1), day(grade.substring(0, at)));
        }
        for (String hold : split(holds)) {
            int at = hold.indexOf("..");
            person.addHold(day(hold.substring(0, at)), day(hold.substring(at + 2)));
        }
        person.setRetiredFrom(retiredFrom);
        for (String retirement : split(retirements)) {
            int at = retirement.indexOf("..");
            person.addRetirement(day(retirement.substring(0, at)), day(retirement.substring(at + 2)));
        }
        person.setTerms(terms);
        person.setContact(contact);
        person.setTags(tags);
        for (String alias : split(aliases)) {
            person.addAlias(alias);
        }
        return person;
    }

    public Long getPersonId() {
        return personId;
    }

    public LocalDate getRecordedOn() {
        return recordedOn;
    }

    private static String grades(Person person) {
        return person.getGrades().stream().map(g -> text(g.getFromDate()) + ":" + g.getGrade())
                .collect(Collectors.joining(";"));
    }

    private static String holds(Person person) {
        return person.getHolds().stream().map(h -> text(h.getFromDate()) + ".." + text(h.getToDate()))
                .collect(Collectors.joining(";"));
    }

    private static String retirements(Person person) {
        return person.getRetirements().stream().map(r -> text(r.getFromDate()) + ".." + text(r.getToDate()))
                .collect(Collectors.joining(";"));
    }

    private static String aliases(Person person) {
        return String.join(";", person.getAliases());
    }

    private static String text(LocalDate day) {
        return day == null ? "" : day.toString();
    }

    private static LocalDate day(String text) {
        return text.isEmpty() ? null : LocalDate.parse(text);
    }

    private static String[] split(String list) {
        return list.isEmpty() ? new String[0] : list.split(";");
    }
}
