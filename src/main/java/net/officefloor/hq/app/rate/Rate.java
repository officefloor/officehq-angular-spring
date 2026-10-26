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

/**
 * A rate for a grade on a sheet: a percent with two decimals, 0.00 or more, applying from its from-date (null: from
 * the beginning of time) through its to-date (null: with no end).
 */
@Entity
@Table(name = "rate")
public class Rate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "grade", nullable = false)
    private String grade;

    @Column(name = "pct", nullable = false, precision = 7, scale = 2)
    private BigDecimal pct;

    @Column(name = "from_date")
    private LocalDate from;

    @Column(name = "to_date")
    private LocalDate to;

    protected Rate() {
    }

    public Rate(String sheetId, String grade, LocalDate from, BigDecimal pct) {
        this.sheetId = sheetId;
        this.grade = grade;
        this.from = from;
        this.pct = pct;
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

    public BigDecimal getPct() {
        return pct;
    }

    public void setPct(BigDecimal pct) {
        this.pct = pct;
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

    /**
     * The rate in force on the date among one grade's rates: the one with the latest from-date on or before it, unless
     * the date is past that one's to-date; null with none.
     */
    public static Rate inForce(Collection<Rate> rates, LocalDate date) {
        Rate best = rates.stream().filter(r -> r.getFrom() == null || !r.getFrom().isAfter(date))
                .max(Comparator.comparing(Rate::getFrom,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
        return best == null || (best.getTo() != null && best.getTo().isBefore(date)) ? null : best;
    }
}
