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
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimStatus;

/** A person on a sheet: a name and a grade (A, B or C), dated: each grade is in force from its from-date. */
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

    /** The person's place in the sheet's list: the list is in ascending position. */
    @Column(name = "position", nullable = false)
    private int position;

    /** The sheet the person was last transferred from; null when they were never transferred. */
    @Column(name = "origin_sheet_id")
    private String originSheetId;

    /** The grades recorded for the person; the first given has no from-date (from the beginning of time). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_grade", joinColumns = @JoinColumn(name = "person_id"))
    private List<GradeFrom> grades = new ArrayList<>();

    /** The person's own terms in days, ahead of their grade's and the sheet's; null when they have none. */
    @Column(name = "terms")
    private Integer terms;

    /** The person's contact, trimmed and lower-cased; null when they have none. */
    @Column(name = "contact")
    private String contact;

    /** The date the person is retired from; null while they are not retired. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** The retirements the person was unretired from: a claim dated within one is refused. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_past_retirement", joinColumns = @JoinColumn(name = "person_id"))
    private List<PastRetirement> pastRetirements = new ArrayList<>();

    /** The person's holds: their open claims dated within one, and not bound by a statement, count in no live
     *  total. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_hold", joinColumns = @JoinColumn(name = "person_id"))
    private List<Hold> holds = new ArrayList<>();

    /** The other names the person is found by, stored trimmed. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_alias", joinColumns = @JoinColumn(name = "person_id"))
    @Column(name = "alias", nullable = false)
    private Set<String> aliases = new HashSet<>();

    /** Raised on every save; an edit made from an older version is refused. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {
    }

    public Person(String sheetId, String name, String grade) {
        this.sheetId = sheetId;
        this.name = name;
        this.grades.add(new GradeFrom(null, grade));
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    /** Move the person to another sheet, leaving their holds behind; the sheet they leave becomes their origin. */
    public void transferTo(String sheetId) {
        this.originSheetId = this.sheetId;
        this.sheetId = sheetId;
        this.holds.clear();
    }

    public String getOriginSheetId() {
        return originSheetId;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** The grades recorded, in the order they take effect. */
    public List<GradeFrom> getGrades() {
        return grades.stream().sorted(GradeFrom.BY_FROM).toList();
    }

    /** The grade in force on {@code date}: the one with the latest from-date on or before it. A null date
     *  takes the latest grade recorded. */
    public String gradeOn(LocalDate date) {
        return grades.stream()
                .filter(g -> date == null || g.getFromDate() == null || !g.getFromDate().isAfter(date))
                .max(GradeFrom.BY_FROM)
                .orElseGet(() -> grades.stream().min(GradeFrom.BY_FROM).orElseThrow())
                .getGrade();
    }

    /** Record {@code grade} from {@code from}, replacing the grade recorded from that same date, if any. */
    public void setGradeFrom(LocalDate from, String grade) {
        grades.removeIf(g -> Objects.equals(g.getFromDate(), from));
        grades.add(new GradeFrom(from, grade));
    }

    public Integer getTerms() {
        return terms;
    }

    public void setTerms(Integer terms) {
        this.terms = terms;
    }

    public String getContact() {
        return contact;
    }

    public void setContact(String contact) {
        this.contact = contact;
    }

    /** The person's aliases, sorted as names are compared: without regard to case. */
    public List<String> getAliases() {
        return aliases.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public void addAlias(String alias) {
        aliases.add(alias);
    }

    public long getVersion() {
        return version;
    }

    public LocalDate getRetiredFrom() {
        return retiredFrom;
    }

    public void setRetiredFrom(LocalDate retiredFrom) {
        this.retiredFrom = retiredFrom;
    }

    /** Retired as soon as a retirement date is set, whatever the date. */
    public boolean isRetired() {
        return retiredFrom != null;
    }

    /** The retirements the person was unretired from, in the order they started. */
    public List<PastRetirement> getPastRetirements() {
        return pastRetirements.stream().sorted(PastRetirement.BY_FROM).toList();
    }

    public void addPastRetirement(PastRetirement retirement) {
        pastRetirements.add(retirement);
    }

    /** End the retirement on {@code on}: it is kept as a past retirement, and the person is no longer retired. */
    public void unretire(LocalDate on) {
        pastRetirements.add(new PastRetirement(retiredFrom, on));
        retiredFrom = null;
    }

    /** Whether the person has any hold, whatever its dates. */
    public boolean isHeld() {
        return !holds.isEmpty();
    }

    /** The holds recorded, in the order they start. */
    public List<Hold> getHolds() {
        return holds.stream().sorted(Hold.BY_FROM).toList();
    }

    public void addHold(Hold hold) {
        holds.add(hold);
    }

    /** Whether a claim of this person counts in the live totals on the sheet's {@code today}: an OPEN or PENDING one
     *  dated within a hold, or any once {@code today} reaches the retirement date, does not, unless a statement
     *  binds it. */
    public boolean counts(Claim claim, LocalDate today) {
        return claim.getStatus().counts() && (claim.isStated() || !heldOn(claim, today));
    }

    /** Whether a claim of this person counts in the figures of a period ending {@code end}: by the status it had at
     *  the end of that day, left out when held or retired as the live totals on the sheet's {@code today} say. */
    public boolean countsAtEnd(Claim claim, LocalDate end, LocalDate today) {
        ClaimStatus at = claim.statusAt(end);
        return at != null && at.counts() && (claim.isStated() || !heldOn(claim, today));
    }

    /** Whether a claim of this person counts when no statement binds it, as a new statement finds it. */
    public boolean countsUnbound(Claim claim, LocalDate today) {
        return claim.getStatus().counts() && !heldOn(claim, today);
    }

    /** Whether the claim is open and dated within one of the person's holds, or open once {@code today} reaches
     *  the person's retirement date. */
    private boolean heldOn(Claim claim, LocalDate today) {
        return claim.getStatus().isOpen()
                && (retiredOn(today) || holds.stream().anyMatch(h -> h.covers(claim.getDate())));
    }

    /** Whether a hold of the person is in force on {@code date}: one whose dates include it. */
    public boolean heldOn(LocalDate date) {
        return date != null && holds.stream().anyMatch(h -> h.covers(date));
    }

    /** Whether the retirement date is reached on {@code date}: set, and on or before it. */
    public boolean retiredOn(LocalDate date) {
        return retiredFrom != null && date != null && !date.isBefore(retiredFrom);
    }

    /** Whether a claim dated {@code date} is refused: one dated after the retirement date, or after the from-date
     *  and before the to-date of a past retirement. */
    public boolean refusesClaimOn(LocalDate date) {
        return (retiredFrom != null && date.isAfter(retiredFrom))
                || pastRetirements.stream().anyMatch(r -> r.refusesClaimOn(date));
    }
}
