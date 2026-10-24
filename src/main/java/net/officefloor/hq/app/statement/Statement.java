package net.officefloor.hq.app.statement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;

/** A statement taken of a person: the sheet's today when it was taken, the person's due-total then
 * (two decimals; null when it was n/a) and how many of their claims counted toward it. A person's
 * statements are ordered by their position, their last being the one placed last. */
@Entity
@Table(name = "statement")
public class Statement {

    /** Statements as they stand among their person's statements, oldest first. */
    public static final Comparator<Statement> ORDER = Comparator.comparingLong(Statement::getPosition)
            .thenComparing(Statement::getId);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "statement_date", nullable = false)
    private LocalDate date;

    @Column(name = "due_total", precision = 14, scale = 2)
    private BigDecimal dueTotal;

    @Column(name = "claim_count", nullable = false)
    private int claimCount;

    /** Where it stands among its person's statements; its id unless it moved with a merge or transfer. */
    @Column(name = "list_position")
    private Long position;

    protected Statement() {
    }

    public Statement(String sheetId, Long personId, LocalDate date, BigDecimal dueTotal, int claimCount) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.date = date;
        this.dueTotal = dueTotal;
        this.claimCount = claimCount;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public Long getPersonId() {
        return personId;
    }

    public LocalDate getDate() {
        return date;
    }

    public BigDecimal getDueTotal() {
        return dueTotal;
    }

    public int getClaimCount() {
        return claimCount;
    }

    /** Where it stands among its person's statements: its id until placed elsewhere. */
    public long getPosition() {
        return position != null ? position : id;
    }

    /** Move the statement to the person {@code personId} on the sheet {@code sheetId}, placed at
     * {@code position} among their statements. */
    public void movedTo(String sheetId, Long personId, long position) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.position = position;
    }
}
