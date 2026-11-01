package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A policy version of the sheet: made by a change to a policy setting (its key), from the from-date it was changed
 *  from (null: from the beginning of time). */
@Embeddable
public class PolicyVersion {

    @Column(name = "setting_key", nullable = false)
    private String key;

    @Column(name = "from_date")
    private LocalDate from;

    protected PolicyVersion() {
    }

    public PolicyVersion(String key, LocalDate from) {
        this.key = key;
        this.from = from;
    }

    public String getKey() {
        return key;
    }

    public LocalDate getFrom() {
        return from;
    }

    /** Whether it counts on this day: it has no from-date, or its from-date is on or before it. */
    boolean startedBy(LocalDate date) {
        return from == null || !from.isAfter(date);
    }
}
