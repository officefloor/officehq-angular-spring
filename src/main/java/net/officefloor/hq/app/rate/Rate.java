package net.officefloor.hq.app.rate;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.history.HistoryListener;

/** One of the sheet's rates for a grade: a percent with two decimals, in force from its from-date
 *  (from the beginning of time when it has none) through its to-date (for good when it has none).
 *  A version saved forward-only (its from-date on or after the sheet's today then) applies only to
 *  claims added after it was saved; a claim added before falls back to the earlier versions. */
@Entity
@EntityListeners(HistoryListener.class)
@Table(name = "rate")
public class Rate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "grade", nullable = false)
    private String grade;

    @Column(name = "pct", nullable = false, precision = 9, scale = 2)
    private BigDecimal pct;

    @Column(name = "from_date")
    private LocalDate from;

    @Column(name = "to_date")
    private LocalDate to;

    @Column(name = "version", nullable = false)
    private int version = 1;

    /** For a forward-only newest version, the highest claim id there was when it was saved: only
     *  claims added after that apply it. Null: it applies to every claim. */
    @Column(name = "after_claim")
    private Long afterClaim;

    /** The versions a claim added before the newest (forward-only) one applies instead, newest
     *  first, up to and including the first that applies to every claim; empty when the newest
     *  applies to every claim. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "rate_earlier", joinColumns = @JoinColumn(name = "rate_id"))
    @OrderColumn(name = "position")
    private List<Earlier> earlier = new ArrayList<>();

    /** An earlier version of a rate as a claim added before a later forward-only one applies it:
     *  its percent and to-date, and (when it was itself forward-only) the highest claim id there was
     *  when it was saved. */
    @Embeddable
    public record Earlier(
            @Column(name = "pct", nullable = false, precision = 9, scale = 2) BigDecimal pct,
            @Column(name = "to_date") LocalDate to,
            @Column(name = "after_claim") Long afterClaim) {

        /** Whether the version applies to a claim added as {@code added} (null: one added now). */
        public boolean appliesTo(Long added) {
            return afterClaim == null || added == null || added > afterClaim;
        }
    }

    protected Rate() {
    }

    public Rate(String sheetId, String grade, LocalDate from, LocalDate to, BigDecimal pct) {
        this.sheetId = sheetId;
        this.grade = grade;
        this.from = from;
        this.to = to;
        this.pct = pct;
    }

    public Rate(String sheetId, String grade, LocalDate from, LocalDate to, BigDecimal pct, int version) {
        this(sheetId, grade, from, to, pct);
        this.version = version;
    }

    /** A rate with its forward-only mark and earlier versions, as a copy or a version rebuilt from
     *  history carries them. */
    public Rate(String sheetId, String grade, LocalDate from, LocalDate to, BigDecimal pct, int version,
            Long afterClaim, List<Earlier> earlier) {
        this(sheetId, grade, from, to, pct, version);
        this.afterClaim = afterClaim;
        this.earlier = new ArrayList<>(earlier);
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

    /** The first date the rate is in force; null when it applies from the beginning of time. */
    public LocalDate getFrom() {
        return from;
    }

    /** Whether the rate is in force on a date (it may be overtaken by a later one). */
    public boolean appliesOn(LocalDate date) {
        return from == null || !from.isAfter(date);
    }

    /** The last date the rate applies; null when it applies for good. */
    public LocalDate getTo() {
        return to;
    }

    public void setTo(LocalDate to) {
        this.to = to;
    }

    /** Whether the rate still applies on a date it is in force (not past its to-date). */
    public boolean runsThrough(LocalDate date) {
        return to == null || !to.isBefore(date);
    }

    public BigDecimal getPct() {
        return pct;
    }

    public void setPct(BigDecimal pct) {
        this.pct = pct;
    }

    /** How many versions the rate has: this, the newest, is the one in force. */
    public int getVersion() {
        return version;
    }

    /** For a forward-only newest version, the highest claim id there was when it was saved; null
     *  when it applies to every claim. */
    public Long getAfterClaim() {
        return afterClaim;
    }

    /** The versions a claim added before the newest one applies instead, newest first. */
    public List<Earlier> getEarlier() {
        return List.copyOf(earlier);
    }

    /** Replace the rate's percent and to-date with a new version, its version count going up by
     *  one: forward-only when {@code afterClaim} (the highest claim id there is now) is given, the
     *  version it replaces then kept for the claims added before it. */
    public void newVersion(BigDecimal pct, LocalDate to, Long afterClaim) {
        if (afterClaim == null) {
            earlier.clear();
        } else {
            if (this.afterClaim == null) {
                earlier.clear();
            }
            earlier.add(0, new Earlier(this.pct, this.to, this.afterClaim));
        }
        this.pct = pct;
        this.to = to;
        this.afterClaim = afterClaim;
        this.version++;
    }

    /** Withdraw the newest version, the version before it ({@code previous}: its percent and
     *  to-date) applying again, the version count going down by one: for a forward-only newest
     *  version the earlier version kept for claims added before it is the one restored. */
    public void withdrawTo(RateVersion previous) {
        if (afterClaim != null && !earlier.isEmpty()) {
            Earlier restored = earlier.remove(0);
            this.pct = restored.pct();
            this.to = restored.to();
            this.afterClaim = restored.afterClaim();
        } else {
            earlier.clear();
            this.pct = previous.getPct();
            this.to = previous.getTo();
            this.afterClaim = null;
        }
        this.version--;
    }

    /** The rate as it applies to a claim added as {@code added} (null: one added now): the newest
     *  version, else the newest earlier one that applies to it; none when no version does. */
    public Optional<Rate> forClaim(Long added) {
        if (afterClaim == null || added == null || added > afterClaim) {
            return Optional.of(this);
        }
        return earlier.stream()
                .filter(e -> e.appliesTo(added))
                .findFirst()
                .map(e -> new Rate(sheetId, grade, from, e.to(), e.pct(), version));
    }

    /** Of rates, each as it applies to a claim added as {@code added}, leaving out those that do not. */
    public static List<Rate> forClaim(List<Rate> rates, Long added) {
        if (rates.stream().allMatch(r -> r.afterClaim == null)) {
            return rates;
        }
        return rates.stream().flatMap(r -> r.forClaim(added).stream()).toList();
    }
}
