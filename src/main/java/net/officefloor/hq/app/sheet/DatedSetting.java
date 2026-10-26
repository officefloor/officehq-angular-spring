package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/**
 * One value recorded for a dated setting of a sheet: in force from its from-date until the next one recorded for
 * the same key. The value is the figure as text, or null for none (a clerk limit or a cap left blank).
 */
@Embeddable
public class DatedSetting {

    @Column(name = "setting_key", nullable = false)
    private String key;

    @Column(name = "from_date", nullable = false)
    private LocalDate from;

    @Column(name = "setting_value")
    private String value;

    protected DatedSetting() {
    }

    public DatedSetting(String key, LocalDate from, String value) {
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

    public String getValue() {
        return value;
    }
}
