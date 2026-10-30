package net.officefloor.hq.app.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** The steward role delegated by a steward to a user name, from a from-date to a to-date inclusive. */
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

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Column(name = "delegated_by", nullable = false)
    private String delegatedBy;

    protected Delegation() {
    }

    public Delegation(String sheetId, String userName, LocalDate fromDate, LocalDate toDate, String delegatedBy) {
        this.sheetId = sheetId;
        this.userName = userName;
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.delegatedBy = delegatedBy;
    }

    public Long getId() {
        return id;
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

    public String getDelegatedBy() {
        return delegatedBy;
    }

    /** Whether the date is inside the delegation's range. */
    public boolean covers(LocalDate date) {
        return !date.isBefore(fromDate) && !date.isAfter(toDate);
    }
}
