package com.freezhub.organization;

import com.freezhub.audit.AuditAction;
import com.freezhub.audit.AuditActor;
import com.freezhub.audit.AuditDetails;
import com.freezhub.audit.AuditResourceType;
import com.freezhub.audit.AuditTrail;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who belongs to an organization, and what they may do (FZ-212).
 *
 * <p><b>Removal is deactivation.</b> The operator's decision: a removed person cannot sign
 * in, but their row stays, so the audit trail keeps naming them for what they did and a
 * mistaken removal can be undone. It is also what SCIM means by removal ({@code active:
 * false}), so SSO can arrive later without changing the meaning of this screen.
 *
 * <p><b>An organization always keeps an active administrator.</b> Demoting or deactivating
 * the last one is refused. Nothing in the product could recover from it — only an
 * administrator can promote anyone — so the way back would be editing the database.
 *
 * <p><b>Changes are serialised per organization.</b> Every mutation locks the organization
 * row before it counts administrators. Two administrators demoting each other at the same
 * moment would otherwise each count two, each succeed, and leave none.
 *
 * <p>Every lookup is scoped to the caller's organization, so another tenant's member is a
 * {@code 404}, never a {@code 403} — existence is not revealed across tenants.
 */
@Service
public class MemberService {

    private final UserRepository users;
    private final OrganizationRepository organizations;
    private final AuditTrail auditTrail;

    public MemberService(UserRepository users, OrganizationRepository organizations, AuditTrail auditTrail) {
        this.users = users;
        this.organizations = organizations;
        this.auditTrail = auditTrail;
    }

    /**
     * Newest first, one page at a time: customers are companies, and a roster of hundreds is
     * not one response.
     *
     * <p><b>An offset, not a cursor</b> like the audit trail's. That one is a feed read as a
     * stream, where a skipped entry is a lost fact. This is a roster read as pages — the
     * screen says "page 2 of 7" — and if someone is invited between two page loads, one
     * person shows twice or once late, and nothing is lost. Newest first because the screen
     * puts the invitation above the list: whoever was just invited is right under it.
     */
    @Transactional(readOnly = true)
    public Page<User> list(Long organizationId, int page, int size) {
        return users.findAllByOrganizationId(organizationId, PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
    }

    @Transactional
    public User changeRole(Long organizationId, AuditActor actor, Long memberId, UserRole role) {
        lock(organizationId);
        User member = owned(organizationId, memberId);
        if (member.getRole() == role) {
            return member;
        }
        if (member.isActive() && member.getRole() == UserRole.ADMINISTRATOR) {
            requireAnotherActiveAdministrator(organizationId);
        }

        UserRole from = member.getRole();
        member.changeRole(role);
        User saved = users.save(member);
        auditTrail.record(organizationId, actor, AuditAction.USER_ROLE_CHANGED, AuditResourceType.USER,
                saved.getId(), AuditDetails.builder()
                        .with("email", saved.getEmail())
                        .with("from", from)
                        .with("to", role)
                        .toJson());
        return saved;
    }

    @Transactional
    public User deactivate(Long organizationId, AuditActor actor, Long memberId, Instant now) {
        lock(organizationId);
        User member = owned(organizationId, memberId);
        if (!member.isActive()) {
            return member;
        }
        if (member.getRole() == UserRole.ADMINISTRATOR) {
            requireAnotherActiveAdministrator(organizationId);
        }

        member.deactivate(now);
        User saved = users.save(member);
        auditTrail.record(organizationId, actor, AuditAction.USER_DEACTIVATED, AuditResourceType.USER,
                saved.getId(), AuditDetails.builder().with("email", saved.getEmail()).toJson());
        return saved;
    }

    @Transactional
    public User reactivate(Long organizationId, AuditActor actor, Long memberId) {
        lock(organizationId);
        User member = owned(organizationId, memberId);
        if (member.isActive()) {
            return member;
        }

        member.reactivate();
        User saved = users.save(member);
        auditTrail.record(organizationId, actor, AuditAction.USER_REACTIVATED, AuditResourceType.USER,
                saved.getId(), AuditDetails.builder().with("email", saved.getEmail()).toJson());
        return saved;
    }

    private void lock(Long organizationId) {
        organizations.lockById(organizationId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found"));
    }

    private User owned(Long organizationId, Long memberId) {
        return users.findByIdAndOrganizationId(memberId, organizationId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));
    }

    /**
     * Called only when the member being changed is an active administrator, so "another"
     * means more than one active administrator exists right now, counted under the lock.
     */
    private void requireAnotherActiveAdministrator(Long organizationId) {
        long active = users.countByOrganizationIdAndRoleAndDeactivatedAtIsNull(organizationId,
                UserRole.ADMINISTRATOR);
        if (active <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This is the organization's only active administrator. Make someone else an "
                            + "administrator first, or nobody will be able to manage the organization.");
        }
    }

}
