package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/**
 * One value recorded for a dated setting of a sheet, kept as it was recorded: the key, the from-date, the value
 * (null for none) and the sheet's today when it was recorded (null: before that was kept).
 */
@Embeddable
public class SettingChange {

    @Column(name = "setting_key", nullable = false)
    private String key;

    @Column(name = "from_date", nullable = false)
    private LocalDate from;

    @Column(name = "setting_value")
    private String value;

    @Column(name = "made_on")
    private LocalDate madeOn;

    protected SettingChange() {
    }

    public SettingChange(String key, LocalDate from, String value, LocalDate madeOn) {
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

    public LocalDate getMadeOn() {
        return madeOn;
    }

    /** Whether the value was recorded while the sheet's today was on or before the day. */
    public boolean madeBy(LocalDate day) {
        return madeOn == null || !madeOn.isAfter(day);
    }
}
