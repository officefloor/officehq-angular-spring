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

/** A revision of a person in their sheet's history: their name, grades, holds, retirement, terms and
 *  contact  *  after a change, recorded on the sheet's today then. Grades are kept as "from:grade" and holds as
 *  "from..to" (a day left blank when there is none), separated by ";"; past retirements likewise as "from..to". */
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
    }

    /** Whether the person is now as this revision has them. */
    public boolean matches(Person person) {
        return name.equals(person.getName()) && grades.equals(grades(person)) && holds.equals(holds(person))
                && Objects.equals(retiredFrom, person.getRetiredFrom())
                && retirements.equals(retirements(person)) && Objects.equals(terms, person.getTerms())
                && Objects.equals(contact, person.getContact());
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
