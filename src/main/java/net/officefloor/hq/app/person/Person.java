package net.officefloor.hq.app.person;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.officefloor.hq.app.history.History;

/** A person on a sheet, with a name and a dated grade (A, B or C): each grade they are given applies
 *  from a date, the first from the beginning of time. */
@Entity
@EntityListeners(History.class)
@Table(name = "person")
public class Person {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "name", nullable = false)
    private String name;

    /** The grades they were given, each from a date; the first has none. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "person_id", nullable = false)
    private List<PersonGrade> grades = new ArrayList<>();

    /** Their own terms in days from the beginning of time, ahead of their grade's and the sheet's; null
     *  when they have none. */
    @Column(name = "terms")
    private Integer terms;

    /** Their own terms recorded from a date, each ahead of those from the beginning of time from then on. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "person_id", nullable = false)
    private List<PersonTerms> datedTerms = new ArrayList<>();

    /** Their contact, trimmed and lower-cased; null when they have none. Unique on the sheet. */
    @Column(name = "contact")
    private String contact;

    /** Their tags, sorted and comma separated; none when they carry none. */
    @Column(name = "tags")
    private String tags;

    /** The date they are retired from; null while not retired. Set at all, they count as retired. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** Their holds: an OPEN or PENDING claim dated within one, and bound by no statement, counts in no
     *  live total; their other claims still do. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "person_id", nullable = false)
    private List<PersonHold> holds = new ArrayList<>();

    /** Their past retirements, each from the date they were retired from to the day they were unretired. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "person_id", nullable = false)
    private List<PersonRetirement> retirements = new ArrayList<>();

    /** The other names they go by. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "person_id", nullable = false)
    private List<PersonAlias> aliases = new ArrayList<>();

    /** The sheet they were transferred from; null for anyone added on this sheet. */
    @Column(name = "origin_sheet")
    private String originSheet;

    /** Their place in the sheet's list; set when they are first saved, next after everyone on the sheet. */
    @Column(name = "list_position", nullable = false)
    private Long position;

    /** Moved on by each save, so a save made from an older version is refused. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {
    }

    public Person(String sheetId, String name, String grade) {
        this.sheetId = sheetId;
        this.name = name;
        this.grades.add(new PersonGrade(grade, null));
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** The grades they were given, the one from the beginning of time first, then by from-date. */
    public List<PersonGrade> getGrades() {
        return grades.stream()
                .sorted(Comparator.comparing(PersonGrade::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /** The grade in force on a date: the one whose from-date is the latest on or before it. */
    public String gradeOn(LocalDate date) {
        String found = null;
        for (PersonGrade g : getGrades()) {
            if (found == null || g.getFromDate() == null || !g.getFromDate().isAfter(date)) {
                found = g.getGrade();
            }
        }
        return found;
    }

    /** Give them a grade from a date, replacing the one they had from that same date. */
    public void setGrade(String grade, LocalDate from) {
        for (PersonGrade g : grades) {
            if (Objects.equals(g.getFromDate(), from)) {
                g.setGrade(grade);
                return;
            }
        }
        grades.add(new PersonGrade(grade, from));
    }

    /** Their own terms from the beginning of time; null when they have none. */
    public Integer getTerms() {
        return terms;
    }

    public void setTerms(Integer terms) {
        this.terms = terms;
    }

    /** Their own terms recorded from a date, by from-date. */
    public List<PersonTerms> getDatedTerms() {
        return datedTerms.stream().sorted(Comparator.comparing(PersonTerms::getFromDate)).toList();
    }

    /** Their own terms in force on a date: those whose from-date is the latest on or before it, else those
     *  from the beginning of time; null when they have none then. */
    public Integer termsOn(LocalDate date) {
        Integer found = terms;
        for (PersonTerms t : getDatedTerms()) {
            if (!t.getFromDate().isAfter(date)) {
                found = t.getTerms();
            }
        }
        return found;
    }

    /** Record their own terms (null: none) from a date, replacing those recorded from that same date; with no
     *  date, set those from the beginning of time. Whether anything recorded changed. */
    public boolean setTerms(Integer terms, LocalDate from) {
        if (from == null) {
            boolean changed = !Objects.equals(this.terms, terms);
            this.terms = terms;
            return changed;
        }
        for (PersonTerms t : datedTerms) {
            if (t.getFromDate().equals(from)) {
                boolean changed = !Objects.equals(t.getTerms(), terms);
                t.setTerms(terms);
                return changed;
            }
        }
        datedTerms.add(new PersonTerms(terms, from));
        return true;
    }

    public String getOriginSheet() {
        return originSheet;
    }

    public String getContact() {
        return contact;
    }

    public void setContact(String contact) {
        this.contact = contact;
    }

    /** Their tags, sorted; empty when they carry none. */
    public List<String> getTags() {
        return tags == null ? List.of() : List.of(tags.split(","));
    }

    /** Carry the tags (normalised: trimmed, lower-cased, without repeats and sorted). */
    public void setTags(List<String> tags) {
        this.tags = tags.isEmpty() ? null : String.join(",", tags);
    }

    public LocalDate getRetiredFrom() {
        return retiredFrom;
    }

    public void setRetiredFrom(LocalDate retiredFrom) {
        this.retiredFrom = retiredFrom;
    }

    public boolean isRetired() {
        return retiredFrom != null;
    }

    /** Whether a claim on this date is refused: it is after their retirement date, or after the from and
     *  before the to of one of their past retirements. */
    public boolean retiredOn(LocalDate date) {
        return (retiredFrom != null && date.isAfter(retiredFrom))
                || retirements.stream().anyMatch(r -> r.refuses(date));
    }

    /** Their past retirements, by from-date. */
    public List<PersonRetirement> getRetirements() {
        return retirements.stream()
                .sorted(Comparator.comparing(PersonRetirement::getFromDate).thenComparing(PersonRetirement::getToDate))
                .toList();
    }

    /** Unretire them on a day (the sheet's today): no longer retired, their retirement kept as a past one
     *  from its date to that day. */
    public void unretire(LocalDate on) {
        retirements.add(new PersonRetirement(retiredFrom, on));
        retiredFrom = null;
    }

    /** Whether their retirement date is reached on a day (the sheet's today): it is on or before it. */
    public boolean retiredBy(LocalDate today) {
        return retiredFrom != null && today != null && !today.isBefore(retiredFrom);
    }

    /** Whether they have any hold at all. */
    public boolean isHeld() {
        return !holds.isEmpty();
    }

    /** Their holds, the one from the beginning of time first, then by from-date. */
    public List<PersonHold> getHolds() {
        return holds.stream()
                .sorted(Comparator.comparing(PersonHold::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(PersonHold::getToDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** Whether a hold of theirs covers a date. */
    public boolean heldOn(LocalDate date) {
        return holds.stream().anyMatch(h -> h.covers(date));
    }

    /** Put them on hold from a date, to a date when one is given. */
    public void hold(LocalDate from, LocalDate to) {
        holds.add(new PersonHold(from, to));
    }

    /** Their aliases, sorted as names are compared: without regard to case. */
    public List<String> getAliases() {
        return aliases.stream().map(PersonAlias::getAlias)
                .sorted(Comparator.comparing((String a) -> a.toLowerCase(Locale.ROOT)).thenComparing(a -> a))
                .toList();
    }

    /** Give them another name they go by, stored trimmed. */
    public void addAlias(String alias) {
        String trimmed = alias.trim();
        String key = trimmed.toLowerCase(Locale.ROOT);
        int copy = (int) aliases.stream().filter(a -> a.getAlias().toLowerCase(Locale.ROOT).equals(key)).count();
        aliases.add(new PersonAlias(sheetId, trimmed, copy));
    }

    /** A copy of them for another sheet: every grade they were given (with its from-date), their terms
     *  (each with its from-date), contact, tags and aliases; not their holds. */
    public Person copyTo(String otherSheetId) {
        Person copy = new Person();
        copy.sheetId = otherSheetId;
        copy.name = name;
        grades.forEach(g -> copy.grades.add(new PersonGrade(g.getGrade(), g.getFromDate())));
        copy.terms = terms;
        datedTerms.forEach(t -> copy.datedTerms.add(new PersonTerms(t.getTerms(), t.getFromDate())));
        copy.contact = contact;
        copy.tags = tags;
        copy.originSheet = sheetId;
        aliases.forEach(a -> copy.aliases.add(new PersonAlias(otherSheetId, a.getAlias(), a.getAliasCopy())));
        return copy;
    }

    public Long getPosition() {
        return position;
    }

    public void setPosition(Long position) {
        this.position = position;
    }

    public long getVersion() {
        return version;
    }
}
