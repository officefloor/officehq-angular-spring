package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;
import java.util.Comparator;

/** A retirement a person was unretired from: retired from its from-date, unretired on its to-date. */
@Embeddable
public class PastRetirement {

    /** Orders past retirements by the date they started. */
    public static final Comparator<PastRetirement> BY_FROM =
            Comparator.comparing(PastRetirement::getFromDate).thenComparing(PastRetirement::getToDate);

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    protected PastRetirement() {
    }

    public PastRetirement(LocalDate fromDate, LocalDate toDate) {
        this.fromDate = fromDate;
        this.toDate = toDate;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    /** Whether a claim dated {@code date} is refused: one after the from-date and before the to-date. */
    public boolean refusesClaimOn(LocalDate date) {
        return date.isAfter(fromDate) && date.isBefore(toDate);
    }
}
