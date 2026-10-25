package net.officefloor.hq.app.setting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A steward's delegation of the steward role to a user name: while the sheet's today is from the
 *  from-date to the to-date, inclusive, the user acts as a steward for the one who delegated. */
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
    private LocalDate from;

    @Column(name = "to_date", nullable = false)
    private LocalDate to;

    @Column(name = "delegated_by", nullable = false)
    private String delegatedBy;

    protected Delegation() {
    }

    public Delegation(String sheetId, String userName, LocalDate from, LocalDate to, String delegatedBy) {
        this.sheetId = sheetId;
        this.userName = userName;
        this.from = from;
        this.to = to;
        this.delegatedBy = delegatedBy;
    }

    public Long getId() {
        return id;
    }

    public String getUserName() {
        return userName;
    }

    public LocalDate getFrom() {
        return from;
    }

    public LocalDate getTo() {
        return to;
    }

    public String getDelegatedBy() {
        return delegatedBy;
    }

    /** Whether the date is inside the delegation's range, inclusive. */
    public boolean covers(LocalDate date) {
        return date != null && !date.isBefore(from) && !date.isAfter(to);
    }
}
