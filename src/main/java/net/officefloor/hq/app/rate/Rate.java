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
 * beginning of time when it has none). */
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

    @Column(name = "rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal rate;

    protected Rate() {}

    public Rate(String sheetId, String grade, LocalDate from, BigDecimal rate) {
        this.sheetId = sheetId;
        this.grade = grade;
        this.from = from;
        this.rate = rate;
    }

    /** Of a grade's rates, the one in force on a date: the latest from-date on or before it; none when the
     * date is before every one. */
    public static Optional<Rate> inForce(Collection<Rate> rates, LocalDate date) {
        return rates.stream()
                .filter(r -> r.getFrom() == null || !r.getFrom().isAfter(date))
                .max(BY_FROM);
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

    public BigDecimal getRate() {
        return rate;
    }

    public void setRate(BigDecimal rate) {
        this.rate = rate;
    }
}
