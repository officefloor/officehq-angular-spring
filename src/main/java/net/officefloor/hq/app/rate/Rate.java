package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongUnaryOperator;
import net.officefloor.hq.app.history.History;

/** The sheet's rate for a grade from a date (or from the beginning of time when it has none),
 *  perhaps through a to-date: a percent (0.00 or more, two decimals). */
@Entity
@EntityListeners(History.class)
@Table(name = "rate")
public class Rate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "grade", nullable = false)
    private String grade;

    /** The first day the rate applies; none when it applies from the beginning of time. */
    @Column(name = "from_date")
    private LocalDate fromDate;

    /** The last day the rate applies; none when it applies on for good. */
    @Column(name = "to_date")
    private LocalDate toDate;

    @Column(name = "rate", nullable = false)
    private BigDecimal rate;

    /** How many versions the rate has had: this, the newest, and the older ones it replaced. */
    @Column(name = "version", nullable = false)
    private int version = 1;

    /** What the claims already on the sheet when a forward-only version was saved use instead of it, oldest
     *  first, as 'upTo:rate:to' entries separated by ';' (see {@link Earlier}); none when the rate applies to
     *  every claim. */
    @Column(name = "earlier")
    private String earlier;

    /** What the claims whose id is at most {@code upTo} (and above the entry before's) use of this grade and
     *  from-date: a rate through a to-date, or none ({@code rate} null). */
    public record Earlier(long upTo, BigDecimal rate, LocalDate toDate) {

        static List<Earlier> parse(String text) {
            List<Earlier> list = new ArrayList<>();
            if (text == null || text.isEmpty()) {
                return list;
            }
            for (String entry : text.split(";")) {
                String[] f = entry.split(":", -1);
                list.add(new Earlier(Long.parseLong(f[0]), "-".equals(f[1]) ? null : new BigDecimal(f[1]),
                        f[2].isEmpty() ? null : LocalDate.parse(f[2])));
            }
            return list;
        }

        static String format(List<Earlier> list) {
            if (list.isEmpty()) {
                return null;
            }
            List<String> entries = new ArrayList<>();
            for (Earlier e : list) {
                entries.add(e.upTo() + ":" + (e.rate() == null ? "-" : e.rate().toPlainString()) + ":"
                        + (e.toDate() == null ? "" : e.toDate().toString()));
            }
            return String.join(";", entries);
        }
    }

    protected Rate() {
    }

    public Rate(String sheetId, String grade, LocalDate fromDate, LocalDate toDate, BigDecimal rate) {
        this.sheetId = sheetId;
        this.grade = grade;
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.rate = rate;
    }

    /** A rate saved forward-only: the claims whose id is at most {@code forwardAfter} have none of this grade
     *  and from-date. */
    public Rate(String sheetId, String grade, LocalDate fromDate, LocalDate toDate, BigDecimal rate, long forwardAfter) {
        this(sheetId, grade, fromDate, toDate, rate);
        this.earlier = Earlier.format(List.of(new Earlier(forwardAfter, null, null)));
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

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    public void setToDate(LocalDate toDate) {
        this.toDate = toDate;
    }

    /** Whether the rate still applies on a date on or after its from-date: not past its to-date. */
    public boolean coversThrough(LocalDate date) {
        return toDate == null || !date.isAfter(toDate);
    }

    public BigDecimal getRate() {
        return rate;
    }

    public void setRate(BigDecimal rate) {
        this.rate = rate;
    }

    public int getVersion() {
        return version;
    }

    /** What the claims already on the sheet when a forward-only version was saved use instead, oldest first. */
    public List<Earlier> getEarlier() {
        return Earlier.parse(earlier);
    }

    /** Replace the rate with a newer version, through a to-date; the caller keeps the one it replaces. The new
     *  version applies to every claim. */
    public void newVersion(BigDecimal rate, LocalDate toDate) {
        this.rate = rate;
        this.toDate = toDate;
        this.version++;
        this.earlier = null;
    }

    /** Replace the rate with a newer forward-only version: the claims whose id is at most {@code forwardAfter}
     *  keep using what they used before. */
    public void newVersion(BigDecimal rate, LocalDate toDate, long forwardAfter) {
        List<Earlier> list = getEarlier();
        long last = list.isEmpty() ? Long.MIN_VALUE : list.get(list.size() - 1).upTo();
        if (forwardAfter > last) {
            list.add(new Earlier(forwardAfter, this.rate, this.toDate));
        }
        this.rate = rate;
        this.toDate = toDate;
        this.version++;
        this.earlier = Earlier.format(list);
    }

    /** The earlier entries as stored, for an older version to keep. */
    String earlierText() {
        return earlier;
    }

    /** Withdraw the newest version: the older one it replaced (its value, to-date and earlier entries) is
     *  the rate again. */
    public void withdrawTo(RateVersion previous) {
        this.rate = previous.getRate();
        this.toDate = previous.getToDate();
        this.version = previous.getVersion();
        this.earlier = previous.getEarlier();
    }

    /** A rate's earlier entries as stored, the claim id each names mapped (a clone's claims have new ids). */
    public static String remapEarlier(String earlier, LongUnaryOperator map) {
        List<Earlier> list = new ArrayList<>();
        for (Earlier e : Earlier.parse(earlier)) {
            list.add(new Earlier(map.applyAsLong(e.upTo()), e.rate(), e.toDate()));
        }
        return Earlier.format(list);
    }

    /** The rate as it applies to a claim (none given: one added now): this one, or what an earlier claim
     *  uses instead of a forward-only version, or none when the claim has none of this grade and from-date. */
    public Optional<Rate> forClaim(Long claimId) {
        if (claimId != null && earlier != null) {
            for (Earlier e : getEarlier()) {
                if (claimId <= e.upTo()) {
                    return e.rate() == null ? Optional.empty()
                            : Optional.of(new Rate(sheetId, grade, fromDate, e.toDate(), e.rate()));
                }
            }
        }
        return Optional.of(this);
    }
}
