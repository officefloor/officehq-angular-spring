package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A comment on a claim: who wrote it, the sheet's today then, and its text. */
@Embeddable
public class ClaimComment {

    @Column(name = "by_user", nullable = false)
    private String by;

    @Column(name = "on_date", nullable = false)
    private LocalDate on;

    @Column(name = "text", nullable = false)
    private String text;

    protected ClaimComment() {
    }

    public ClaimComment(String by, LocalDate on, String text) {
        this.by = by;
        this.on = on;
        this.text = text;
    }

    public String getBy() {
        return by;
    }

    public LocalDate getOn() {
        return on;
    }

    public String getText() {
        return text;
    }
}
