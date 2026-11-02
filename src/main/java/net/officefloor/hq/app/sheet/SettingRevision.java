package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A revision of a dated setting's value in its sheet's history: the value a setting takes from a
 *  from-date after a change, recorded on the sheet's today then. */
@Entity
@Table(name = "setting_revision")
public class SettingRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "setting_key", nullable = false)
    private String key;

    @Column(name = "from_date", nullable = false)
    private LocalDate from;

    /** The sheet's today when the revision was recorded. */
    @Column(name = "recorded_on", nullable = false)
    private LocalDate recordedOn;

    @Column(name = "setting", precision = 14, scale = 2)
    private BigDecimal value;

    protected SettingRevision() {
    }

    /** A revision of the setting value as it is now, recorded on the day. */
    public SettingRevision(SettingValue value, LocalDate recordedOn) {
        this.sheetId = value.getSheetId();
        this.key = value.getKey();
        this.from = value.getFrom();
        this.recordedOn = recordedOn;
        this.value = value.getValue();
    }

    /** Which setting value the revision is of: its setting and from-date. */
    public String key() {
        return key + ":" + from;
    }

    /** Which setting value a value is (see {@link #key()}). */
    public static String key(SettingValue value) {
        return value.getKey() + ":" + value.getFrom();
    }

    /** Whether the setting value is now as this revision has it. */
    public boolean matches(SettingValue now) {
        return value == null ? now.getValue() == null : now.getValue() != null && value.compareTo(now.getValue()) == 0;
    }

    /** The setting value as this revision has it, detached: for working out figures only, never saved. */
    public SettingValue toValue() {
        return new SettingValue(sheetId, key, from, value);
    }

    public LocalDate getRecordedOn() {
        return recordedOn;
    }
}
