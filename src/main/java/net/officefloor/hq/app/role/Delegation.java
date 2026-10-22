package net.officefloor.hq.app.role;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * The steward role delegated by a steward to a user name for an inclusive range of the sheet's today:
 * while the sheet's today is inside it, the user acts as a steward, for the steward who delegated.
 */
@Entity
@Table(name = "steward_delegation")
public class Delegation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "user_name", nullable = false)
    private String userName;

    @Column(name = "steward", nullable = false)
    private String steward;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    protected Delegation() {
    }

    public Delegation(String sheetId, String userName, String steward, LocalDate fromDate, LocalDate toDate) {
        this.sheetId = sheetId;
        this.userName = userName;
        this.steward = steward;
        this.fromDate = fromDate;
        this.toDate = toDate;
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

    public String getSteward() {
        return steward;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    /** Whether the delegation is in force on a date: it is inside the range, both ends included. */
    public boolean covers(LocalDate date) {
        return !date.isBefore(fromDate) && !date.isAfter(toDate);
    }
}
