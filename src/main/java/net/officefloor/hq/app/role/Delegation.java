package net.officefloor.hq.app.role;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A steward's delegation of the steward role to a user name: while the sheet's today is from its from-date to its
 *  to-date, inclusive, the user acts as a steward on behalf of the steward who delegated. */
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

    @Column(name = "delegated_by", nullable = false)
    private String delegatedBy;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    protected Delegation() {
    }

    public Delegation(String sheetId, String userName, String delegatedBy, LocalDate fromDate, LocalDate toDate) {
        this.sheetId = sheetId;
        this.userName = userName;
        this.delegatedBy = delegatedBy;
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

    public String getDelegatedBy() {
        return delegatedBy;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    /** Whether the delegation is in force on the day. */
    public boolean covers(LocalDate day) {
        return !day.isBefore(fromDate) && !day.isAfter(toDate);
    }
}
