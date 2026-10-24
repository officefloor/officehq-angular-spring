package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;
import java.util.Objects;

/** A hold a person was put on, from {@code from} to {@code to} (both included); a null to-date has no end. */
@Embeddable
public class PersonHold {

    @Column(name = "hold_from", nullable = false)
    private LocalDate from;

    @Column(name = "hold_to")
    private LocalDate to;

    protected PersonHold() {
    }

    public PersonHold(LocalDate from, LocalDate to) {
        this.from = from;
        this.to = to;
    }

    public LocalDate getFrom() {
        return from;
    }

    public LocalDate getTo() {
        return to;
    }

    /** Whether {@code date} falls within the hold. */
    public boolean covers(LocalDate date) {
        return date != null && !date.isBefore(from) && (to == null || !date.isAfter(to));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PersonHold other && Objects.equals(from, other.from) && Objects.equals(to, other.to);
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, to);
    }
}
