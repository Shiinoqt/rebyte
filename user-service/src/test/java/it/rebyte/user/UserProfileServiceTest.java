package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import it.rebyte.user.domain.User;
import it.rebyte.user.dto.UpdateProfileRequest;
import it.rebyte.user.error.ReservedHandleException;
import it.rebyte.user.error.UsernameConflictException;
import it.rebyte.user.mapper.UserMapper;
import it.rebyte.user.repository.DeveloperProfileRepository;
import it.rebyte.user.repository.FollowRepository;
import it.rebyte.user.repository.SellerReviewRepository;
import it.rebyte.user.repository.SellerSaleRepository;
import it.rebyte.user.repository.UserRepository;
import it.rebyte.user.service.UserProfileService;
import it.rebyte.user.service.UsernameRules;

class UserProfileServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final SellerReviewRepository sellerReviewRepository = mock(SellerReviewRepository.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final UsernameRules usernameRules = new UsernameRules();
    private final DeveloperProfileRepository developerProfileRepository = mock(DeveloperProfileRepository.class);
    private final SellerSaleRepository sellerSaleRepository = mock(SellerSaleRepository.class);
    private final FollowRepository followRepository = mock(FollowRepository.class);
    private final UserProfileService service = new UserProfileService(
            userRepository, usernameRules, sellerReviewRepository, userMapper, developerProfileRepository,
            sellerSaleRepository, followRepository);

    @Test
    void updatesUsernameLocationAndBio() {
        User user = new User("sub-1", "old_handle", Instant.parse("2026-01-01T00:00:00Z"));
        when(userRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        User updated = service.updateProfile(user, new UpdateProfileRequest("new_handle", "Roma", "hi there"));

        assertThat(updated.getUsername()).isEqualTo("new_handle");
        assertThat(updated.getLocation()).isEqualTo("Roma");
        assertThat(updated.getBio()).isEqualTo("hi there");
    }

    @Test
    void idAndRegistrationAreNeverChanged() {
        Instant registration = Instant.parse("2026-01-01T00:00:00Z");
        User user = new User("sub-1", "old_handle", registration);
        when(userRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        User updated = service.updateProfile(user, new UpdateProfileRequest("new_handle", "Roma", "hi"));

        assertThat(updated.getId()).isEqualTo("sub-1");
        assertThat(updated.getRegistration()).isEqualTo(registration);
    }

    @Test
    void reservedShapeUsernameIsRejected() {
        User user = new User("sub-1", "old_handle", Instant.now());

        assertThatThrownBy(() -> service.updateProfile(user, new UpdateProfileRequest("user_1a2b3c4d", null, null)))
                .isInstanceOf(ReservedHandleException.class);
    }

    @Test
    void nonReservedLookalikeUsernameIsAccepted() {
        User user = new User("sub-1", "old_handle", Instant.now());
        when(userRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        User updated = service.updateProfile(user, new UpdateProfileRequest("user_friendly", null, null));

        assertThat(updated.getUsername()).isEqualTo("user_friendly");
    }

    @Test
    void uniqueViolationBecomesUsernameConflictException() {
        User user = new User("sub-1", "old_handle", Instant.now());
        when(userRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> service.updateProfile(user, new UpdateProfileRequest("taken_handle", null, null)))
                .isInstanceOf(UsernameConflictException.class);
    }

    @Test
    void invalidUsernameRejectionHappensBeforeAnyWrite() {
        User user = new User("sub-1", "old_handle", Instant.now());

        assertThatThrownBy(() -> service.updateProfile(user, new UpdateProfileRequest("x", null, null)))
                .isInstanceOf(it.rebyte.user.error.InvalidUsernameException.class);
    }
}
