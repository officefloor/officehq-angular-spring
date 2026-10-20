package net.officefloor.hq.app.setting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A steward's delegation of the steward role to a user name, from a date to a date, inclusive, by the sheet's
 * today. */
@Entity
@Table(name = "delegation")
public class Delegation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "delegator", nullable = false)
    private String delegator;

    @Column(name = "user_name", nullable = false)
    private String user;

    @Column(name = "from_date", nullable = false)
    private LocalDate from;

    @Column(name = "to_date", nullable = false)
    private LocalDate to;

    protected Delegation() {}

    public Delegation(String sheetId, String delegator, String user, LocalDate from, LocalDate to) {
        this.sheetId = sheetId;
        this.delegator = delegator;
        this.user = user;
        this.from = from;
        this.to = to;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getDelegator() {
        return delegator;
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

    /** Whether the delegation is in force on a date. */
    public boolean covers(LocalDate date) {
        return date != null && !date.isBefore(from) && !date.isAfter(to);
    }
}
