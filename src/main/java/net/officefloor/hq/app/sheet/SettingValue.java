package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** The value a dated setting of a sheet takes from a date on (the floor, the late percent, the terms
 *  of the sheet and of each grade, the clerk limit, the cap); in force until a later from-date of the
 *  same key. A null value is blank: no limit, no cap. */
@Entity
@Table(name = "setting_value")
@IdClass(SettingValue.Key.class)
public class SettingValue {

    /** The key of a setting value: its sheet, setting and from-date. */
    public static class Key implements Serializable {
        private String sheetId;
        private String key;
        private LocalDate from;

        protected Key() {
        }

        public Key(String sheetId, String key, LocalDate from) {
            this.sheetId = sheetId;
            this.key = key;
            this.from = from;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(sheetId, k.sheetId) && Objects.equals(key, k.key)
                    && Objects.equals(from, k.from);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sheetId, key, from);
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

    @Column(name = "setting", precision = 14, scale = 2)
    private BigDecimal value;

    protected SettingValue() {
    }

    public SettingValue(String sheetId, String key, LocalDate from, BigDecimal value) {
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

    public BigDecimal getValue() {
        return value;
    }

    public void setValue(BigDecimal value) {
        this.value = value;
    }
}
