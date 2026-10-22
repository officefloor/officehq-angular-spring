package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;

/**
 * A person on a sheet, with a name and a grade (A, B or C). The name is kept with surrounding spaces
 * removed and is unique on the sheet ignoring case, by its {@link #keyOf key}. Its version is raised on
 * every save, so an edit made from an older version is refused.
 */
@Entity
@Table(name = "person")
public class Person {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "name_key", nullable = false)
    private String nameKey;

    @Column(name = "grade", nullable = false)
    private String grade;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** How many statements have been taken for the person. */
    @Column(name = "statement_count", nullable = false)
    private int statementCount;

    /** The last statement's due-total; null with no statement, or when it was n/a. */
    @Column(name = "statement_total", precision = 14, scale = 2)
    private BigDecimal statementTotal;

    /** The sheet's today when the last statement was taken; null with no statement. */
    @Column(name = "statement_date")
    private LocalDate statementDate;

    protected Person() {
    }

    public Person(String sheetId, String name, String grade) {
        this.sheetId = sheetId;
        setName(name);
        this.grade = grade;
    }

    /** The name as it is kept: surrounding spaces removed. */
    public static String clean(String name) {
        return name.trim();
    }

    /** The key a name is unique by on its sheet: kept as cleaned, ignoring case. */
    public static String keyOf(String name) {
        return clean(name).toLowerCase(Locale.ROOT);
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = clean(name);
        this.nameKey = keyOf(name);
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }

    public long getVersion() {
        return version;
    }

    public int getStatementCount() {
        return statementCount;
    }

    public BigDecimal getStatementTotal() {
        return statementTotal;
    }

    public LocalDate getStatementDate() {
        return statementDate;
    }

    /** Record a statement taken on a date with its due-total (null when n/a). */
    public void stated(BigDecimal total, LocalDate date) {
        this.statementCount++;
        this.statementTotal = total;
        this.statementDate = date;
    }
}
