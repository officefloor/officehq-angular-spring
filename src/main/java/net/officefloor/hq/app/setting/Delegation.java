package net.officefloor.hq.app.setting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A steward's delegation of the steward role to a user from a from-date to a to-date, inclusive, by
 *  the sheet's today. */
@Entity
@Table(name = "delegation")
public class Delegation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "user_name", nullable = false)
    private String user;

    @Column(name = "from_date", nullable = false)
    private LocalDate from;

    @Column(name = "to_date", nullable = false)
    private LocalDate to;

    @Column(name = "delegated_by", nullable = false)
    private String by;

    /** The sheet's today when the delegation was made; null for one made before it was kept. */
    @Column(name = "made_on", updatable = false)
    private LocalDate madeOn;

    protected Delegation() {
    }

    public Delegation(String sheetId, String user, LocalDate from, LocalDate to, String by, LocalDate madeOn) {
        this.madeOn = madeOn;
        this.sheetId = sheetId;
        this.user = user;
        this.from = from;
        this.to = to;
        this.by = by;
    }

    public Long getId() {
        return id;
    }

    public String getUser() {
        return user;
    }

    public LocalDate getFrom() {
        return from;
    }

    public LocalDate getTo() {
        return to;
    }

    /** The steward who delegated. */
    public String getBy() {
        return by;
    }

    /** Whether the delegation stood at the end of a day: made while the sheet's today was on or before
     *  it (one made before that was kept always stood). */
    public boolean madeAsOf(LocalDate day) {
        return madeOn == null || !madeOn.isAfter(day);
    }

    /** Whether the delegation is in force on a date. */
    public boolean covers(LocalDate on) {
        return !on.isBefore(from) && !on.isAfter(to);
    }
}
