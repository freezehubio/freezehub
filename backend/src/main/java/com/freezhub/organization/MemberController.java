package com.freezhub.organization;

import com.freezhub.audit.AuditActor;
import com.freezhub.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @GetMapping
    public List<MemberResponse> list(@AuthenticationPrincipal AuthenticatedUser caller) {
        return members.list(caller.organizationId()).stream().map(MemberResponse::from).toList();
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
