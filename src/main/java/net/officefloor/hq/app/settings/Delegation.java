package net.officefloor.hq.app.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * A steward's delegation of the steward role to a user name on a sheet: while the sheet's today is from its from-date
 * to its to-date, inclusive, the user acts as a steward for the steward who delegated.
 */
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

    @Column(name = "delegated_by", nullable = false)
    private String delegatedBy;

    @Column(name = "from_date", nullable = false)
    private LocalDate from;

    @Column(name = "to_date", nullable = false)
    private LocalDate to;

    protected Delegation() {
    }

    public Delegation(String sheetId, String user, String delegatedBy, LocalDate from, LocalDate to) {
        this.sheetId = sheetId;
        this.user = user;
        this.delegatedBy = delegatedBy;
        this.from = from;
        this.to = to;
    }

    /** Whether the date is inside the delegation's range, inclusive. */
    public boolean covers(LocalDate date) {
        return date != null && !date.isBefore(from) && !date.isAfter(to);
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getUser() {
        return user;
    }

    public String getDelegatedBy() {
        return delegatedBy;
    }

    public LocalDate getFrom() {
        return from;
    }

    public LocalDate getTo() {
        return to;
    }
}
