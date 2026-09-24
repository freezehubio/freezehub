package com.freezhub.organization;

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

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "external_subject", nullable = false)
    private String externalSubject;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * When this person's access was withdrawn, or null while they have it (FZ-212).
     *
     * <p>Deactivation, not deletion: the row stays so the audit trail keeps naming the
     * person for what they did, and so a mistaken removal can be undone. It is enforced at
     * sign-in — {@code UserResolvingJwtAuthenticationConverter} refuses a deactivated user on
     * every request, so withdrawal takes effect on the next call rather than when a token
     * expires.
     */
    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    protected User() {
    }

    public User(Long organizationId, String externalSubject, String email, UserRole role) {
        this.organizationId = organizationId;
        this.externalSubject = externalSubject;
        this.email = email;
        this.role = role;
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

    public Long getOrganizationId() {
        return organizationId;
    }

    public String getExternalSubject() {
        return externalSubject;
    }

    public String getEmail() {
        return email;
    }

    public UserRole getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeactivatedAt() {
        return deactivatedAt;
    }

    public boolean isActive() {
        return deactivatedAt == null;
    }

    /**
     * Changes what this person may do. The rule about the last administrator is not here but
     * in {@code MemberService}, because it is a rule about the organization, not the person,
     * and only the service can see the other members.
     */
    public void changeRole(UserRole role) {
        this.role = role;
    }

    /** Withdraws access. Idempotent: deactivating twice keeps the first time. */
    public void deactivate(Instant now) {
        if (deactivatedAt == null) {
            deactivatedAt = now;
        }
    }

    public void reactivate() {
        deactivatedAt = null;
    }

}
