package net.officefloor.hq.app.rate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import net.officefloor.hq.app.person.Grade;

/** The sheet's rate for a grade from a date (none: from the beginning of time): a percent with two decimals. */
@Entity
@Table(name = "rate")
public class Rate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "grade", nullable = false)
    private Grade grade;

    @Column(name = "pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal pct;

    /** The first date the rate applies on; none when it applies from the beginning of time. */
    @Column(name = "from_date")
    private LocalDate fromDate;

    protected Rate() {}

    public Rate(String sheetId, Grade grade, LocalDate fromDate, BigDecimal pct) {
        this.sheetId = sheetId;
        this.grade = grade;
        this.fromDate = fromDate;
        this.pct = pct;
    }

    public Long getId() { return id; }

    public String getSheetId() { return sheetId; }

    public Grade getGrade() { return grade; }

    public LocalDate getFromDate() { return fromDate; }

    /** Whether the rate applies to a claim dated {@code date}. */
    public boolean appliesOn(LocalDate date) { return fromDate == null || !fromDate.isAfter(date); }

    public BigDecimal getPct() { return pct; }

    public void setPct(BigDecimal pct) { this.pct = pct; }
}
