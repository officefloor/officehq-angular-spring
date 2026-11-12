package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * A policy version of a sheet: a change to one of its policy settings (the rounding mode, the floor, the cap, the
 * terms, the late percent or the clerk limit), numbered from 1 in the order made, with the value the setting was given,
 * in force from its from-date (none: on every date).
 */
@Entity
@Table(name = "policy_version")
public class PolicyVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sheet_id", nullable = false)
    private Sheet sheet;

    @Column(name = "version_no", nullable = false)
    private int number;

    @Column(name = "setting_key", nullable = false)
    private String key;

    /** The date the version is in force from; null: on every date. */
    @Column(name = "from_date")
    private LocalDate fromDate;

    /** The value the setting was given; null: none (such as no limit). */
    @Column(name = "setting_value")
    private String value;

    protected PolicyVersion() {
    }

    PolicyVersion(Sheet sheet, int number, String key, LocalDate fromDate, String value) {
        this.sheet = sheet;
        this.number = number;
        this.key = key;
        this.fromDate = fromDate;
        this.value = value;
    }

    public int getNumber() {
        return number;
    }

    public String getKey() {
        return key;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public String getValue() {
        return value;
    }

    /** Whether the version is in force on the date: it has no from-date, or its from-date is on or before it. */
    public boolean inForceOn(LocalDate date) {
        return fromDate == null || !fromDate.isAfter(date);
    }
}
