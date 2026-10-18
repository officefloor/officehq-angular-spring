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

    @Enumerated(EnumType.STRING)
    @Column(name = "grade", nullable = false)
    private Grade grade;

    /** Retired from this date (claims dated after it are refused); null when not retired. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {}

    public Person(String sheetId, String name, Grade grade) {
        this.sheetId = sheetId;
        this.name = name;
        this.grade = grade;
    }

    public Long getId() { return id; }

    public long getVersion() { return version; }

    public String getSheetId() { return sheetId; }

    public String getName() { return name; }

    public void setName(String name) { this.name = name; }

    public Grade getGrade() { return grade; }

    public void setGrade(Grade grade) { this.grade = grade; }

    public LocalDate getRetiredFrom() { return retiredFrom; }

    public void setRetiredFrom(LocalDate retiredFrom) { this.retiredFrom = retiredFrom; }

    /** Whether a claim dated {@code date} is after their retirement. */
    public boolean retiredOn(LocalDate date) { return retiredFrom != null && date.isAfter(retiredFrom); }
}
