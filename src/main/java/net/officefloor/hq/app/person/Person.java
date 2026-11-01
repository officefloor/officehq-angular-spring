package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Locale;

/**
 * A person on a sheet, with a name and a grade (A, B or C). The name is kept without surrounding
 * spaces and is unique on the sheet without regard to case (by its {@link #keyOf key}).
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

    protected Person() {}

    public Person(String sheetId, String name, String grade) {
        this.sheetId = sheetId;
        setName(name);
        this.grade = grade;
    }

    /** The key a name is compared by for uniqueness: without surrounding spaces, lower-cased. */
    public static String keyOf(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    public Long getId() { return id; }

    public String getSheetId() { return sheetId; }

    public String getName() { return name; }

    public void setName(String name) {
        this.name = name.strip();
        this.nameKey = keyOf(name);
    }

    public String getGrade() { return grade; }

    public void setGrade(String grade) { this.grade = grade; }
}
