package net.officefloor.hq.app.people;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A person on the list: a name and whole-number "alpha", "beta" and "gamma" (each absent until
 * given), and the derived "thrust" (alpha × beta), "drift" (gamma − alpha) and "momentum"
 * (thrust + 2 × drift). */
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

    /** Thrust is alpha multiplied by beta; absent while either is absent. */
    public Integer getThrust() {
        return alpha == null || beta == null ? null : alpha * beta;
    }

    /** Drift is gamma minus alpha; absent while either is absent. */
    public Integer getDrift() {
        return gamma == null || alpha == null ? null : gamma - alpha;
    }

    /** Momentum is thrust plus two times drift; absent while either is absent. */
    public Integer getMomentum() {
        Integer thrust = getThrust();
        Integer drift = getDrift();
        return thrust == null || drift == null ? null : thrust + 2 * drift;
    }
}
