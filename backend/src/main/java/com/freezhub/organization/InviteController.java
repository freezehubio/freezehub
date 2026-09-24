package com.freezhub.organization;

import com.freezhub.audit.AuditActor;
import com.freezhub.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InviteController {

    private final InviteService inviteService;

    public InviteController(InviteService inviteService) {
        this.inviteService = inviteService;
    }

    @PostMapping("/api/invites")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteUserResponse invite(@AuthenticationPrincipal AuthenticatedUser caller,
                                      @Valid @RequestBody InviteUserRequest request) {
        User invited = inviteService.invite(caller.organizationId(), AuditActor.of(caller),
                request.email(), request.role());
        return InviteUserResponse.from(invited);
    }

}
