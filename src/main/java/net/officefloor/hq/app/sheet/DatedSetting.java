package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;
import java.util.Objects;

/** A value of one of the sheet's dated settings, in force from its from-date until the next
 *  from-date of the same key; a blank value (none) is null. */
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

    @Override
    public boolean equals(Object o) {
        return o instanceof DatedSetting other && key.equals(other.key) && from.equals(other.from)
                && Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, from, value);
    }
}
