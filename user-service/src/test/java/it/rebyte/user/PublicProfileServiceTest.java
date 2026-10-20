package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import it.rebyte.user.domain.User;
import it.rebyte.user.dto.PublicProfileResponse;
import it.rebyte.user.mapper.UserMapper;
import it.rebyte.user.repository.DeveloperProfileRepository;
import it.rebyte.user.repository.FollowRepository;
import it.rebyte.user.repository.SellerReviewRepository;
import it.rebyte.user.repository.SellerSaleRepository;
import it.rebyte.user.repository.UserRepository;
import it.rebyte.user.service.UserProfileService;
import it.rebyte.user.service.UserService;
import it.rebyte.user.service.UsernameRules;

class PublicProfileServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final SellerReviewRepository sellerReviewRepository = mock(SellerReviewRepository.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final DeveloperProfileRepository developerProfileRepository = mock(DeveloperProfileRepository.class);
    private final SellerSaleRepository sellerSaleRepository = mock(SellerSaleRepository.class);
    private final FollowRepository followRepository = mock(FollowRepository.class);
    private final UserProfileService service = new UserProfileService(
            userRepository, new UsernameRules(), sellerReviewRepository, userMapper, developerProfileRepository,
            sellerSaleRepository, followRepository);

    @Test
    void noReviewsYieldsNullAverageAndZeroCount() {
        String sub = "sub-1";
        User user = new User(sub, "handle1", Instant.now());
        when(userRepository.findById(sub)).thenReturn(Optional.of(user));
        when(sellerReviewRepository.countBySellerSub(sub)).thenReturn(0L);
        when(userMapper.toPublicProfileResponse(user, null, 0L, 0L, List.of(), false, 0L, 0L, false))
                .thenReturn(new PublicProfileResponse(sub, "handle1", null, user.getRegistration(),
                        null, null, false, null, 0L, 0L, List.of(), 0L, 0L, false));

        PublicProfileResponse response = service.getPublicProfile(sub, null);

        assertThat(response.ratingAvg()).isNull();
        assertThat(response.ratingCount()).isZero();
        assertThat(response.activeListings()).isEmpty();
    }

    @Test
    void nullAvatarIsExposedAsNull() {
        String sub = "sub-2";
        User user = new User(sub, "handle2", Instant.now());
        when(userRepository.findById(sub)).thenReturn(Optional.of(user));
        when(sellerReviewRepository.countBySellerSub(sub)).thenReturn(0L);
        when(userMapper.toPublicProfileResponse(user, null, 0L, 0L, List.of(), false, 0L, 0L, false))
                .thenReturn(new PublicProfileResponse(sub, "handle2", null, user.getRegistration(),
                        null, null, false, null, 0L, 0L, List.of(), 0L, 0L, false));

        PublicProfileResponse response = service.getPublicProfile(sub, null);

        assertThat(response.avatar()).isNull();
    }

    @Test
    void reviewsPresentYieldNonNullAverage() {
        String sub = "sub-3";
        User user = new User(sub, "handle3", Instant.now());
        when(userRepository.findById(sub)).thenReturn(Optional.of(user));
        when(sellerReviewRepository.countBySellerSub(sub)).thenReturn(2L);
        when(sellerReviewRepository.averageRatingBySellerSub(sub)).thenReturn(4.5);
        when(sellerSaleRepository.countCompletedSales(sub)).thenReturn(3L);
        when(userMapper.toPublicProfileResponse(user, 4.5, 2L, 3L, List.of(), false, 0L, 0L, false))
                .thenReturn(new PublicProfileResponse(sub, "handle3", null, user.getRegistration(),
                        null, null, false, 4.5, 2L, 3L, List.of(), 0L, 0L, false));

        PublicProfileResponse response = service.getPublicProfile(sub, null);

        assertThat(response.ratingAvg()).isEqualTo(4.5);
        assertThat(response.ratingCount()).isEqualTo(2L);
        assertThat(response.completedSales()).isEqualTo(3L);
    }

    @Test
    void developerProfileExistenceIsReflectedAsIsDeveloper() {
        String sub = "sub-4";
        User user = new User(sub, "handle4", Instant.now());
        when(userRepository.findById(sub)).thenReturn(Optional.of(user));
        when(sellerReviewRepository.countBySellerSub(sub)).thenReturn(0L);
        when(developerProfileRepository.existsById(sub)).thenReturn(true);
        when(userMapper.toPublicProfileResponse(user, null, 0L, 0L, List.of(), true, 0L, 0L, false))
                .thenReturn(new PublicProfileResponse(sub, "handle4", null, user.getRegistration(),
                        null, null, true, null, 0L, 0L, List.of(), 0L, 0L, false));

        PublicProfileResponse response = service.getPublicProfile(sub, null);

        assertThat(response.isDeveloper()).isTrue();
    }

    @Test
    void neverProvisionedSubIsSynthesizedWithSameShape() {
        String sub = "9f8e7d6c-aaaa-bbbb-cccc-000000000000";
        when(userRepository.findById(sub)).thenReturn(Optional.empty());
        when(sellerReviewRepository.countBySellerSub(sub)).thenReturn(0L);

        PublicProfileResponse response = service.getPublicProfile(sub, null);

        assertThat(response.sub()).isEqualTo(sub);
        assertThat(response.username()).isEqualTo(UserService.generateHandle(sub));
        assertThat(response.ratingAvg()).isNull();
        assertThat(response.ratingCount()).isZero();
        assertThat(response.completedSales()).isZero();
        assertThat(response.activeListings()).isEmpty();
        assertThat(response.isDeveloper()).isFalse();
        assertThat(response.avatar()).isNull();
        assertThat(response.registration()).isNull();
        assertThat(response.followerCount()).isZero();
        assertThat(response.followingCount()).isZero();
        assertThat(response.followedByMe()).isFalse();
    }

    @Test
    void neverProvisionedSubCarriesItsLocalFollowCounts() {
        String sub = "9f8e7d6c-aaaa-bbbb-cccc-111111111111";
        when(userRepository.findById(sub)).thenReturn(Optional.empty());
        when(followRepository.countFollowers(sub)).thenReturn(2L);
        when(followRepository.countFollowing(sub)).thenReturn(3L);

        PublicProfileResponse response = service.getPublicProfile(sub, null);

        assertThat(response.registration()).isNull();
        assertThat(response.followerCount()).isEqualTo(2L);
        assertThat(response.followingCount()).isEqualTo(3L);
        assertThat(response.followedByMe()).isFalse();
    }
}
