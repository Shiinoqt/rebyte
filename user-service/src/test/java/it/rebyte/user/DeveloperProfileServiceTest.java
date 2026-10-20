package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import it.rebyte.user.domain.DeveloperProfile;
import it.rebyte.user.repository.DeveloperProfileRepository;
import it.rebyte.user.service.DeveloperProfileService;

class DeveloperProfileServiceTest {

    private final DeveloperProfileRepository repository = mock(DeveloperProfileRepository.class);
    private final DeveloperProfileService service = new DeveloperProfileService(repository);

    @Test
    void firstActivationCreatesRow() {
        String sub = "sub-1";
        when(repository.findById(sub)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        DeveloperProfile profile = service.activate(sub);

        assertThat(profile.getUserSub()).isEqualTo(sub);
        assertThat(profile.getActivatedAt()).isNotNull();
    }

    @Test
    void repeatActivationReturnsExistingActivationWithoutWriting() {
        String sub = "sub-1";
        Instant activatedAt = Instant.parse("2026-01-01T00:00:00Z");
        DeveloperProfile existing = new DeveloperProfile(sub, activatedAt);
        when(repository.findById(sub)).thenReturn(Optional.of(existing));

        DeveloperProfile profile = service.activate(sub);

        assertThat(profile.getActivatedAt()).isEqualTo(activatedAt);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void notActivatedReportsFalse() {
        String sub = "sub-2";
        when(repository.existsById(sub)).thenReturn(false);

        assertThat(service.isActivated(sub)).isFalse();
    }

    @Test
    void activatedReportsTrue() {
        String sub = "sub-3";
        when(repository.existsById(sub)).thenReturn(true);

        assertThat(service.isActivated(sub)).isTrue();
    }
}
