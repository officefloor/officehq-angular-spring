package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;
import java.util.Comparator;

/** A hold on a person from its from-date to its to-date, both inclusive; a null to-date does not end. */
@Embeddable
public class Hold {

    /** Orders holds by the date they start. */
    public static final Comparator<Hold> BY_FROM = Comparator.comparing(Hold::getFromDate);

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date")
    private LocalDate toDate;

    protected Hold() {
    }

    public Hold(LocalDate fromDate, LocalDate toDate) {
        this.fromDate = fromDate;
        this.toDate = toDate;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    /** Whether {@code date} is within the hold. */
    public boolean covers(LocalDate date) {
        return !date.isBefore(fromDate) && (toDate == null || !date.isAfter(toDate));
    }
}
