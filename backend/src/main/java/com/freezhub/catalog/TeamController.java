package com.freezhub.catalog;

import com.freezhub.audit.AuditActor;
import com.freezhub.shared.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/teams")
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TeamResponse create(@AuthenticationPrincipal AuthenticatedUser caller, @Valid @RequestBody TeamRequest request) {
        return TeamResponse.from(teamService.create(caller.organizationId(), AuditActor.of(caller), request.name()));
    }

    @GetMapping
    public List<TeamResponse> list(@AuthenticationPrincipal AuthenticatedUser caller) {
        return teamService.list(caller.organizationId()).stream().map(TeamResponse::from).toList();
    }

    @GetMapping("/{teamId}")
    public TeamResponse get(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long teamId) {
        return TeamResponse.from(teamService.get(caller.organizationId(), teamId));
    }

    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @PatchMapping("/{teamId}")
    public TeamResponse rename(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long teamId,
                                @Valid @RequestBody TeamRequest request) {
        return TeamResponse.from(teamService.rename(caller.organizationId(), AuditActor.of(caller), teamId, request.name()));
    }

    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @DeleteMapping("/{teamId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long teamId) {
        teamService.delete(caller.organizationId(), AuditActor.of(caller), teamId);
    }

}
