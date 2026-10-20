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

    /** The sheet's today when the hold was added; none when not kept. */
    @Column(name = "made_on")
    private LocalDate madeOn;

    protected Hold() {}

    public Hold(LocalDate from, LocalDate to, LocalDate madeOn) {
        this.from = from;
        this.to = to;
        this.madeOn = madeOn;
    }

    /** Whether the hold was added while the sheet's today was on or before a date (one not kept was). */
    public boolean madeBy(LocalDate date) {
        return madeOn == null || !madeOn.isAfter(date);
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
