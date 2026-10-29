package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

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

    /** The number of this, the newest version, counting from 1; the older ones are kept as {@link RateVersion}s. */
    @Column(name = "version", nullable = false)
    private int version = 1;

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

    public int getVersion() {
        return version;
    }

    /** Replaces this version with a newer one of the percent and to-date; the caller keeps the one replaced. */
    public void newVersion(BigDecimal pct, LocalDate to) {
        this.version++;
        this.pct = pct;
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

    /**
     * One grade's rates as they apply through a chain of sheets: on each date, the rate the first level has in force
     * then (the sheet's own, then its parent's, and so on). Given as rates that do not overlap, so the one in force on
     * a date among them is the one the chain has; with one level, its rates as they are. Not to be saved.
     */
    public static List<Rate> chained(String sheetId, String grade, List<? extends Collection<Rate>> levels) {
        if (levels.size() == 1) {
            return List.copyOf(levels.get(0));
        }
        // the chain's rate can only change on a from-date, or the day after a to-date
        TreeSet<LocalDate> starts = new TreeSet<>();
        starts.add(LocalDate.MIN);
        for (Collection<Rate> level : levels) {
            for (Rate r : level) {
                if (r.getFrom() != null) {
                    starts.add(r.getFrom());
                }
                if (r.getTo() != null && r.getTo().isBefore(LocalDate.MAX)) {
                    starts.add(r.getTo().plusDays(1));
                }
            }
        }
        List<Rate> chained = new ArrayList<>();
        Rate last = null;
        for (LocalDate start : starts) {
            BigDecimal pct = levels.stream().map(level -> inForce(level, start)).filter(Objects::nonNull)
                    .map(Rate::getPct).findFirst().orElse(null);
            LocalDate end = starts.higher(start) == null ? null : starts.higher(start).minusDays(1);
            if (pct == null) {
                last = null;
            } else if (last != null && last.getPct().compareTo(pct) == 0) {
                last.setTo(end);
            } else {
                last = new Rate(sheetId, grade, start.equals(LocalDate.MIN) ? null : start, pct);
                last.setTo(end);
                chained.add(last);
            }
        }
        return chained;
    }
}
