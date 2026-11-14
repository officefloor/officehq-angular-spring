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
 *  clerk-limit, clerk-limit-&lt;grade&gt;, cap, proof-threshold, proof-threshold-&lt;grade&gt;, note-threshold, rounding): in force from its from-date
 *  until the next one recorded for the key. A figure, but a word (its text) for the rounding mode. Null only for the clerk
 *  limit, the cap, a proof threshold, the note threshold or the rounding mode, where it means none. */
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

    @Column(name = "setting_text", length = 20)
    private String text;

    protected SettingValue() {
    }

    public SettingValue(String sheetId, String key, LocalDate from, BigDecimal value) {
        this.sheetId = sheetId;
        this.key = key;
        this.from = from;
        this.value = value;
    }

    public SettingValue(String sheetId, String key, LocalDate from, BigDecimal value, String text) {
        this(sheetId, key, from, value);
        this.text = text;
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

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
