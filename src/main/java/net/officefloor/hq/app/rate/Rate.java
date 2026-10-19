package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/** A rate for a grade on a sheet: a percent with two decimals, in force from its from-date (or from the
 * beginning of time when it has none), and applying through its to-date when it has one. */
@Entity
@Table(name = "rate")
public class Rate {

    /** Rates by from-date, the one with no from-date first. */
    public static final Comparator<Rate> BY_FROM =
            Comparator.comparing(Rate::getFrom, Comparator.nullsFirst(Comparator.naturalOrder()));

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "grade", nullable = false)
    private String grade;

    @Column(name = "from_date")
    private LocalDate from;

    @Column(name = "to_date")
    private LocalDate to;

    @Column(name = "rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal rate;

    protected Rate() {}

    public Rate(String sheetId, String grade, LocalDate from, LocalDate to, BigDecimal rate) {
        this.sheetId = sheetId;
        this.grade = grade;
        this.from = from;
        this.to = to;
        this.rate = rate;
    }

    /** Of a grade's rates, the one in force on a date: the latest from-date on or before it, provided the date
     * is not after its to-date; none when the date is before every one, or after the to-date of that one. */
    public static Optional<Rate> inForce(Collection<Rate> rates, LocalDate date) {
        return rates.stream()
                .filter(r -> r.getFrom() == null || !r.getFrom().isAfter(date))
                .max(BY_FROM)
                .filter(r -> r.getTo() == null || !date.isAfter(r.getTo()));
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getGrade() {
        return grade;
    }

    public LocalDate getFrom() {
        return from;
    }

    public LocalDate getTo() {
        return to;
    }

    public void setTo(LocalDate to) {
        this.to = to;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public void setRate(BigDecimal rate) {
        this.rate = rate;
    }
}
