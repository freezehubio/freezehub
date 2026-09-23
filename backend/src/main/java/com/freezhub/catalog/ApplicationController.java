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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    private final ApplicationService applicationService;

    public ApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationResponse create(@AuthenticationPrincipal AuthenticatedUser caller,
                                       @Valid @RequestBody ApplicationRequest request) {
        Application application = applicationService.create(caller.organizationId(), AuditActor.of(caller), request.name());
        return ApplicationResponse.from(application, List.of());
    }

    @GetMapping
    public List<ApplicationResponse> list(@AuthenticationPrincipal AuthenticatedUser caller) {
        return applicationService.list(caller.organizationId()).stream()
                .map(application -> ApplicationResponse.from(application, applicationService.teamIds(application.getId())))
                .toList();
    }

    @GetMapping("/{applicationId}")
    public ApplicationResponse get(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long applicationId) {
        Application application = applicationService.get(caller.organizationId(), applicationId);
        return ApplicationResponse.from(application, applicationService.teamIds(applicationId));
    }

    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @PatchMapping("/{applicationId}")
    public ApplicationResponse rename(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long applicationId,
                                       @Valid @RequestBody ApplicationRequest request) {
        Application application = applicationService.rename(caller.organizationId(), AuditActor.of(caller), applicationId, request.name());
        return ApplicationResponse.from(application, applicationService.teamIds(applicationId));
    }

    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @DeleteMapping("/{applicationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long applicationId) {
        applicationService.delete(caller.organizationId(), AuditActor.of(caller), applicationId);
    }

    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @PutMapping("/{applicationId}/teams/{teamId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void associateTeam(@AuthenticationPrincipal AuthenticatedUser caller,
                               @PathVariable Long applicationId, @PathVariable Long teamId) {
        applicationService.associateTeam(caller.organizationId(), AuditActor.of(caller), applicationId, teamId);
    }

    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @DeleteMapping("/{applicationId}/teams/{teamId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disassociateTeam(@AuthenticationPrincipal AuthenticatedUser caller,
                                  @PathVariable Long applicationId, @PathVariable Long teamId) {
        applicationService.disassociateTeam(caller.organizationId(), AuditActor.of(caller), applicationId, teamId);
    }

}
