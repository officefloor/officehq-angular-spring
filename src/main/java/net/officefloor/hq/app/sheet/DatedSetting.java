package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/**
 * A value recorded for one of a sheet's dated settings (the floor, the late percent, the terms, a
 * grade's terms, the clerk limit, the cap) from a date on; a null value is none.
 */
@Embeddable
public class DatedSetting {

    @Column(name = "setting_key", nullable = false, length = 40)
    private String key;

    @Column(name = "from_date", nullable = false)
    private LocalDate from;

    @Column(name = "setting_value", length = 40)
    private String value;

    protected DatedSetting() {}

    public DatedSetting(String key, LocalDate from, String value) {
        this.key = key;
        this.from = from;
        this.value = value;
    }

    public String getKey() { return key; }

    public LocalDate getFrom() { return from; }

    public String getValue() { return value; }
}
