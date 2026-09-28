package com.freezhub.demo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Somebody asking for a demo (FZ-083).
 *
 * <p><strong>The one entity with no {@code organizationId}.</strong> A demo request
 * predates the organization it might become, so there is nothing to scope it to — and
 * nothing in the tenant API may ever read it. It is written by one unauthenticated
 * endpoint and read by operators.
 *
 * <p>Every field here is personal data belonging to someone who is not yet a customer:
 * a name, a work email, an employer. None of it is logged.
 */
@Entity
@Table(name = "demo_request")
public class DemoRequest {

    /** Long enough for a real message, short enough not to be a place to paste a file. */
    public static final int MAX_MESSAGE_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String company;

    @Column(name = "team_size")
    private String teamSize;

    @Column
    private String message;

    @Column
    private String source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DemoRequestStatus status = DemoRequestStatus.NEW;

    @Column(name = "converted_organization_id")
    private Long convertedOrganizationId;

    @Column(name = "notified_at")
    private Instant notifiedAt;

    @Column(name = "notify_attempts", nullable = false)
    private int notifyAttempts;

    @Column(name = "next_notify_at", nullable = false)
    private Instant nextNotifyAt;

    @Column(name = "notify_error")
    private String notifyError;

    /*
     * Telling the prospect we have their request (FZ-217) — a second outbound message with
     * its own outcome, not a second attempt at the first. One may succeed while the other
     * fails, and each has to say so on its own.
     */
    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "acknowledge_attempts", nullable = false)
    private int acknowledgeAttempts;

    @Column(name = "next_acknowledge_at", nullable = false)
    private Instant nextAcknowledgeAt;

    @Column(name = "acknowledge_error")
    private String acknowledgeError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DemoRequest() {
    }

    public DemoRequest(String name, String email, String company, String teamSize,
                       String message, String source, Instant now) {
        this.name = name;
        this.email = email;
        this.company = company;
        this.teamSize = teamSize;
        this.message = message;
        this.source = source;
        this.status = DemoRequestStatus.NEW;
        this.nextNotifyAt = now.truncatedTo(ChronoUnit.MICROS);
        // Both are NOT NULL, and JPA inserts what the object holds rather than letting the
        // column default apply — so a row created without this would fail on insert, not
        // quietly take now() (FZ-217).
        this.nextAcknowledgeAt = this.nextNotifyAt;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getCompany() {
        return company;
    }

    public String getTeamSize() {
        return teamSize;
    }

    public String getMessage() {
        return message;
    }

    public String getSource() {
        return source;
    }

    public DemoRequestStatus getStatus() {
        return status;
    }

    public Long getConvertedOrganizationId() {
        return convertedOrganizationId;
    }

    public Instant getNotifiedAt() {
        return notifiedAt;
    }

    public int getNotifyAttempts() {
        return notifyAttempts;
    }

    public Instant getNextNotifyAt() {
        return nextNotifyAt;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public int getAcknowledgeAttempts() {
        return acknowledgeAttempts;
    }

    public String getAcknowledgeError() {
        return acknowledgeError;
    }

    public String getNotifyError() {
        return notifyError;
    }

    public boolean hasBeenNotified() {
        return notifiedAt != null;
    }

    void markNotified(Instant now) {
        this.notifiedAt = now.truncatedTo(ChronoUnit.MICROS);
        this.notifyError = null;
    }

    /**
     * Announced somewhere, but not everywhere (`FZ-214`).
     *
     * <p>Notified — so it is not retried and the channel that worked is not sent the same
     * lead again — while keeping what failed. Without this the two outcomes are
     * indistinguishable afterwards: a request announced on both channels and one announced
     * on one would look identical in the table, and a permanently broken channel would be
     * visible only in whatever logs had not yet rotated.
     */
    void markAcknowledged(Instant now) {
        this.acknowledgedAt = now.truncatedTo(ChronoUnit.MICROS);
        this.acknowledgeError = null;
    }

    /**
     * Records a failed acknowledgement and when to try again (`FZ-217`).
     *
     * <p>Retry matters more here than it looks. While SES is in the sandbox <b>every</b>
     * acknowledgement to a real prospect is rejected, because their address is not a
     * verified identity. Without a schedule those rows would stay permanently unanswered
     * once production access arrives.
     */
    void markAcknowledgementFailed(String error, Instant nextAttemptAt) {
        this.acknowledgeAttempts += 1;
        this.acknowledgeError = truncate(error);
        this.nextAcknowledgeAt = nextAttemptAt.truncatedTo(ChronoUnit.MICROS);
    }

    void markNotifiedWithFailures(Instant now, String error) {
        this.notifiedAt = now.truncatedTo(ChronoUnit.MICROS);
        this.notifyError = truncate(error);
    }

    /**
     * Records a failed attempt and when to try again.
     *
     * <p>The lead is not at risk either way — it was stored before anything was sent, so
     * the worst case is an operator finding it by query rather than in Slack. That is what
     * makes it safe to give up eventually instead of retrying for ever.
     */
    void markNotificationFailed(String error, Instant nextAttemptAt) {
        this.notifyAttempts += 1;
        this.notifyError = truncate(error);
        this.nextNotifyAt = nextAttemptAt.truncatedTo(ChronoUnit.MICROS);
    }

    void markConverted(Long organizationId) {
        this.status = DemoRequestStatus.CONVERTED;
        this.convertedOrganizationId = organizationId;
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
