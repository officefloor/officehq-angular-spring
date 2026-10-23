package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/**
 * A version of the sheet's policy settings: made by a change to one of them, numbered from 1 in the
 * order made, with the value the change set (null: none). It is in force on its from-date and after;
 * one with no from-date is in force always.
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

    /** The value the change set the setting to; null for none. */
    @Column(name = "setting_value")
    private String value;

    protected PolicyVersion() {
    }

    public PolicyVersion(int number, String key, LocalDate fromDate, String value) {
        this.number = number;
        this.key = key;
        this.fromDate = fromDate;
        this.value = value;
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

    public String getValue() {
        return value;
    }

    /** Whether the version is in force on a date: its from-date is on or before it, or it has none. */
    public boolean inForceOn(LocalDate date) {
        return fromDate == null || !fromDate.isAfter(date);
    }
}
