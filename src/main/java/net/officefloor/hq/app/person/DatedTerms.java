package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A person's own terms from their from-date; null terms: none of their own from then. */
@Embeddable
public class DatedTerms {

    @Column(name = "from_date", nullable = false)
    private LocalDate from;

    @Column(name = "terms")
    private Integer terms;

    /** The sheet's today when the terms were recorded; null: before that was kept. */
    @Column(name = "made_on")
    private LocalDate madeOn;

    protected DatedTerms() {
    }

    public DatedTerms(Integer terms, LocalDate from, LocalDate madeOn) {
        this.terms = terms;
        this.from = from;
        this.madeOn = madeOn;
    }

    /** Whether the terms were recorded while the sheet's today was on or before the day. */
    public boolean madeBy(LocalDate day) {
        return madeOn == null || !madeOn.isAfter(day);
    }

    public LocalDate getFrom() {
        return from;
    }

    public Integer getTerms() {
        return terms;
    }
}
