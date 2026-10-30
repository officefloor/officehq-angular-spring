package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * A value of one of a sheet's dated settings (keyed as in the settings form: floor, terms, terms-A,
 * late-pct, clerk-limit, cap, ...), in force from its from-date, or from the beginning of time when it has
 * none. The value is as written; null is a value saved blank.
 */
@Entity
@Table(name = "sheet_setting")
public class SheetSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "setting_key", nullable = false)
    private String key;

    /** The first date the value is in force on; null when it is in force from the beginning of time. */
    @Column(name = "from_date")
    private LocalDate fromDate;

    @Column(name = "setting_value")
    private String value;

    protected SheetSetting() {
    }

    public SheetSetting(String sheetId, String key, LocalDate fromDate, String value) {
        this.sheetId = sheetId;
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

    public String getKey() {
        return key;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
