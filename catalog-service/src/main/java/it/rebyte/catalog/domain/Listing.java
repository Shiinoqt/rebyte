package it.rebyte.catalog.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Aggregate root del catalogo (ch. 12 del modello ristrutturato), documento
 * MongoDB nella collection {@code listing}. {@code id} e un {@code String}
 * che porta un {@link java.util.UUID#randomUUID()} — coniato internamente da
 * catalog-service, a differenza di {@code User.id}, che viene da Cognito
 * (02-RESEARCH.md Assumption A1).
 *
 * <p>{@code hardware}/{@code software} sono gli slot delle due parti tipate:
 * esattamente uno dei due e valorizzato su ogni riga reale
 * ({@code [V.Listing.exactlyOnePart]}). Lo slot {@code software} e
 * dichiarato ora — anche se resta sempre {@code null} su questo percorso —
 * cosi il piano 02-04, che introduce il vero {@code SoftwareListing}, non
 * deve riaprire questa classe per aggiungere il campo. {@link #type()}
 * deriva {@link ListingType} da quale parte e presente, mai da un campo
 * ridondante che potrebbe disallinearsi.
 *
 * <p>{@code price} e un {@link BigDecimal}, persistito come BSON
 * {@code Decimal128} dalla {@link it.rebyte.catalog.config.MongoConfig}
 * esplicita di questo modulo — senza quella configurazione sarebbe una
 * stringa BSON silenziosa (02-RESEARCH.md Pitfall 1).
 *
 * <p>{@code @Version} lega questo campo al meccanismo di locking ottimistico
 * di Spring Data, ma il suo valore dopo l'insert e anche la sequenza per
 * documento che il guard anti-stale dell'indexer usa (D-11) — nessun secondo
 * contatore.
 */
@Document(collection = "listing")
@CompoundIndex(name = "seller_status_publication",
        def = "{'sellerSub': 1, 'status': 1, 'publication': -1, '_id': -1}")
public class Listing {

    @Id
    private final String id;

    private final String title;
    private final String description;
    private final BigDecimal price;
    private final Instant publication;
    private final ListingStatus status;

    /** Al massimo cinque, ordinate (CAT-01). */
    private final List<String> photos;

    /** Mai vuoto — un venditore deve accettare almeno un metodo di pagamento. */
    private final Set<PaymentMethod> paymentMethods;

    private final String sellerSub;
    private final String categoryId;

    private final HardwareListing hardware;
    private final it.rebyte.catalog.domain.SoftwareListing software;

    /**
     * ATTR-03/D-02: submitted attribute values, keyed by {@link
     * AttributeDefinition#getId()} — never by name, for the same reason
     * {@code Category} uses a surrogate id (§10.6). Only {@link
     * it.rebyte.catalog.service.AttributeValueValidator} produces the values
     * stored here; nothing writes to this map without having validated
     * against the effective schema first. Never null — defaults to an empty
     * map so every reader (the search assembler in particular) can iterate
     * it unconditionally.
     */
    private final Map<String, AttributeValue> attributes;
    private final List<PriceChange> priceHistory;

    /** Technical saga correlation: a delayed release must not undo a later reservation. */
    private String reservationId;

    public String getReservationId() { return reservationId; }

    /**
     * Durable release tombstone (D-17/D-16 amendment). A {@code reservationId} lands here the
     * moment {@code release} is called for it, whether or not the listing was currently SOLD
     * under that id — so a release that overtakes a delayed reserve is not lost, and a delayed
     * reserve arriving afterwards can never mark this listing SOLD. Never cleared, and never
     * touched by a different buyer's reservation.
     */
    private Set<String> canceledReservationIds;

    public Set<String> getCanceledReservationIds() {
        return canceledReservationIds == null ? Set.of() : canceledReservationIds;
    }

    @Version
    private Long version;

    public Listing(String id, String title, String description, BigDecimal price, Instant publication,
            ListingStatus status, List<String> photos, Set<PaymentMethod> paymentMethods, String sellerSub,
            String categoryId, HardwareListing hardware, it.rebyte.catalog.domain.SoftwareListing software,
            Map<String, AttributeValue> attributes) {
        this(id, title, description, price, publication, status, photos, paymentMethods, sellerSub, categoryId,
                hardware, software, attributes, List.of());
    }

    @PersistenceCreator
    public Listing(String id, String title, String description, BigDecimal price, Instant publication,
            ListingStatus status, List<String> photos, Set<PaymentMethod> paymentMethods, String sellerSub,
            String categoryId, HardwareListing hardware, it.rebyte.catalog.domain.SoftwareListing software,
            Map<String, AttributeValue> attributes, List<PriceChange> priceHistory) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.price = price;
        this.publication = publication;
        this.status = status;
        this.photos = photos;
        this.paymentMethods = paymentMethods;
        this.sellerSub = sellerSub;
        this.categoryId = categoryId;
        this.hardware = hardware;
        this.software = software;
        this.attributes = attributes != null ? attributes : Map.of();
        this.priceHistory = priceHistory != null ? List.copyOf(priceHistory) : List.of();
    }

    /** {@code HARDWARE} quando la parte hardware e presente — mai un campo ridondante. */
    public ListingType type() {
        return hardware != null ? ListingType.HARDWARE : ListingType.SOFTWARE;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public Instant getPublication() {
        return publication;
    }

    public ListingStatus getStatus() {
        return status;
    }

    public List<String> getPhotos() {
        return photos;
    }

    public Set<PaymentMethod> getPaymentMethods() {
        return paymentMethods;
    }

    public String getSellerSub() {
        return sellerSub;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public HardwareListing getHardware() {
        return hardware;
    }

    public it.rebyte.catalog.domain.SoftwareListing getSoftware() {
        return software;
    }

    /** See field Javadoc — never null. */
    public Map<String, AttributeValue> getAttributes() {
        return attributes;
    }

    public List<PriceChange> getPriceHistory() { return priceHistory; }

    /** La sequenza per documento che il guard anti-stale dell'indexer usa (D-11) — non lo stato di locking ottimistico in se. */
    public Long getVersion() {
        return version;
    }

    /** Compatibility fixture type retained for the phase-01 internal contract. */
    @Deprecated
    public static class SoftwareListing extends it.rebyte.catalog.domain.SoftwareListing {
        public SoftwareListing() { super(); }
    }

}
