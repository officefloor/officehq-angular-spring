package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.Locale;

/**
 * A person on a sheet: a name and a grade (A, B or C). The name is kept without the spaces around
 * it and is unique on the sheet without regard to case, compared through its {@link #keyOf key}.
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

    /** The date the person is retired from; null while they are not retired. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** Raised each time the person is saved. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {
    }

    public Person(String sheetId, String name, String grade) {
        this.sheetId = sheetId;
        setName(name);
        this.grade = grade;
    }

    /** The form of a name that is unique on a sheet: without surrounding spaces, in lower case. */
    public static String keyOf(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
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
        this.name = name.trim();
        this.nameKey = keyOf(name);
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }

    public LocalDate getRetiredFrom() {
        return retiredFrom;
    }

    public void setRetiredFrom(LocalDate retiredFrom) {
        this.retiredFrom = retiredFrom;
    }

    /** Retired as soon as a retirement date is set, whatever the date. */
    public boolean isRetired() {
        return retiredFrom != null;
    }

    /** Refuses a claim dated {@code date} when it falls after the person's retirement date. */
    public void requireClaimable(LocalDate date) {
        if (retiredFrom != null && date.isAfter(retiredFrom)) {
            throw new PersonRetiredException();
        }
    }

    public long getVersion() {
        return version;
    }
}
