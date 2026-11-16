package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;
import java.util.Objects;

/** A version of the sheet's policy: the change to one of its policy settings (see
 *  {@link Sheet#POLICY_KEYS}) that made it, numbered from 1 in the order made, with the from-date
 *  the changed value is in force from (null: none, the version counts on every date). */
@Embeddable
public class PolicyVersion {

    @Column(name = "number", nullable = false)
    private int number;

    @Column(name = "setting_key", nullable = false)
    private String key;

    @Column(name = "from_date")
    private LocalDate from;

    protected PolicyVersion() {
    }

    public PolicyVersion(int number, String key, LocalDate from) {
        this.number = number;
        this.key = key;
        this.from = from;
    }

    public int getNumber() {
        return number;
    }

    public String getKey() {
        return key;
    }

    public LocalDate getFrom() {
        return from;
    }

    /** Whether the version is in force on a date: it has no from-date, or one on or before it. */
    public boolean countsOn(LocalDate on) {
        return from == null || !from.isAfter(on);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PolicyVersion other && number == other.number && key.equals(other.key)
                && Objects.equals(from, other.from);
    }

    @Override
    public int hashCode() {
        return Objects.hash(number, key, from);
    }
}
