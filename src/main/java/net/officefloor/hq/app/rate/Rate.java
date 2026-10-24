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
import java.util.List;
import java.util.Optional;

/** A sheet's rate for a grade: a percent (0.00 or more, two decimals), in force from its from-date on
 * (from the beginning of time when it has none) through its to-date (with no end when it has none).
 * It holds its newest version, the one in force; its older versions are {@link RateVersion}s. */
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

    /** The newest version's number: 1 for a rate never replaced; also how many versions it has. */
    @Column(name = "version", nullable = false)
    private int version = 1;

    /** The terms it was saved with, newest first (see {@link Term}), as {@code pct|to|mark} separated by
     * {@code ;}; null when its newest version applies to every claim. */
    @Column(name = "terms", length = 4000)
    private String terms;

    protected Rate() {
    }

    public Rate(String sheetId, String grade, LocalDate from, LocalDate to, BigDecimal pct) {
        this.sheetId = sheetId;
        this.grade = grade;
        this.from = from;
        this.to = to;
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

    /** The date the rate applies from; null when it applies from the beginning of time. */
    public LocalDate getFrom() {
        return from;
    }

    /** The last date the rate applies on; null when it applies on with no end. */
    public LocalDate getTo() {
        return to;
    }

    public void setTo(LocalDate to) {
        this.to = to;
    }

    public BigDecimal getPct() {
        return pct;
    }

    public void setPct(BigDecimal pct) {
        this.pct = pct;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    /** A version of the rate as it applies to claims: its percent and to-date, for the claims added after
     * {@code mark} (a claim id; null: every claim). A version saved forward-only (see
     * {@link RateService#set}) has the highest claim id there was when it was saved as its mark. */
    public record Term(BigDecimal pct, LocalDate to, Long mark) {

        boolean appliesTo(long added) {
            return mark == null || added > mark;
        }
    }

    /** The terms the rate was saved with, newest first: the newest version alone, for every claim, when
     * it was not saved forward-only. */
    public List<Term> getTerms() {
        if (terms == null) return List.of(new Term(pct, to, null));
        List<Term> list = new ArrayList<>();
        for (String term : terms.split(";")) {
            String[] f = term.split("\\|", -1);
            list.add(new Term(new BigDecimal(f[0]), f[1].isEmpty() ? null : LocalDate.parse(f[1]),
                    f[2].isEmpty() ? null : Long.valueOf(f[2])));
        }
        return list;
    }

    /** Sets the terms, newest first; the newest one is the rate's percent and to-date. */
    public void setTerms(List<Term> list) {
        this.pct = list.get(0).pct();
        this.to = list.get(0).to();
        if (list.size() == 1 && list.get(0).mark() == null) {
            this.terms = null;
            return;
        }
        List<String> parts = new ArrayList<>();
        for (Term t : list) {
            parts.add(t.pct().toPlainString() + "|" + (t.to() == null ? "" : t.to()) + "|"
                    + (t.mark() == null ? "" : t.mark()));
            if (t.mark() == null) break;
        }
        this.terms = String.join(";", parts);
    }

    /** The rate as it applies to a claim added at {@code added} (see {@link
     * net.officefloor.hq.app.claim.Claim#addedMark}): its newest term that applies to it; empty when none
     * does (it was saved forward-only after the claim was added). */
    public Optional<Rate> forClaim(long added) {
        if (terms == null) return Optional.of(this);
        for (Term t : getTerms()) {
            if (!t.appliesTo(added)) continue;
            if (t.pct().compareTo(pct) == 0 && java.util.Objects.equals(t.to(), to)) return Optional.of(this);
            return Optional.of(new Rate(sheetId, grade, from, t.to(), t.pct()));
        }
        return Optional.empty();
    }
}
