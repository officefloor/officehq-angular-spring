package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.Locale;
import net.officefloor.hq.app.claim.ClaimStatus;

/** A person on a sheet. */
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

    /** The name as compared for uniqueness on the sheet: trimmed and lower-cased. */
    @Column(name = "name_key", nullable = false)
    private String nameKey;

    @Column(name = "grade", nullable = false)
    private String grade;

    /** The date the person is retired from; null while not retired. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** Whether the person is on hold: their OPEN claims then count in no live total. */
    @Column(name = "held", nullable = false)
    private boolean held;

    /** Raised by one on each save; a save of an older version is refused. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {
    }

    public Person(String sheetId, String name, String grade) {
        this.sheetId = sheetId;
        setName(name);
        this.grade = grade;
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

    /** Keeps the name with the spaces before and after it removed. */
    public void setName(String name) {
        this.name = name.trim();
        this.nameKey = key(name);
    }

    /** The key a name is compared on: two names with the same key are the same name. */
    public static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }

    public LocalDate getRetiredFrom() {
        return retiredFrom;
    }

    /** A person with a retirement date counts as retired at once, whatever the date. */
    public boolean isRetired() {
        return retiredFrom != null;
    }

    public void setRetiredFrom(LocalDate retiredFrom) {
        this.retiredFrom = retiredFrom;
    }

    public boolean isHeld() {
        return held;
    }

    public void setHeld(boolean held) {
        this.held = held;
    }

    /** Whether a claim of the person in the status counts in the live totals: an OPEN one not while held. */
    public boolean counts(ClaimStatus status) {
        return status.counts() && !(held && status == ClaimStatus.OPEN);
    }

    /** Refused when the person is retired and the date is after their retirement date. */
    public void requireClaimable(LocalDate date) {
        if (retiredFrom != null && date.isAfter(retiredFrom)) {
            throw new PersonRetiredException(name, retiredFrom, date);
        }
    }

    public long getVersion() {
        return version;
    }
}
