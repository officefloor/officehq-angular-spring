package net.officefloor.hq.app.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/** A version of a sheet's policy settings (see {@link DatedSettings#POLICY_KEYS}): made by a change to one
 * of them, numbered from 1 on the sheet in the order made, with the setting changed, its value and the
 * date it is in force from (null counts on every date). */
@Entity
@Table(name = "policy_version")
public class PolicyVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "number", nullable = false)
    private int number;

    @Column(name = "from_date")
    private LocalDate from;

    @Column(name = "setting_key", nullable = false)
    private String key;

    @Column(name = "setting_value")
    private String value;

    protected PolicyVersion() {
    }

    public PolicyVersion(String sheetId, int number, LocalDate from, String key, String value) {
        this.sheetId = sheetId;
        this.number = number;
        this.from = from;
        this.key = key;
        this.value = value;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public int getNumber() {
        return number;
    }

    public LocalDate getFrom() {
        return from;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    /** Whether the version counts on the date: it has no from-date, or one on or before it. */
    public boolean countsOn(LocalDate date) {
        return from == null || !from.isAfter(date);
    }

    /** How many of the claims dated {@code claimDates} have each of {@code versions} (oldest first) as
     * their policy version: the number of versions that count on the claim's date. A claim with none in
     * force on its date counts for none. */
    public static int[] claimsOf(List<PolicyVersion> versions, Collection<LocalDate> claimDates) {
        int[] counts = new int[versions.size()];
        for (LocalDate date : claimDates) {
            int number = (int) versions.stream().filter(v -> v.countsOn(date)).count();
            if (number > 0) counts[number - 1]++;
        }
        return counts;
    }
}
