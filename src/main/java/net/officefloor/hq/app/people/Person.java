package net.officefloor.hq.app.people;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A person on the list: a name and whole-number "alpha" and "beta" (each absent until
 * given), and the derived "thrust" (alpha × beta). */
@Entity
@Table(name = "person")
public class Person {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private Integer alpha;

    private Integer beta;

    protected Person() {
    }

    public Person(String name, Integer alpha, Integer beta) {
        this.name = name;
        this.alpha = alpha;
        this.beta = beta;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Integer getAlpha() {
        return alpha;
    }

    public Integer getBeta() {
        return beta;
    }

    public void setBeta(Integer beta) {
        this.beta = beta;
    }

    /** Thrust is alpha multiplied by beta; absent while either is absent. */
    public Integer getThrust() {
        return alpha == null || beta == null ? null : alpha * beta;
    }
}
