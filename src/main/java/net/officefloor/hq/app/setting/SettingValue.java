package net.officefloor.hq.app.setting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import net.officefloor.hq.app.history.HistoryListener;

/** A value recorded for one of a sheet's dated settings (floor, late-pct, terms, terms-&lt;grade&gt;,
 *  clerk-limit, cap, proof-threshold): in force from its from-date until the next one recorded for the
 *  key. Null only for the clerk limit, the cap or the proof threshold, where it means none. */
@Entity
@Table(name = "sheet_setting_value")
@EntityListeners(HistoryListener.class)
public class SettingValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "setting_key", nullable = false)
    private String key;

    @Column(name = "from_date", nullable = false)
    private LocalDate from;

    @Column(name = "setting_value", precision = 12, scale = 2)
    private BigDecimal value;

    protected SettingValue() {
    }

    public SettingValue(String sheetId, String key, LocalDate from, BigDecimal value) {
        this.sheetId = sheetId;
        this.key = key;
        this.from = from;
        this.value = value;
    }

    public Long getId() {
        return id;
    }

    public String getKey() {
        return key;
    }

    public LocalDate getFrom() {
        return from;
    }

    public BigDecimal getValue() {
        return value;
    }

    public void setValue(BigDecimal value) {
        this.value = value;
    }
}
