package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import it.rebyte.catalog.client.UserServiceClient;
import it.rebyte.catalog.client.UserSummary;
import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.PaymentMethod;
import it.rebyte.catalog.domain.Platform;
import it.rebyte.catalog.dto.PublishSoftwareRequest;
import it.rebyte.catalog.error.DeveloperProfileRequiredException;
import it.rebyte.catalog.error.DeveloperServiceUnavailableException;
import it.rebyte.catalog.outbox.DomainEventRecorder;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.catalog.service.AttributeValueValidator;
import it.rebyte.catalog.service.SoftwarePublishService;

class SoftwarePublishGateTest {

    private final UserServiceClient users = mock(UserServiceClient.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final AttributeValueValidator validator = mock(AttributeValueValidator.class);
    private final DomainEventRecorder events = mock(DomainEventRecorder.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
    private SoftwarePublishService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any(DefaultTransactionDefinition.class))).thenReturn(transactionStatus);
        service = new SoftwarePublishService(users, mongo, categories, validator, events, transactionManager);
    }

    @Test
    void developerProfileIsReadBeforeTheMongoTransactionStarts() {
        Category category = new Category("software-leaf", "Drivers", null, List.of("Software"),
                List.of(), List.of(), "admin", Instant.now(), 0L);
        when(users.getSummary("developer")).thenReturn(new UserSummary("developer", "dev", null, true, null, 0));
        when(categories.findById(category.getId())).thenReturn(java.util.Optional.of(category));
        when(validator.validate(any(), any())).thenReturn(Map.of());
        when(mongo.findAndModify(any(), any(), org.mockito.ArgumentMatchers.eq(Category.class))).thenReturn(category);
        when(mongo.insert(any(Listing.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Listing listing = service.publishSoftware(request(category.getId()), "developer");

        assertThat(listing.getSoftware()).isNotNull();
        InOrder order = inOrder(users, transactionManager);
        order.verify(users).getSummary("developer");
        order.verify(transactionManager).getTransaction(any(DefaultTransactionDefinition.class));
    }

    @Test
    void userWithoutDeveloperProfileIsRefusedBeforeAWriteTransaction() {
        when(users.getSummary("regular")).thenReturn(new UserSummary("regular", "user", null, false, null, 0));

        assertThatThrownBy(() -> service.publishSoftware(request("unused"), "regular"))
                .isInstanceOf(DeveloperProfileRequiredException.class);
        org.mockito.Mockito.verifyNoInteractions(transactionManager);
    }

    @Test
    void unavailableUserServiceIsNotReportedAsMissingDeveloperProfile() {
        when(users.getSummary("developer")).thenThrow(new IllegalStateException("unavailable"));

        assertThatThrownBy(() -> service.publishSoftware(request("unused"), "developer"))
                .isInstanceOf(DeveloperServiceUnavailableException.class)
                .isNotInstanceOf(DeveloperProfileRequiredException.class);
        org.mockito.Mockito.verifyNoInteractions(transactionManager);
    }

    private PublishSoftwareRequest request(String categoryId) {
        return new PublishSoftwareRequest("Linux driver", "Driver package", new BigDecimal("9.99"), categoryId,
                List.of(), Set.of(PaymentMethod.PAYPAL), Set.of(Platform.LINUX), "1.0.0", null, List.of(), null);
    }
}
