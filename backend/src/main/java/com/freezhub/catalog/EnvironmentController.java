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
@RequestMapping("/api/environments")
public class EnvironmentController {

    private final EnvironmentService environmentService;

    public EnvironmentController(EnvironmentService environmentService) {
        this.environmentService = environmentService;
    }

    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EnvironmentResponse create(@AuthenticationPrincipal AuthenticatedUser caller,
                                       @Valid @RequestBody EnvironmentRequest request) {
        return EnvironmentResponse.from(environmentService.create(caller.organizationId(), AuditActor.of(caller), request.name()));
    }

    @GetMapping
    public List<EnvironmentResponse> list(@AuthenticationPrincipal AuthenticatedUser caller) {
        return environmentService.list(caller.organizationId()).stream().map(EnvironmentResponse::from).toList();
    }

    @GetMapping("/{environmentId}")
    public EnvironmentResponse get(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long environmentId) {
        return EnvironmentResponse.from(environmentService.get(caller.organizationId(), environmentId));
    }

    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @PatchMapping("/{environmentId}")
    public EnvironmentResponse rename(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long environmentId,
                                       @Valid @RequestBody EnvironmentRequest request) {
        return EnvironmentResponse.from(environmentService.rename(caller.organizationId(), AuditActor.of(caller), environmentId, request.name()));
    }

    @PreAuthorize("hasRole('ADMINISTRATOR')")
    @DeleteMapping("/{environmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long environmentId) {
        environmentService.delete(caller.organizationId(), AuditActor.of(caller), environmentId);
    }

}
