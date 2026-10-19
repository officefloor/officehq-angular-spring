package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;
import java.util.Comparator;

/** A hold on a person from a from-date to a to-date, inclusive; open-ended when it has no to-date. */
@Embeddable
public class Hold {

    /** Holds by from-date, then to-date, an open-ended one last. */
    public static final Comparator<Hold> BY_FROM = Comparator.comparing(Hold::getFrom)
            .thenComparing(Hold::getTo, Comparator.nullsLast(Comparator.naturalOrder()));

    @Column(name = "hold_from", nullable = false)
    private LocalDate from;

    @Column(name = "hold_to")
    private LocalDate to;

    protected Hold() {}

    public Hold(LocalDate from, LocalDate to) {
        this.from = from;
        this.to = to;
    }

    public LocalDate getFrom() {
        return from;
    }

    public LocalDate getTo() {
        return to;
    }

    /** Whether a date is within the hold. */
    public boolean covers(LocalDate date) {
        return !date.isBefore(from) && (to == null || !date.isAfter(to));
    }
}
