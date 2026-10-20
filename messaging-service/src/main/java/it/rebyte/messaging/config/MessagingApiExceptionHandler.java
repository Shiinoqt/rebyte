package it.rebyte.messaging.config;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import it.rebyte.commons.error.AbstractApiExceptionHandler;
import it.rebyte.commons.error.ProblemTypes;
import it.rebyte.messaging.error.ConversationExistsException;

/**
 * The only advice in this module: a second {@code @RestControllerAdvice}
 * would make Boot's auto-configured problem handler back off (see
 * {@link AbstractApiExceptionHandler}). Method-security denials are thrown
 * from inside the controller call, so they reach this advice instead of the
 * servlet-level access denied handler and need an RFC 9457 body here.
 */
@RestControllerAdvice
public class MessagingApiExceptionHandler extends AbstractApiExceptionHandler {

    private static final String ACCESS_DENIED_CODE = "access-denied";

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access is denied");
        problemDetail.setType(ProblemTypes.forCode(ACCESS_DENIED_CODE));
        problemDetail.setTitle(ACCESS_DENIED_CODE);
        return handleExceptionInternal(ex, problemDetail, new HttpHeaders(), HttpStatus.FORBIDDEN, request);
    }

    /** Same body as every DomainException, plus the id of the conversation the caller should open instead. */
    @ExceptionHandler(ConversationExistsException.class)
    public ResponseEntity<Object> handleConversationExists(ConversationExistsException ex, WebRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problemDetail.setType(ProblemTypes.forCode(ex.getCode()));
        problemDetail.setTitle(ex.getCode());
        problemDetail.setProperty("conversationId", ex.getConversationId());
        return handleExceptionInternal(ex, problemDetail, new HttpHeaders(), ex.getStatus(), request);
    }
}
