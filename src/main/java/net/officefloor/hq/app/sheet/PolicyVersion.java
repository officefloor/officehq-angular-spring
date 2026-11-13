package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A version of the sheet's policy: a change to one of its policy settings (the rounding mode, the floor, the
 *  cap, the terms, the late percent or the clerk limit), with the from-date the change took; none counts
 *  always, and the value it set (none: blank). Numbered from 1 in the order made by its place in the sheet's
 *  list. */
@Embeddable
public class PolicyVersion {

    @Column(name = "setting_key", nullable = false)
    private String key;

    @Column(name = "from_date")
    private LocalDate fromDate;

    @Column(name = "setting_value")
    private String value;

    protected PolicyVersion() {
    }

    PolicyVersion(String key, LocalDate fromDate, String value) {
        this.key = key;
        this.fromDate = fromDate;
        this.value = value;
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

    /** Whether the version is in force on a date: it has no from-date, or one on or before it. */
    public boolean countsOn(LocalDate date) {
        return fromDate == null || !fromDate.isAfter(date);
    }
}
