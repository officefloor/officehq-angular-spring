package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/**
 * A line of a sheet's dated-setting log: a value recorded for a setting from a from-date (a null value
 * is none), on the sheet's today then (null: before this was kept).
 */
@Embeddable
public class DatedSettingLine {

    @Column(name = "setting_key", nullable = false, length = 40)
    private String key;

    @Column(name = "from_date", nullable = false)
    private LocalDate from;

    @Column(name = "setting_value", length = 40)
    private String value;

    @Column(name = "set_on")
    private LocalDate setOn;

    protected DatedSettingLine() {}

    public DatedSettingLine(String key, LocalDate from, String value, LocalDate setOn) {
        this.key = key;
        this.from = from;
        this.value = value;
        this.setOn = setOn;
    }

    public String getKey() { return key; }

    public LocalDate getFrom() { return from; }

    public String getValue() { return value; }

    /** Whether it was recorded on or before the date. */
    public boolean recordedBy(LocalDate date) { return setOn == null || !setOn.isAfter(date); }
}
