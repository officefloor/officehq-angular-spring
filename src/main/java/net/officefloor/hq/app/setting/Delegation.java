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

    protected Delegation() {
    }

    public Delegation(String sheetId, String user, LocalDate from, LocalDate to, String by) {
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

    /** Whether the delegation is in force on a date. */
    public boolean covers(LocalDate on) {
        return !on.isBefore(from) && !on.isAfter(to);
    }
}
