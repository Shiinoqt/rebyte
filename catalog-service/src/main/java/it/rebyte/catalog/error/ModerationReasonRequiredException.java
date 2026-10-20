package it.rebyte.catalog.error;
import org.springframework.http.HttpStatus; import it.rebyte.commons.error.DomainException;
public class ModerationReasonRequiredException extends DomainException{public ModerationReasonRequiredException(){super(HttpStatus.UNPROCESSABLE_ENTITY,"moderation-reason-required","A moderation reason is required");}}
