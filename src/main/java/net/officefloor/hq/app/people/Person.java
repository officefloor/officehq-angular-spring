package net.officefloor.hq.app.people;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A person on the list: a name and whole-number "alpha", "beta" and "gamma" (each absent until
 * given), and the derived "thrust" (alpha × beta), "drift" (gamma − alpha) and "momentum"
 * (thrust + 3 × drift), which are absent until all three raw values are given. */
@Entity
@Table(name = "person")
public class Person {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private Integer alpha;

    private Integer beta;

    private Integer gamma;

    protected Person() {
    }

    public Person(String name, Integer alpha, Integer beta, Integer gamma) {
        this.name = name;
        this.alpha = alpha;
        this.beta = beta;
        this.gamma = gamma;
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

    public void setAlpha(Integer alpha) {
        this.alpha = alpha;
    }

    public Integer getBeta() {
        return beta;
    }

    public void setBeta(Integer beta) {
        this.beta = beta;
    }

    public Integer getGamma() {
        return gamma;
    }

    public void setGamma(Integer gamma) {
        this.gamma = gamma;
    }

    /** The derived values exist only once alpha, beta and gamma are all given. */
    private boolean complete() {
        return alpha != null && beta != null && gamma != null;
    }

    /** Thrust is alpha multiplied by beta; absent until alpha, beta and gamma are all given. */
    public Integer getThrust() {
        return complete() ? alpha * beta : null;
    }

    /** Drift is gamma minus alpha; absent until alpha, beta and gamma are all given. */
    public Integer getDrift() {
        return complete() ? gamma - alpha : null;
    }

    /** Momentum is thrust plus three times drift; absent while either is absent. */
    public Integer getMomentum() {
        Integer thrust = getThrust();
        Integer drift = getDrift();
        return thrust == null || drift == null ? null : thrust + 3 * drift;
    }

    /** Tier classifies momentum: SINK below zero, STEADY up to 99, SURGE from 100; absent with momentum. */
    public String getTier() {
        Integer momentum = getMomentum();
        if (momentum == null) {
            return null;
        }
        return momentum < 0 ? "SINK" : momentum <= 99 ? "STEADY" : "SURGE";
    }
}
