package com.freezhub.organization;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByExternalSubject(String externalSubject);

    /**
     * Email is unique per organization, not globally, so this can legitimately return
     * several users. Used by local development sign-in (FZ-035), which reports an
     * ambiguous email rather than guessing which organization was meant.
     */
    List<User> findAllByEmail(String email);

    /** Who to tell when something commercial happens to the organization (FZ-084). */
    List<User> findAllByOrganizationIdAndRole(Long organizationId, UserRole role);

    boolean existsByOrganizationIdAndEmail(Long organizationId, String email);

    /** Every user of one organization, to remove with it when it is purged (FZ-082). */
    List<User> findAllByOrganizationId(Long organizationId);

}
