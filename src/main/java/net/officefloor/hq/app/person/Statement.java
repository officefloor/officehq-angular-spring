package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A statement taken for a person: their due-total on the sheet's today then. */
@Entity
@Table(name = "statement")
public class Statement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "taken_on", nullable = false)
    private LocalDate takenOn;

    /** The person's due-total then; none when it was n/a. */
    @Column(name = "due_total", precision = 15, scale = 2)
    private BigDecimal dueTotal;

    /** How many of the person's claims counted then. */
    @Column(name = "claims", nullable = false)
    private int claims;

    @Column(name = "taken_by", nullable = false)
    private String takenBy;

    protected Statement() {}

    public Statement(String sheetId, Long personId, LocalDate takenOn, BigDecimal dueTotal, int claims,
            String takenBy) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.takenOn = takenOn;
        this.dueTotal = dueTotal;
        this.claims = claims;
        this.takenBy = takenBy;
    }

    public Long getId() { return id; }

    public Long getPersonId() { return personId; }

    public LocalDate getTakenOn() { return takenOn; }

    public BigDecimal getDueTotal() { return dueTotal; }

    public int getClaims() { return claims; }
}
