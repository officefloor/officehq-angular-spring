package net.officefloor.hq.app.role;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * A steward's delegation of the steward role to a user: while the sheet's today is from its
 * from-date to its to-date, inclusive, the user acts as a steward on behalf of the steward.
 */
@Entity
@Table(name = "sheet_delegation")
public class Delegation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "user_name", nullable = false)
    private String userName;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    /** The steward who delegated. */
    @Column(name = "steward", nullable = false)
    private String steward;

    protected Delegation() {
    }

    public Delegation(String sheetId, String userName, LocalDate fromDate, LocalDate toDate, String steward) {
        this.sheetId = sheetId;
        this.userName = userName;
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.steward = steward;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getUserName() {
        return userName;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    public String getSteward() {
        return steward;
    }

    /** Whether the delegation is in force on the date. */
    public boolean covers(LocalDate date) {
        return !date.isBefore(fromDate) && !date.isAfter(toDate);
    }
}
