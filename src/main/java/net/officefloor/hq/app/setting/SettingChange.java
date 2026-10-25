package net.officefloor.hq.app.setting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A recording of one of the sheet's dated settings, kept with the sheet's today it was made on, so the
 *  dated settings can be told as they stood at the end of any date. */
@Entity
@Table(name = "setting_change")
public class SettingChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "setting_key", nullable = false)
    private String key;

    @Column(name = "from_date", nullable = false)
    private LocalDate from;

    @Column(name = "setting_value", nullable = false)
    private String value;

    /** The sheet's today when the value was recorded; null when it was recorded before this was kept. */
    @Column(name = "made_on")
    private LocalDate madeOn;

    protected SettingChange() {
    }

    public SettingChange(String sheetId, String key, LocalDate from, String value, LocalDate madeOn) {
        this.sheetId = sheetId;
        this.key = key;
        this.from = from;
        this.value = value;
        this.madeOn = madeOn;
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

    /** Whether the change was made while the sheet's today was on or before the date. */
    public boolean madeBy(LocalDate date) {
        return madeOn == null || !madeOn.isAfter(date);
    }
}
