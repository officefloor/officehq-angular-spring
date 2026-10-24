package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;
import java.util.Objects;

/** A past retirement of a person, from {@code from} to {@code to}, the day they were unretired. */
@Embeddable
public class PersonRetirement {

    @Column(name = "retired_from", nullable = false)
    private LocalDate from;

    @Column(name = "retired_to", nullable = false)
    private LocalDate to;

    protected PersonRetirement() {
    }

    public PersonRetirement(LocalDate from, LocalDate to) {
        this.from = from;
        this.to = to;
    }

    public LocalDate getFrom() {
        return from;
    }

    public LocalDate getTo() {
        return to;
    }

    /** Whether {@code date} falls after the from-date and before the to-date. */
    public boolean excludes(LocalDate date) {
        return date.isAfter(from) && date.isBefore(to);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PersonRetirement other && Objects.equals(from, other.from) && Objects.equals(to, other.to);
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, to);
    }
}
