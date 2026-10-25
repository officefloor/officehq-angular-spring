package net.officefloor.hq.app.setting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;

/** One of the sheet's settings: its value by key. */
@Entity
@Table(name = "sheet_setting")
@IdClass(SheetSetting.Key.class)
public class SheetSetting {

    public record Key(String sheetId, String key) implements Serializable {
        public Key() {
            this(null, null);
        }
    }

    @Id
    @Column(name = "sheet_id")
    private String sheetId;

    @Id
    @Column(name = "setting_key")
    private String key;

    @Column(name = "setting_value", nullable = false)
    private String value;

    protected SheetSetting() {
    }

    public SheetSetting(String sheetId, String key, String value) {
        this.sheetId = sheetId;
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
