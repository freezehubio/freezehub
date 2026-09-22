package com.freezhub.organization;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "organization")
public class Organization {

    /** 24 hours: a day's notice is how far ahead teams actually plan around a freeze. */
    public static final int DEFAULT_STARTING_SOON_LEAD_TIME_MINUTES = 1440;

    /** A year: long enough for an audit to look back over one, which is what asks. */
    public static final int DEFAULT_DEPLOYMENT_CHECK_RETENTION_DAYS = 365;

    public static final int MIN_DEPLOYMENT_CHECK_RETENTION_DAYS = 7;
    public static final int MAX_DEPLOYMENT_CHECK_RETENTION_DAYS = 3650;

    /** Matches the database CHECK, so the two cannot drift. */
    public static final int MIN_STARTING_SOON_LEAD_TIME_MINUTES = 1;
    public static final int MAX_STARTING_SOON_LEAD_TIME_MINUTES = 30 * 24 * 60;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /**
     * How far ahead of a freeze this organization wants warning, in minutes (FZ-047).
     *
     * <p>Per-organization because release rhythms differ: a weekly train wants more
     * notice than a shop deploying continuously. Defaulted rather than asked for at
     * sign-up, so nobody has to answer a question they have no opinion about yet.
     */
    @Column(name = "starting_soon_lead_time_minutes", nullable = false)
    private int startingSoonLeadTimeMinutes = DEFAULT_STARTING_SOON_LEAD_TIME_MINUTES;

    /**
     * How long this organization's deployment checks are kept, in days (FZ-070).
     *
     * <p>One year by default — the window most compliance regimes assume. It is also what
     * bounds how long the deploying engineer's identity is held, so it is a data-handling
     * setting as much as a storage one.
     */
    @Column(name = "deployment_check_retention_days", nullable = false)
    private int deploymentCheckRetentionDays = DEFAULT_DEPLOYMENT_CHECK_RETENTION_DAYS;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private OrganizationStatus status = OrganizationStatus.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Organization() {
    }

    public Organization(String name) {
        this.name = name;
    }

    /**
     * An organization created by the public signup form, which nobody has signed in to yet
     * (FZ-082).
     *
     * <p>A named constructor rather than a setter, so the unverified state can only be
     * reached deliberately. Everything else that creates an organization -- the
     * provisioning script, the seed script, tests -- is already vouched for by whoever ran
     * it, and gets {@code ACTIVE} from the field default.
     */
    public static Organization pendingVerification(String name) {
        Organization organization = new Organization(name);
        organization.status = OrganizationStatus.PENDING_VERIFICATION;
        return organization;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getDeploymentCheckRetentionDays() {
        return deploymentCheckRetentionDays;
    }

    /** Bounded here as well as in the database, so a bad value is a 400 rather than a 500. */
    public void setDeploymentCheckRetentionDays(int days) {
        if (days < MIN_DEPLOYMENT_CHECK_RETENTION_DAYS || days > MAX_DEPLOYMENT_CHECK_RETENTION_DAYS) {
            throw new IllegalArgumentException("Retention must be between "
                    + MIN_DEPLOYMENT_CHECK_RETENTION_DAYS + " and "
                    + MAX_DEPLOYMENT_CHECK_RETENTION_DAYS + " days");
        }
        this.deploymentCheckRetentionDays = days;
    }

    public int getStartingSoonLeadTimeMinutes() {
        return startingSoonLeadTimeMinutes;
    }

    /**
     * Zero would make the warning fire as the freeze begins, which {@code ACTIVATED}
     * already covers; the upper bound is well past any window anyone plans a freeze over.
     * Enforced here as well as by the database so the rejection is a 400 rather than a 500.
     */
    public void setStartingSoonLeadTimeMinutes(int minutes) {
        if (minutes < MIN_STARTING_SOON_LEAD_TIME_MINUTES || minutes > MAX_STARTING_SOON_LEAD_TIME_MINUTES) {
            throw new IllegalArgumentException("Lead time must be between "
                    + MIN_STARTING_SOON_LEAD_TIME_MINUTES + " and "
                    + MAX_STARTING_SOON_LEAD_TIME_MINUTES + " minutes");
        }
        this.startingSoonLeadTimeMinutes = minutes;
    }

    public OrganizationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

}
