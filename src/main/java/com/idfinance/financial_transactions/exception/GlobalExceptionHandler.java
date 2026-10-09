package com.idfinance.financial_transactions.exception;

import com.idfinance.financial_transactions.web.RateLimitExceededException;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The one place where exceptions become HTTP responses.
 *
 * <p>
 * Every error leaves the API as {@code application/problem+json} (RFC 9457).
 * Services raise {@link DomainException} without knowing any status code, which
 * keeps the domain free of HTTP concerns; the mapping lives here and nowhere
 * else.
 */
@RestControllerAdvice
@Slf4j
public final class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final String TYPE_PREFIX = "https://financial-transactions.idfinance.com/problems/";

	private static final String GENERIC_FAILURE_DETAIL = "The request could not be processed";

	private static final String INVALID_FIELD_DETAIL = "is invalid";

	private static final String CONSTRAINT_VIOLATION_DETAIL = "The request violates a constraint the data itself enforces";

	private static final String MALFORMED_BODY_DETAIL = "The request body could not be read as JSON";

	@ExceptionHandler(DomainException.class)
	ResponseEntity<ProblemDetail> handleDomain(DomainException ex, WebRequest request) {
		return problem(statusOf(ex), ex.typeSuffix(), ex.getMessage(), request);
	}

	/**
	 * The rate limit is the one refusal that carries a {@code Retry-After}: the
	 * client knows how long to wait, so it does not have to guess.
	 */
	@ExceptionHandler(RateLimitExceededException.class)
	ResponseEntity<ProblemDetail> handleRateLimit(RateLimitExceededException ex, WebRequest request) {
		ProblemDetail body = problemDetail(HttpStatus.TOO_MANY_REQUESTS, ex.typeSuffix(), ex.getMessage(), request);
		body.setProperty("retryAfterSeconds", ex.retryAfterSeconds());

		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
				.header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()))
				.contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex, WebRequest request) {
		log.warn("Rejected a malformed request on {}: {}", request.getDescription(false), ex.getMessage());
		return problem(HttpStatus.BAD_REQUEST, "malformed-request", ex.getMessage(), request);
	}

	/**
	 * A constraint the database refuses — a CHECK, a UNIQUE or a foreign key.
	 *
	 * <p>
	 * These are the rules Bean Validation cannot express: {@code occurred_at}
	 * before 1990, a duplicate currency code, a rate for a currency that is not in
	 * the lookup. All of them are the client's input reaching further than the
	 * annotations allow, so they are answered as 400 rather than surfacing as a 500
	 * that reads like a server fault. Without this handler a request the
	 * specification calls invalid came back as an internal error, which is exactly
	 * the misreading that hides a bug in the caller.
	 *
	 * <p>
	 * The message is deliberately generic. The cause names the table and the
	 * constraint, and a response body that quotes it tells a caller about the
	 * schema rather than about what they should send; the full cause is logged
	 * instead.
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ProblemDetail> handleConstraintViolation(DataIntegrityViolationException ex, WebRequest request) {
		log.warn("A database constraint refused the request on {}: {}", request.getDescription(false),
				ex.getMostSpecificCause().getMessage());
		return problem(HttpStatus.BAD_REQUEST, "constraint-violated", CONSTRAINT_VIOLATION_DETAIL, request);
	}

	@ExceptionHandler(IllegalStateException.class)
	ResponseEntity<ProblemDetail> handleUnexpected(IllegalStateException ex, WebRequest request) {
		log.error("Unexpected failure on {}", request.getDescription(false), ex);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", GENERIC_FAILURE_DETAIL, request);
	}

	/**
	 * Bean Validation failures, with the offending fields listed under
	 * {@code errors}.
	 *
	 * <p>
	 * The signature is Spring Framework 7's:
	 * {@code (exception, headers, status, request)} returning
	 * {@code ResponseEntity<Object>}. The older three-argument form no longer
	 * overrides anything.
	 */
	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldViolation> violations = violationsOf(ex);

		ProblemDetail body = problemDetail(HttpStatus.BAD_REQUEST, "validation-failed", "The request body is not valid",
				request);
		body.setProperty("errors", violations);

		return ResponseEntity.badRequest().body(body);
	}

	/**
	 * A body that cannot be read at all — malformed JSON, a type Jackson cannot
	 * coerce, a timestamp that is not a timestamp.
	 *
	 * <p>
	 * Spring's own handling of this returns a {@code ProblemDetail} with no
	 * {@code type}, so the answer would have carried {@code about:blank} and missed
	 * every other field this class fills in. Overriding it keeps one shape for one
	 * API: whatever goes wrong, the body is the same problem document.
	 */
	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		log.warn("Could not read the request body on {}: {}", request.getDescription(false), ex.getMessage());

		return ResponseEntity.badRequest()
				.body(problemDetail(HttpStatus.BAD_REQUEST, "malformed-request", MALFORMED_BODY_DETAIL, request));
	}

	/**
	 * Reads both kinds of error Bean Validation produces. A {@link FieldError}
	 * points at a property and usually carries a message; an {@link ObjectError}
	 * does not, so its description is used instead.
	 */
	private static List<FieldViolation> violationsOf(MethodArgumentNotValidException ex) {
		return ex.getBindingResult().getAllErrors().stream().map(GlobalExceptionHandler::toViolation).toList();
	}

	private static FieldViolation toViolation(ObjectError error) {
		String field = (error instanceof FieldError fieldError) ? fieldError.getField() : error.getObjectName();
		String message = Optional.ofNullable(error.getDefaultMessage()).orElse(INVALID_FIELD_DETAIL);
		return new FieldViolation(field, message);
	}

	/**
	 * The status lives here rather than on the exceptions, so that a new domain
	 * exception forces a decision about how it is reported instead of silently
	 * becoming a 500.
	 */
	private HttpStatus statusOf(DomainException ex) {
		return switch (ex) {
			case NotFoundException ignored -> HttpStatus.NOT_FOUND;
			case RateNotFoundException ignored -> HttpStatus.UNPROCESSABLE_ENTITY;
			case ExternalServiceUnavailableException ignored -> HttpStatus.SERVICE_UNAVAILABLE;
			default -> HttpStatus.INTERNAL_SERVER_ERROR;
		};
	}

	private ResponseEntity<ProblemDetail> problem(HttpStatus status, String type, String detail, WebRequest request) {
		return ResponseEntity.status(status).body(problemDetail(status, type, detail, request));
	}

	/**
	 * Builds the body once, so every handler emits the same shape: {@code type},
	 * {@code title}, {@code status}, {@code detail} and {@code instance}.
	 */
	private static ProblemDetail problemDetail(HttpStatus status, String type, String detail, WebRequest request) {
		ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
		body.setType(URI.create(TYPE_PREFIX + type));
		body.setInstance(URI.create(request.getDescription(false).replaceFirst("^uri=", "")));
		return body;
	}

	/**
	 * @param field
	 *            the property that failed validation
	 * @param message
	 *            why it failed
	 */
	public record FieldViolation(String field, String message) {
	}

}
