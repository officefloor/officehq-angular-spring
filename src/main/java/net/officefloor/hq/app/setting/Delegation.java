package net.officefloor.hq.app.setting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** The steward role a steward (the delegator) delegated to a user name on a sheet, from and to a date, inclusive. */
@Entity
@Table(name = "delegation")
public class Delegation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "user_name", nullable = false)
    private String userName;

    @Column(name = "delegator", nullable = false)
    private String delegator;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    protected Delegation() {}

    public Delegation(String sheetId, String userName, String delegator, LocalDate fromDate, LocalDate toDate) {
        this.sheetId = sheetId;
        this.userName = userName;
        this.delegator = delegator;
        this.fromDate = fromDate;
        this.toDate = toDate;
    }

    public Long getId() { return id; }

    public String getSheetId() { return sheetId; }

    public String getUserName() { return userName; }

    public String getDelegator() { return delegator; }

    public LocalDate getFromDate() { return fromDate; }

    public LocalDate getToDate() { return toDate; }

    /** Whether the date is inside its range. */
    public boolean covers(LocalDate date) {
        return !date.isBefore(fromDate) && !date.isAfter(toDate);
    }
}
