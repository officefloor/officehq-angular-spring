package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.Locale;

/** A person on a sheet. */
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

    /** The name as uniqueness compares it: trimmed and lower-cased. */
    @Column(name = "name_key", nullable = false)
    private String nameKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "grade", nullable = false)
    private Grade grade;

    /** The date the person is retired from; null while they are not retired. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** Bumped on each save; an edit made from an older version is refused. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {
    }

    public Person(String sheetId, String name, Grade grade) {
        this.sheetId = sheetId;
        setName(name);
        this.grade = grade;
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

    /** Stores the name with its surrounding spaces removed. */
    public void setName(String name) {
        this.name = name.trim();
        this.nameKey = keyOf(name);
    }

    /** The key a name is compared on for uniqueness: without surrounding spaces or regard to case. */
    public static String keyOf(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    public Grade getGrade() {
        return grade;
    }

    public void setGrade(Grade grade) {
        this.grade = grade;
    }

    public LocalDate getRetiredFrom() {
        return retiredFrom;
    }

    public void setRetiredFrom(LocalDate retiredFrom) {
        this.retiredFrom = retiredFrom;
    }

    /** Whether the person is retired: true as soon as a retirement date is set, whatever the date. */
    public boolean isRetired() {
        return retiredFrom != null;
    }

    /** Whether a claim of this date is refused: one dated after the person's retirement date. */
    public boolean isRetiredOn(LocalDate date) {
        return retiredFrom != null && date.isAfter(retiredFrom);
    }

    public long getVersion() {
        return version;
    }
}
