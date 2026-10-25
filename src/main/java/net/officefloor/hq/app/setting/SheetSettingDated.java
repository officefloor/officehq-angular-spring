package net.officefloor.hq.app.setting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;

/** A value one of the sheet's dated settings takes from a date; a blank value clears the setting from then. */
@Entity
@Table(name = "sheet_setting_dated")
@IdClass(SheetSettingDated.Key.class)
public class SheetSettingDated {

    public record Key(String sheetId, String key, LocalDate from) implements Serializable {
        public Key() {
            this(null, null, null);
        }
    }

    @Id
    @Column(name = "sheet_id")
    private String sheetId;

    @Id
    @Column(name = "setting_key")
    private String key;

    @Id
    @Column(name = "from_date")
    private LocalDate from;

    @Column(name = "setting_value", nullable = false)
    private String value;

    protected SheetSettingDated() {
    }

    public SheetSettingDated(String sheetId, String key, LocalDate from, String value) {
        this.sheetId = sheetId;
        this.key = key;
        this.from = from;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public LocalDate getFrom() {
        return from;
    }

    /** The value from the from-date; blank when the setting is cleared from then. */
    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
