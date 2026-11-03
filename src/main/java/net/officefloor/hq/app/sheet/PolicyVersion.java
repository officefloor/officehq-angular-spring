package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** A version of a sheet's policy: made by a change to one of its policy settings (see {@link #KEYS}),
 *  numbered from 1 per sheet in the order made, in force from its from-date (null: none, always). */
@Entity
@Table(name = "policy_version")
public class PolicyVersion {

    /** The settings that are versioned together as the sheet's policy. */
    public static final Set<String> KEYS = Set.of(Rounding.KEY, "floor", "cap", "terms", "late-pct", "clerk-limit");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "number", nullable = false)
    private int number;

    @Column(name = "setting_key", nullable = false)
    private String key;

    @Column(name = "from_date")
    private LocalDate from;

    /** The sheet's today when the version was made. */
    @Column(name = "recorded_on", nullable = false)
    private LocalDate recordedOn;

    /** The value the setting was given, as shown; null when not kept. */
    @Column(name = "value_shown")
    private String shown;

    protected PolicyVersion() {
    }

    public PolicyVersion(String sheetId, int number, String key, LocalDate from, LocalDate recordedOn,
            String shown) {
        this.shown = shown;
        this.sheetId = sheetId;
        this.number = number;
        this.key = key;
        this.from = from;
        this.recordedOn = recordedOn;
    }

    public int getNumber() {
        return number;
    }

    public String getKey() {
        return key;
    }

    public LocalDate getFrom() {
        return from;
    }

    public String getShown() {
        return shown;
    }

    public LocalDate getRecordedOn() {
        return recordedOn;
    }

    /** Whether the version is in force on a day: it has no from-date, or its from-date is on or before it. */
    public boolean countsOn(LocalDate day) {
        return from == null || !from.isAfter(day);
    }

    /** The version of the policy in force on a day: the number of the versions given in force on it. */
    public static int on(List<PolicyVersion> versions, LocalDate day) {
        return (int) versions.stream().filter(v -> v.countsOn(day)).count();
    }
}
