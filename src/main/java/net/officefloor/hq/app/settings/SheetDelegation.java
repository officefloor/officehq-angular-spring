package net.officefloor.hq.app.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A steward's role delegated to a user name from a date to a date, inclusive, by the sheet's
 *  today: while the sheet's today is inside the range the user acts as a steward. */
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

    @Column(name = "steward", nullable = false)
    private String steward;

    @Column(name = "from_date", nullable = false)
    private LocalDate from;

    @Column(name = "to_date", nullable = false)
    private LocalDate to;

    protected SheetDelegation() {
    }

    public SheetDelegation(String sheetId, String user, String steward, LocalDate from, LocalDate to) {
        this.sheetId = sheetId;
        this.user = user;
        this.steward = steward;
        this.from = from;
        this.to = to;
    }

    public Long getId() {
        return id;
    }

    public String getUser() {
        return user;
    }

    public String getSteward() {
        return steward;
    }

    public LocalDate getFrom() {
        return from;
    }

    public LocalDate getTo() {
        return to;
    }

    /** Whether the date is inside the range, inclusive. */
    public boolean covers(LocalDate day) {
        return !day.isBefore(from) && !day.isAfter(to);
    }
}
