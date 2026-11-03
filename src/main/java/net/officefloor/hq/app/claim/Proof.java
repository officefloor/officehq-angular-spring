package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;
import java.util.List;

/** A proof given to a claim: the day it was given and its kind, receipt or photo (null for one
 *  given before kinds were kept: a receipt). */
@Embeddable
public class Proof {

    /** The kinds a proof may be; the first is the one a proof is when none is chosen. */
    public static final List<String> KINDS = List.of("receipt", "photo");

    @Column(name = "added_on", nullable = false)
    private LocalDate addedOn;

    @Column(name = "kind")
    private String kind;

    protected Proof() {
    }

    public Proof(LocalDate addedOn, String kind) {
        this.addedOn = addedOn;
        this.kind = kind;
    }

    /** A proof the same as this one, for a copy of its claim. */
    Proof copy() {
        return new Proof(addedOn, kind);
    }

    public LocalDate getAddedOn() {
        return addedOn;
    }

    /** The proof's kind; receipt for one given before kinds were kept. */
    public String getKind() {
        return kind == null ? KINDS.get(0) : kind;
    }
}
