package net.officefloor.hq.app.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** The steward role delegated by a steward to a user on a sheet, from a from-date to a to-date, inclusive. */
@Entity
@Table(name = "sheet_delegation")
public class SheetDelegation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "user_name", nullable = false)
    private String user;

    @Column(name = "delegator", nullable = false)
    private String delegator;

    @Column(name = "from_date", nullable = false)
    private LocalDate from;

    @Column(name = "to_date", nullable = false)
    private LocalDate to;

    protected SheetDelegation() {
    }

    public SheetDelegation(String sheetId, String user, String delegator, LocalDate from, LocalDate to) {
        this.sheetId = sheetId;
        this.user = user;
        this.delegator = delegator;
        this.from = from;
        this.to = to;
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

    public String getDelegator() {
        return delegator;
    }

    public LocalDate getFrom() {
        return from;
    }

    public LocalDate getTo() {
        return to;
    }

    /** Whether {@code date} is inside the range, inclusive. */
    public boolean covers(LocalDate date) {
        return date != null && !date.isBefore(from) && !date.isAfter(to);
    }
}
