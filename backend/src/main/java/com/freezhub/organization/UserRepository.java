package com.freezhub.organization;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByExternalSubject(String externalSubject);

    /**
     * Email is unique per organization, not globally, so this can legitimately return
     * several users. Used by local development sign-in (FZ-035), which reports an
     * ambiguous email rather than guessing which organization was meant.
     */
    List<User> findAllByEmail(String email);

    /**
     * Who to tell when something commercial happens to the organization (FZ-084).
     *
     * <p>Active administrators only (FZ-212). Someone whose access was withdrawn is no longer
     * the person a failed payment should be explained to, and emailing them would tell a
     * former employee about their old employer's billing.
     */
    List<User> findAllByOrganizationIdAndRoleAndDeactivatedAtIsNull(Long organizationId, UserRole role);

    /** One member, only if they belong to this organization — cross-tenant is a 404 (FZ-212). */
    Optional<User> findByIdAndOrganizationId(Long id, Long organizationId);

    /** One page of the organization's members, in whatever order {@code pageable} sorts (FZ-212). */
    Page<User> findAllByOrganizationId(Long organizationId, Pageable pageable);

    /** How many people can still administer the organization (FZ-212). */
    long countByOrganizationIdAndRoleAndDeactivatedAtIsNull(Long organizationId, UserRole role);

    boolean existsByOrganizationIdAndEmail(Long organizationId, String email);

    /** Every user of one organization, to remove with it when it is purged (FZ-082). */
    List<User> findAllByOrganizationId(Long organizationId);

}
