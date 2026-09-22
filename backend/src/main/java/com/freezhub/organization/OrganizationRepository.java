package com.freezhub.organization;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    /**
     * The largest lead time any organization has configured (FZ-047).
     *
     * <p>Used to bound the starting-soon sweep: nothing outside this horizon can be due
     * for any tenant, so the sweep never has to consider every future restriction ever
     * created. Null when there are no organizations at all.
     */
    @Query("select max(o.startingSoonLeadTimeMinutes) from Organization o")
    Integer maxStartingSoonLeadTimeMinutes();

    /**
     * Marks an organization verified, if it was not already (FZ-082).
     *
     * <p>One conditional statement rather than read-then-write, for two reasons. It is
     * idempotent under concurrency — two tabs signing in at once cannot both think they
     * were first — and it costs nothing on the overwhelmingly common path, where the
     * organization is already {@code ACTIVE} and no row matches.
     *
     * @return 1 the first time, 0 every time after
     */
    @Modifying
    @Query("""
            update Organization o
               set o.status = com.freezhub.organization.OrganizationStatus.ACTIVE,
                   o.updatedAt = :now
             where o.id = :organizationId
               and o.status = com.freezhub.organization.OrganizationStatus.PENDING_VERIFICATION
            """)
    int markVerified(@Param("organizationId") Long organizationId, @Param("now") Instant now);

    /**
     * Organizations that signed up and never signed in, old enough to purge (FZ-082).
     *
     * <p>Returned rather than deleted in bulk because each one owns a Cognito identity
     * that has to go with it, and Cognito is not in the transaction.
     */
    @Query("""
            select o from Organization o
             where o.status = com.freezhub.organization.OrganizationStatus.PENDING_VERIFICATION
               and o.createdAt < :cutoff
             order by o.createdAt
            """)
    List<Organization> findUnverifiedCreatedBefore(@Param("cutoff") Instant cutoff);

}
