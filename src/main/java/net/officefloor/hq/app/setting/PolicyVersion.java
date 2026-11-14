package net.officefloor.hq.app.setting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A version of a sheet's policy settings (the rounding mode, the floor, the cap, the terms, the late
 *  percent and the clerk limit): every change to one of them is a new version, numbered from 1 in the
 *  order made. It is in force from its from-date; one with no from-date is in force always. */
@Entity
@Table(name = "policy_version")
public class PolicyVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "version_number", nullable = false)
    private int number;

    @Column(name = "from_date")
    private LocalDate from;

    /** The sheet's today when the version was made. */
    @Column(name = "made_on", nullable = false, updatable = false)
    private LocalDate madeOn;

    protected PolicyVersion() {
    }

    public PolicyVersion(String sheetId, int number, LocalDate from, LocalDate madeOn) {
        this.sheetId = sheetId;
        this.number = number;
        this.from = from;
        this.madeOn = madeOn;
    }

    public int getNumber() {
        return number;
    }

    public LocalDate getFrom() {
        return from;
    }

    public LocalDate getMadeOn() {
        return madeOn;
    }

    /** Whether the version is in force on a date: it has no from-date, or one on or before it. */
    public boolean inForceOn(LocalDate date) {
        return from == null || !from.isAfter(date);
    }
}
