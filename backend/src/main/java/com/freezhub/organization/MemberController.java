package com.freezhub.organization;

import com.freezhub.audit.AuditActor;
import com.freezhub.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The people in an organization, for its administrators (FZ-212).
 *
 * <p>Administrator-only throughout, including the list: a member does not need the list of
 * everyone's email address to do anything the product offers them, and it is personal data.
 *
 * <p>Adding someone is {@code POST /api/invites}, which predates this and is unchanged in
 * shape. Removing someone is {@code deactivate} — see {@link MemberService} for why not
 * delete.
 */
@RestController
@RequestMapping("/api/members")
@PreAuthorize("hasRole('ADMINISTRATOR')")
public class MemberController {

    private final MemberService members;

    public MemberController(MemberService members) {
        this.members = members;
    }

    static final int DEFAULT_PAGE_SIZE = 25;
    static final int MAX_PAGE_SIZE = 100;

    /**
     * {@code page} counts from 0. Out-of-range values are clamped rather than refused, as the
     * audit trail's {@code limit} is: a page past the end is an empty page, not an error.
     */
    @GetMapping
    public MemberPage list(@AuthenticationPrincipal AuthenticatedUser caller,
                           @RequestParam(required = false) Integer page,
                           @RequestParam(required = false) Integer size) {
        int pageIndex = page == null ? 0 : Math.max(page, 0);
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : Math.clamp(size, 1, MAX_PAGE_SIZE);
        return MemberPage.from(members.list(caller.organizationId(), pageIndex, pageSize));
    }

    /** A partial update with one field, matching how integrations are changed. */
    @PatchMapping("/{memberId}")
    public MemberResponse changeRole(@AuthenticationPrincipal AuthenticatedUser caller,
                                     @PathVariable Long memberId,
                                     @Valid @RequestBody RoleChange request) {
        return MemberResponse.from(
                members.changeRole(caller.organizationId(), AuditActor.of(caller), memberId, request.role()));
    }

    /** An action rather than a field, like cancelling a restriction or revoking a key. */
    @PostMapping("/{memberId}/deactivate")
    public MemberResponse deactivate(@AuthenticationPrincipal AuthenticatedUser caller,
                                     @PathVariable Long memberId) {
        return MemberResponse.from(
                members.deactivate(caller.organizationId(), AuditActor.of(caller), memberId, Instant.now()));
    }

    @PostMapping("/{memberId}/reactivate")
    public MemberResponse reactivate(@AuthenticationPrincipal AuthenticatedUser caller,
                                     @PathVariable Long memberId) {
        return MemberResponse.from(
                members.reactivate(caller.organizationId(), AuditActor.of(caller), memberId));
    }

    public record RoleChange(@NotNull UserRole role) {
    }
}
