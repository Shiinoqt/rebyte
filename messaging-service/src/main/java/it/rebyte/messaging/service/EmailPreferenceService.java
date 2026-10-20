package it.rebyte.messaging.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;

import it.rebyte.messaging.domain.EmailPreference;
import it.rebyte.messaging.dto.EmailPreferenceView;
import it.rebyte.messaging.repository.EmailPreferenceRepository;

@Service
public class EmailPreferenceService {

    private final EmailPreferenceRepository preferences;

    public EmailPreferenceService(EmailPreferenceRepository preferences) {
        this.preferences = preferences;
    }

    public boolean isEnabled(String sub) {
        return preferences.findById(sub).map(EmailPreference::isEmailEnabled).orElse(true);
    }

    /** Upserts by user sub; Mongo stores dates in milliseconds, so the stamp is truncated up front. */
    public EmailPreferenceView setEnabled(String sub, boolean enabled) {
        EmailPreference saved = preferences.save(
                new EmailPreference(sub, enabled, Instant.now().truncatedTo(ChronoUnit.MILLIS)));
        return new EmailPreferenceView(saved.isEmailEnabled());
    }
}
