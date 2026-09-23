package com.freezhub.restriction;

import com.freezhub.audit.AuditActor;
import com.freezhub.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/restrictions")
public class ChangeRestrictionController {

    private final ChangeRestrictionService changeRestrictionService;
    private final RestrictionImpactService impacts;

    public ChangeRestrictionController(ChangeRestrictionService changeRestrictionService,
                                       RestrictionImpactService impacts) {
        this.changeRestrictionService = changeRestrictionService;
        this.impacts = impacts;
    }

    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RestrictionResponse create(@AuthenticationPrincipal AuthenticatedUser caller,
                                      @Valid @RequestBody RestrictionRequest request) {
        ChangeRestriction created =
                changeRestrictionService.create(caller.organizationId(), AuditActor.of(caller), request);
        return RestrictionResponse.from(created);
    }

    /**
     * Cancels a SCHEDULED or ACTIVE restriction (FZ-024). Modelled as an action rather
     * than DELETE: the restriction is kept as a record with status CANCELLED, not removed.
     * 409 if it is COMPLETED or already CANCELLED.
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @PostMapping("/{restrictionId}/cancel")
    public RestrictionResponse cancel(@AuthenticationPrincipal AuthenticatedUser caller,
                                      @PathVariable Long restrictionId) {
        return RestrictionResponse.from(changeRestrictionService.cancel(caller.organizationId(), AuditActor.of(caller), restrictionId));
    }

    /**
     * Full replacement of a still-SCHEDULED restriction's editable state (FZ-023).
     * 409 if it is no longer SCHEDULED, 404 if unknown or owned by another organization.
     */
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @PutMapping("/{restrictionId}")
    public RestrictionResponse update(@AuthenticationPrincipal AuthenticatedUser caller,
                                      @PathVariable Long restrictionId,
                                      @Valid @RequestBody RestrictionRequest request) {
        return RestrictionResponse.from(
                changeRestrictionService.update(caller.organizationId(), AuditActor.of(caller), restrictionId, request));
    }

    /** One restriction with its scope. 404 if unknown or owned by another organization. */
    @GetMapping("/{restrictionId}")
    public RestrictionResponse get(@AuthenticationPrincipal AuthenticatedUser caller,
                                   @PathVariable Long restrictionId) {
        return RestrictionResponse.from(changeRestrictionService.get(caller.organizationId(), restrictionId));
    }

    /**
     * What this restriction actually did (FZ-112).
     *
     * <p>Separate from the restriction itself on purpose: these are counts drawn from the
     * deployment checks and the notification outbox, and folding them into the detail
     * response would make every read of a restriction pay for aggregates it does not show —
     * including the dashboard's, which reads the in-force ones on every load.
     *
     * <p>Resolved through the service first, so an unknown restriction and another
     * organization's both 404 before any counting happens.
     */
    @GetMapping("/{restrictionId}/impact")
    public RestrictionImpact impact(@AuthenticationPrincipal AuthenticatedUser caller,
                                    @PathVariable Long restrictionId) {
        changeRestrictionService.get(caller.organizationId(), restrictionId);
        return impacts.of(caller.organizationId(), restrictionId);
    }

    /**
     * Restrictions belonging to the caller's organization, soonest-first.
     *
     * <p>{@code status} may be repeated to select several states at once
     * (e.g. {@code ?status=SCHEDULED&status=ACTIVE}); omitting it returns every status.
     * An unrecognised value is rejected with 400 by Spring's enum conversion.
     */
    @GetMapping
    public List<RestrictionSummaryResponse> list(@AuthenticationPrincipal AuthenticatedUser caller,
                                                 @RequestParam(required = false) List<RestrictionStatus> status) {
        return changeRestrictionService.list(caller.organizationId(), status).stream()
                .map(RestrictionSummaryResponse::from)
                .toList();
    }

}
