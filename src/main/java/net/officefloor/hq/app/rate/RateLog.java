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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One setting of a grade's rate: its from- and to-dates and percent as set, and the sheet's today when it was set. */
@Entity
@Table(name = "rate_log")
public class RateLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "grade", nullable = false)
    private String grade;

    @Column(name = "from_date")
    private LocalDate fromDate;

    @Column(name = "to_date")
    private LocalDate toDate;

    @Column(name = "pct", nullable = false, precision = 7, scale = 2)
    private BigDecimal pct;

    @Column(name = "set_on", nullable = false)
    private LocalDate setOn;

    /** As the rate's: set when the setting was forward-only. */
    @Column(name = "forward_after")
    private Long forwardAfter;

    protected RateLog() {
    }

    RateLog(Rate rate, LocalDate setOn) {
        this.sheetId = rate.getSheetId();
        this.grade = rate.getGrade();
        this.fromDate = rate.getFromDate();
        this.toDate = rate.getToDate();
        this.pct = rate.getPct();
        this.setOn = setOn;
        this.forwardAfter = rate.getForwardAfter();
    }

    /** A copy of the log line on another sheet. */
    public RateLog copyTo(String sheetId) {
        RateLog copy = new RateLog();
        copy.sheetId = sheetId;
        copy.grade = grade;
        copy.fromDate = fromDate;
        copy.toDate = toDate;
        copy.pct = pct;
        copy.setOn = setOn;
        copy.forwardAfter = forwardAfter;
        return copy;
    }

    public Long getId() {
        return id;
    }

    public String getGrade() {
        return grade;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    public BigDecimal getPct() {
        return pct;
    }

    public LocalDate getSetOn() {
        return setOn;
    }

    public Long getForwardAfter() {
        return forwardAfter;
    }

    /**
     * A grade's rates on a sheet as they stood at the end of {@code asOf}: each from-date's last setting made while the
     * sheet's today was on or before it (a rate set before settings were logged counts as it is now).
     *
     * @param now the grade's rates as they are now
     * @param set the grade's rate settings, in the order they were made
     */
    public static List<Rate> stood(String sheetId, String grade, List<Rate> now, List<RateLog> set, LocalDate asOf) {
        Map<LocalDate, List<RateLog>> then = new LinkedHashMap<>();
        for (RateLog log : set) {
            if (!log.getSetOn().isAfter(asOf)) {
                then.computeIfAbsent(log.getFromDate(), d -> new ArrayList<>()).add(log);
            }
        }
        List<Rate> stood = new ArrayList<>();
        for (Rate rate : now) {
            boolean logged = set.stream().anyMatch(l -> Objects.equals(l.getFromDate(), rate.getFromDate()));
            if (!logged) {
                stood.add(rate);
            }
        }
        for (List<RateLog> logs : then.values()) {
            // The last setting stood; the ones before it are what a claim that does not see it falls back on.
            RateLog log = logs.get(logs.size() - 1);
            Rate rate = new Rate(sheetId, grade, log.getFromDate(), log.getPct());
            rate.setToDate(log.getToDate());
            rate.setForwardAfter(log.getForwardAfter());
            List<RateVersion> older = new ArrayList<>();
            for (int i = 0; i < logs.size() - 1; i++) {
                RateLog earlier = logs.get(i);
                older.add(new RateVersion(i + 1, earlier.getPct(), earlier.getToDate(), earlier.getForwardAfter()));
            }
            rate.setOlder(older);
            stood.add(rate);
        }
        return stood;
    }
}
