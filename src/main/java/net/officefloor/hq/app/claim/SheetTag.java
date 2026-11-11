package net.officefloor.hq.app.claim;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A tag used on a sheet: kept once it has been given to a claim, even when no claim carries it any more. */
@Entity
@Table(name = "sheet_tag")
public class SheetTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "tag", nullable = false)
    private String tag;

    protected SheetTag() {
    }

    public SheetTag(String sheetId, String tag) {
        this.sheetId = sheetId;
        this.tag = tag;
    }

    /** The tags of comma separated text, each trimmed and lower-cased, without repeats, and sorted. */
    public static List<String> parse(String text) {
        if (text == null) {
            return List.of();
        }
        return Arrays.stream(text.split(",")).map(t -> t.trim().toLowerCase(Locale.ROOT))
                .filter(t -> !t.isEmpty()).distinct().sorted().toList();
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getTag() {
        return tag;
    }
}
