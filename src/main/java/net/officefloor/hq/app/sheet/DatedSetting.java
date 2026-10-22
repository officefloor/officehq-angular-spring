package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A setting's value recorded from a date: it is in force from then until the key's next from-date. */
@Embeddable
public class DatedSetting {

    @Column(name = "setting_key", nullable = false)
    private String key;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    /** The value as text; null for none. */
    @Column(name = "setting_value")
    private String value;

    protected DatedSetting() {
    }

    public DatedSetting(String key, LocalDate fromDate, String value) {
        this.key = key;
        this.fromDate = fromDate;
        this.value = value;
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
}
