package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A value a setting of the sheet takes from a from-date (null: from the beginning of time) until the next one's;
 *  a null value means none (no clerk limit, no cap, or a grade on the sheet's terms). */
@Embeddable
public class DatedSetting {

    @Column(name = "setting_key", nullable = false)
    private String key;

    @Column(name = "from_date")
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

    /** Whether it is in force by this day: it has no from-date, or its from-date is on or before it. */
    boolean startedBy(LocalDate date) {
        return from == null || !from.isAfter(date);
    }
}
