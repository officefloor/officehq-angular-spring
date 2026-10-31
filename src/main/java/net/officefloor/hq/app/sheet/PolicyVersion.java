package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * A version of a sheet's policy: one change to one of its policy settings (see {@link DatedSettings#POLICY}),
 * numbered from 1 in the order made, with the key, from-date (null: the beginning of time) and value (null: saved
 * blank) it saved.
 */
@Entity
@Table(name = "policy_version")
public class PolicyVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "setting_key", nullable = false)
    private String key;

    @Column(name = "from_date")
    private LocalDate fromDate;

    @Column(name = "setting_value")
    private String value;

    protected PolicyVersion() {
    }

    public PolicyVersion(String sheetId, int version, String key, LocalDate fromDate, String value) {
        this.sheetId = sheetId;
        this.version = version;
        this.key = key;
        this.fromDate = fromDate;
        this.value = value;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public int getVersion() {
        return version;
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
}
