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

/** A person on a sheet, with a name (unique on the sheet, ignoring case) and a grade (A, B or C). */
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

    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    @Column(name = "held", nullable = false)
    private boolean held;

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

    /** The date the person is retired from; null while they are not retired. */
    public LocalDate getRetiredFrom() {
        return retiredFrom;
    }

    public void setRetiredFrom(LocalDate retiredFrom) {
        this.retiredFrom = retiredFrom;
    }

    /** A person with a retirement date set is retired at once, whatever the date. */
    public boolean isRetired() {
        return retiredFrom != null;
    }

    /** Whether the person is on hold: while held, their open claims count in no live total. */
    public boolean isHeld() {
        return held;
    }

    public void setHeld(boolean held) {
        this.held = held;
    }

    /** Whether the claim of the given status counts toward the person's live totals: an open one not while held. */
    public boolean countsLive(ClaimStatus status) {
        return status.counts() && !(held && status == ClaimStatus.OPEN);
    }

    /** The version of the person as stored, raised on each save. */
    public long getVersion() {
        return version;
    }
}
