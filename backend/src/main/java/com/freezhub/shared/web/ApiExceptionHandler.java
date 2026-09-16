package com.freezhub.shared.web;

import com.freezhub.shared.ratelimit.RateLimitExceededException;
import com.freezhub.subscription.OrganizationSuspendedException;
import com.freezhub.subscription.PlanFeatureUnavailableException;
import com.freezhub.subscription.PlanLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/**
 * One error shape for the whole API (FZ-061): RFC 9457 Problem Details, served as
 * {@code application/problem+json}.
 *
 * <p>Before this, a client saw two different shapes and a great deal of nothing. Reasons
 * the API had deliberately written — "a team with this name already exists" — arrived as a
 * bare status code, and a validation failure said only {@code 400} without naming the
 * field that caused it.
 *
 * <p><strong>The rule that matters here: a deliberate rejection explains itself; an
 * unexpected failure never does.</strong> Anything the application chose to reject carries
 * its reason to the client. Anything else is logged in full and answered with a fixed
 * sentence, because the text of an unexpected exception is a stack of internal detail —
 * table names, class names, occasionally values — that a caller must never receive.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Deliberately not a link to documentation that does not exist. */
    private static final URI NO_TYPE = URI.create("about:blank");

    /**
     * A plan limit, answered as {@code 402 Payment Required} (FZ-081, {@code D-22}).
     *
     * <p>{@code 402} because no other status says what happened: the request was
     * well-formed, so not {@code 400}; the caller is permitted, so not {@code 403};
     * nothing conflicts, so not {@code 409}. The plan refused.
     *
     * <p>The numbers travel as extensions so a UI can say "10 of 10 applications used"
     * and offer the upgrade, rather than rendering a sentence and a dead end.
     */
    @ExceptionHandler(PlanLimitExceededException.class)
    ProblemDetail handlePlanLimit(PlanLimitExceededException exception, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.PAYMENT_REQUIRED.value(),
                exception.getMessage(), request);
        problem.setProperty("plan", exception.plan().name());
        problem.setProperty("resource", exception.resource());
        problem.setProperty("limit", exception.limit());
        problem.setProperty("current", exception.current());
        return problem;
    }

    /**
     * A capability the plan does not carry at all (FZ-143).
     *
     * <p>Same {@code 402} as a limit, and deliberately no {@code limit} or {@code current}
     * extension: there is no count to show, and inventing one would invite a usage bar for
     * something that has no usage.
     */
    @ExceptionHandler(PlanFeatureUnavailableException.class)
    ProblemDetail handlePlanFeature(PlanFeatureUnavailableException exception, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.PAYMENT_REQUIRED.value(),
                exception.getMessage(), request);
        problem.setProperty("plan", exception.plan().name());
        problem.setProperty("feature", exception.feature());
        return problem;
    }

    /** A suspended or cancelled organization attempting a write (FZ-081). */
    @ExceptionHandler(OrganizationSuspendedException.class)
    ProblemDetail handleSuspended(OrganizationSuspendedException exception, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.PAYMENT_REQUIRED.value(),
                exception.getMessage(), request);
        problem.setProperty("subscriptionStatus", exception.status().name());
        return problem;
    }

    /**
     * Too many requests from one caller (FZ-087).
     *
     * <p>{@code Retry-After} is set by the interceptor rather than here, because it is a
     * header and this builds a body. The seconds are repeated in {@code detail} so the
     * message stands on its own for a human reading a log.
     */
    @ExceptionHandler(RateLimitExceededException.class)
    ProblemDetail handleRateLimit(RateLimitExceededException exception, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.TOO_MANY_REQUESTS.value(),
                exception.getMessage(), request);
        problem.setProperty("retryAfterSeconds", Math.max(1, exception.retryAfter().toSeconds()));
        return problem;
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail handleResponseStatus(ResponseStatusException exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        String detail = exception.getReason() != null
                ? exception.getReason()
                : (status != null ? status.getReasonPhrase() : "Request failed");

        return problem(exception.getStatusCode().value(), detail, request);
    }

    /**
     * Bean Validation failures, with the offending fields named.
     *
     * <p>The {@code errors} extension is the point: "request validation failed" tells a
     * caller nothing they can act on, and a form cannot highlight a field it was not told
     * about. {@code detail} stays readable on its own for clients that ignore extensions.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<ApiFieldError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiFieldError(error.getField(), messageOf(error)))
                .toList();

        String detail = errors.size() == 1
                ? errors.getFirst().field() + " " + errors.getFirst().message()
                : "The request has " + errors.size() + " invalid fields.";

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST.value(), detail, request);
        problem.setProperty("errors", errors);
        return problem;
    }

    /**
     * A body Jackson could not read: malformed JSON, or a value outside an enum — an
     * unsupported {@code action} on the Policy API arrives here.
     *
     * <p>The exception's own message is not returned. It quotes the offending JSON and
     * names the Java types it tried to bind, which is internal detail.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadableBody(HttpMessageNotReadableException exception,
                                       HttpServletRequest request) {
        log.debug("Rejected an unreadable request body on {}", request.getRequestURI(), exception);
        return problem(HttpStatus.BAD_REQUEST.value(),
                "The request body is malformed, or a field holds an unsupported value.", request);
    }

    /** A query parameter that will not convert — a repeated {@code ?status=} with a bad value. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException exception,
                                     HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST.value(),
                "'" + exception.getName() + "' holds an unsupported value.", request);
    }

    /** Authenticated but not permitted — currently only the Administrator-only endpoints. */
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN.value(),
                "This action requires an administrator.", request);
    }

    /**
     * Everything unforeseen.
     *
     * <p>Logged in full with the path, so it is diagnosable; answered with a fixed
     * sentence, so nothing internal reaches the caller. This is the one handler where the
     * exception's message must never become {@code detail}.
     *
     * <p>Spring's own web exceptions are the exception to "everything is a 500", and the
     * reason for the {@link ErrorResponse} check: an unmapped path, a method that is not
     * allowed and an unsupported content type all arrive here, and every one of them
     * carries the status it should be answered with. Reporting {@code 500} instead sends
     * whoever is debugging it looking for a fault that is not there — which is exactly
     * what happened when this handler was first added, caught by the machine-chain test
     * asserting a valid API key against an unmapped policy path gets a {@code 404}.
     *
     * <p>The check is on the interface rather than on a list of exception types, so a
     * Spring exception this code has never heard of is still answered correctly.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception, HttpServletRequest request) {
        int status = exception instanceof ErrorResponse errorResponse
                ? errorResponse.getStatusCode().value()
                : HttpStatus.INTERNAL_SERVER_ERROR.value();

        // Only a genuine fault is worth an ERROR line; a 404 is a client mistake.
        if (status >= 500) {
            log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), exception);
        } else {
            log.debug("Rejected {} {} with {}", request.getMethod(), request.getRequestURI(), status, exception);
        }

        HttpStatus resolved = HttpStatus.resolve(status);
        // The status is taken from the exception; the wording never is.
        String detail = status >= 500 || resolved == null
                ? "The request could not be completed."
                : resolved.getReasonPhrase();

        return problem(status, detail, request);
    }

    private ProblemDetail problem(int status, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), detail);
        problem.setType(NO_TYPE);
        problem.setInstance(URI.create(request.getRequestURI()));

        HttpStatus resolved = HttpStatus.resolve(status);
        if (resolved != null) {
            problem.setTitle(resolved.getReasonPhrase());
        }

        // RFC 9457 extensions. The request id is the one that matters: it turns "it
        // failed at about three o'clock" into an exact log lookup, and it is on every
        // line the request produced (FZ-062).
        problem.setProperty("timestamp", Instant.now());
        String requestId = RequestIdFilter.current();
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
        return problem;
    }

    /** Bean Validation's own message, which reads as a sentence fragment: "must not be blank". */
    private String messageOf(FieldError error) {
        return error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage();
    }

    /** One invalid field, named so a form can point at it. */
    public record ApiFieldError(String field, String message) {
    }

}
