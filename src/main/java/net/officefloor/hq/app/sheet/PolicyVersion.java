package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/**
 * A version of the sheet's policy settings: made by a change to one of them, numbered from 1 in the
 * order made. It is in force on its from-date and after; one with no from-date is in force always.
 */
@Embeddable
public class PolicyVersion {

    @Column(name = "version_number", nullable = false)
    private int number;

    @Column(name = "setting_key", nullable = false)
    private String key;

    /** The date the change was recorded from; null when in force always. */
    @Column(name = "from_date")
    private LocalDate fromDate;

    protected PolicyVersion() {
    }

    public PolicyVersion(int number, String key, LocalDate fromDate) {
        this.number = number;
        this.key = key;
        this.fromDate = fromDate;
    }

    public int getNumber() {
        return number;
    }

    public String getKey() {
        return key;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    /** Whether the version is in force on a date: its from-date is on or before it, or it has none. */
    public boolean inForceOn(LocalDate date) {
        return fromDate == null || !fromDate.isAfter(date);
    }
}
