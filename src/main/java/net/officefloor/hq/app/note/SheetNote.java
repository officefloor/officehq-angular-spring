package net.officefloor.hq.app.note;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A note on a sheet: its text, who added it and the sheet's today when it was added. */
@Entity
@Table(name = "sheet_note")
public class SheetNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "text", nullable = false)
    private String text;

    @Column(name = "added_by", nullable = false)
    private String addedBy;

    @Column(name = "added_on", nullable = false)
    private LocalDate addedOn;

    protected SheetNote() {
    }

    public SheetNote(String sheetId, String text, String addedBy, LocalDate addedOn) {
        this.sheetId = sheetId;
        this.text = text;
        this.addedBy = addedBy;
        this.addedOn = addedOn;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getText() {
        return text;
    }

    public String getAddedBy() {
        return addedBy;
    }

    public LocalDate getAddedOn() {
        return addedOn;
    }
}
