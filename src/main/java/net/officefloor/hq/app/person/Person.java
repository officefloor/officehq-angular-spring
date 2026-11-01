package net.officefloor.hq.app.person;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimStatus;

/**
 * A person on a sheet, with a name and a grade (A, B or C). The name is kept without surrounding
 * spaces and is unique on the sheet without regard to case (by its {@link #keyOf key}). A person
 * given a retirement date is retired at once, whatever the date; none of their claims may be dated
 * after it, and from the day the sheet's today reaches it their open claims, unless bound by a
 * statement, leave the live totals as a hold's do. A person may have several holds over time, each from a from-date to an optional to-date. The grade is dated: the first one
 * given is in force from the beginning of time, and each later one from its from-date. A person may have
 * their own terms in days, which their claims' due-by takes ahead of their grade's and the sheet's.
 */
@Entity
@Table(name = "person")
public class Person {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "name_key", nullable = false)
    private String nameKey;

    @Column(name = "grade", nullable = false)
    private String grade;

    /** The grades recorded after the first, by the date each is in force from. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_grade", joinColumns = @JoinColumn(name = "person_id"))
    @MapKeyColumn(name = "from_date")
    @Column(name = "grade", nullable = false)
    private Map<LocalDate, String> datedGrades = new TreeMap<>();

    /** Their own terms in days, ahead of their grade's and the sheet's; null: none. */
    @Column(name = "terms")
    private Integer terms;

    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** Their holds, earliest from-date first. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_hold", joinColumns = @JoinColumn(name = "person_id"))
    @OrderBy("fromDate ASC, toDate ASC")
    private List<PersonHold> holds = new ArrayList<>();

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {}

    public Person(String sheetId, String name, String grade) {
        this.sheetId = sheetId;
        setName(name);
        this.grade = grade;
    }

    /** The key a name is compared by for uniqueness: without surrounding spaces, lower-cased. */
    public static String keyOf(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    public Long getId() { return id; }

    public String getSheetId() { return sheetId; }

    public String getName() { return name; }

    public void setName(String name) {
        this.name = name.strip();
        this.nameKey = keyOf(name);
    }

    /** The first grade they were given, in force from the beginning of time. */
    public String getGrade() { return grade; }

    /** The grade in force on the date: the one with the latest from-date on or before it. */
    public String gradeOn(LocalDate date) {
        Map.Entry<LocalDate, String> on = new TreeMap<>(datedGrades).floorEntry(date);
        return on == null ? grade : on.getValue();
    }

    /** Record the grade from the date, in place of any recorded from the same date. */
    public void setGradeFrom(LocalDate from, String grade) { datedGrades.put(from, grade); }

    /** The grades recorded after the first, by the date each is in force from, earliest first. */
    public Map<LocalDate, String> getDatedGrades() { return new TreeMap<>(datedGrades); }

    /** Their own terms in days, or null when they have none. */
    public Integer getTerms() { return terms; }

    public void setTerms(Integer terms) { this.terms = terms; }

    /** The date they are retired from, or null while not retired. */
    public LocalDate getRetiredFrom() { return retiredFrom; }

    public void setRetiredFrom(LocalDate retiredFrom) { this.retiredFrom = retiredFrom; }

    public boolean isRetired() { return retiredFrom != null; }

    /** Whether a claim of theirs may be dated on the date: not after their retirement date. */
    public boolean mayClaimOn(LocalDate date) { return retiredFrom == null || !date.isAfter(retiredFrom); }

    /** Whether they have any hold, whatever its dates. */
    public boolean isHeld() { return !holds.isEmpty(); }

    /** Their holds, earliest from-date first. */
    public List<PersonHold> getHolds() { return List.copyOf(holds); }

    public void addHold(PersonHold hold) { holds.add(hold); }

    /** Whether the date falls within any of their holds. */
    public boolean heldOn(LocalDate date) { return holds.stream().anyMatch(h -> h.contains(date)); }

    /** Whether their retirement date is reached on the date (the date on or after it). */
    public boolean retiredOn(LocalDate date) { return retiredFrom != null && date != null && !date.isBefore(retiredFrom); }

    /** Whether an open claim of theirs is kept out of the live totals: dated within a hold, or retired by the sheet's today. */
    private boolean keptOut(Claim claim, LocalDate today) { return heldOn(claim.getDate()) || retiredOn(today); }

    /**
     * Whether a claim of theirs counts in the live totals: it counts by its status, unless it is open
     * (OPEN or PENDING), dated within a hold or theirs once their retirement date is reached on the
     * sheet's today, and not bound by a statement, or deferred on the sheet's today.
     */
    public boolean counts(Claim claim, LocalDate today) {
        if (!claim.getStatus().counts() || claim.isDeferredOn(today)) return false;
        return !claim.getStatus().isOpen() || claim.isBound() || !keptOut(claim, today);
    }

    /**
     * Whether a claim of theirs counts for a period ending on the date: by the status it had at the end
     * of that date, less a deferred one and one still open now (OPEN or PENDING) dated within a hold, or
     * theirs once retired on the sheet's today, and not bound, as the live rules say: a claim since settled or voided is no longer kept out by a hold.
     */
    public boolean countsAt(Claim claim, LocalDate end, LocalDate today) {
        ClaimStatus at = claim.statusAt(end);
        if (at == null || !at.counts() || claim.isDeferredOn(today)) return false;
        return !claim.getStatus().isOpen() || claim.isBound() || !keptOut(claim, today);
    }

    /**
     * Whether they are active on the date: they have an open claim (OPEN or PENDING) that counts, dated
     * within the 30 days up to and including it.
     */
    public boolean activeOn(Iterable<Claim> claims, LocalDate today) {
        if (today == null) return false;
        LocalDate since = today.minusDays(29);
        for (Claim claim : claims) {
            if (!getId().equals(claim.getPersonId()) || !claim.getStatus().isOpen() || !counts(claim, today)) continue;
            if (!claim.getDate().isBefore(since) && !claim.getDate().isAfter(today)) return true;
        }
        return false;
    }

    /** Moved on by each save; an edit is refused unless made from the current version. */
    public long getVersion() { return version; }
}
