package net.officefloor.hq.app.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A value of one of a sheet's dated settings, recorded from a date; a null value records "none" from
 * then (no clerk limit, no cap, or a grade using the sheet's terms). */
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

    @Column(name = "from_date", nullable = false)
    private LocalDate from;

    @Column(name = "setting_value")
    private String value;

    protected SheetSetting() {
    }

    public SheetSetting(String sheetId, String key, LocalDate from, String value) {
        this.sheetId = sheetId;
        this.key = key;
        this.from = from;
        this.value = value;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getKey() {
        return key;
    }

    public LocalDate getFrom() {
        return from;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
