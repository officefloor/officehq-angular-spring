package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A dated setting's value on a sheet, in force from its from-date until the key's next from-date. */
@Entity
@Table(name = "dated_setting")
public class DatedSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sheet_id", nullable = false)
    private Sheet sheet;

    @Column(name = "setting_key", nullable = false)
    private String key;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    /** The value as kept (a plain decimal or whole number); null: none (no clerk limit, no cap). */
    @Column(name = "setting_value")
    private String value;

    protected DatedSetting() {
    }

    DatedSetting(Sheet sheet, String key, LocalDate fromDate, String value) {
        this.sheet = sheet;
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

    void setValue(String value) {
        this.value = value;
    }
}
