package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import net.officefloor.hq.app.claim.ClaimStatus;

/** A person on a sheet: a name and a grade (A, B or C), and the date they are retired from, if any;
 * and whether they are on hold. */
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

    @Column(name = "grade", nullable = false)
    private String grade;

    /** Set once retired: the person counts as retired at once, whatever the date. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** While held, the person's OPEN claims count in no live total. */
    @Column(name = "held", nullable = false)
    private boolean held;

    /** Bumped on every save; an edit must carry the version it was made from. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {
    }

    public Person(String sheetId, String name, String grade) {
        this.sheetId = sheetId;
        this.name = name;
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

    public void setName(String name) {
        this.name = name;
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

    public void setRetiredFrom(LocalDate retiredFrom) {
        this.retiredFrom = retiredFrom;
    }

    public boolean isRetired() {
        return retiredFrom != null;
    }

    public boolean isHeld() {
        return held;
    }

    public void setHeld(boolean held) {
        this.held = held;
    }

    /** Whether a claim of this person in the given status counts toward the live totals. */
    public boolean counts(ClaimStatus status) {
        return status.counts() && !(held && status == ClaimStatus.OPEN);
    }

    public long getVersion() {
        return version;
    }
}
