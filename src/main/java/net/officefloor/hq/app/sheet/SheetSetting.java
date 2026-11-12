package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A value a setting of the sheet takes from a date on, until the next from-date of the same setting; none
 *  (null) for no limit, no cap, or a grade using the sheet's terms. */
@Embeddable
public class SheetSetting {

    @Column(name = "setting_key", nullable = false)
    private String key;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "setting_value")
    private String value;

    protected SheetSetting() {
    }

    SheetSetting(String key, LocalDate fromDate, String value) {
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
