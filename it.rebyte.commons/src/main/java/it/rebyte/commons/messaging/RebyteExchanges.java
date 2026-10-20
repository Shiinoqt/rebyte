package it.rebyte.commons.messaging;

/**
 * Nome dell'exchange e routing key condivisi tra publisher e consumer (D-25).
 *
 * <p>Eccezione deliberata e circoscritta a D-22: D-22 vieta di condividere
 * qualunque cosa relativa all'outbox (tabella, relay, DTO di evento) perche la
 * *struttura* di un evento evolve quando i consumer vogliono nuovi campi, e
 * quella condivisione forzerebbe una traduzione o un accoppiamento non voluto.
 * Una routing key non e struttura: e un identificatore fisso che publisher e
 * consumer devono far combaciare esattamente — un valore diverso e l'unico
 * fallimento silenzioso del percorso di messaging (il binding non incrocia
 * nulla, i messaggi spariscono, nessun log lo segnala). Per questo, e solo
 * per questo, le stringhe vivono qui.
 */
public final class RebyteExchanges {

    /** Topic exchange dichiarato dal servizio publisher (D-24). */
    public static final String MARKETPLACE_EVENTS = "marketplace.events";

    /** Routing key dell'evento pubblicato da user-service al follow (SOCL-01). */
    public static final String USER_FOLLOWED = "user.followed";

    /** Routing key dell'evento pubblicato da user-service all'unfollow (SOCL-02). */
    public static final String USER_UNFOLLOWED = "user.unfollowed";

    /**
     * Routing key dell'evento {@code review.created}, pubblicato da
     * order-service a partire dalla Phase 4 (D-47). user-service e il
     * primo consumer reale di questa chiave, in anticipo sul publisher —
     * piano 01-05, {@code ReviewCreatedListener}.
     */
    public static final String REVIEW_CREATED = "review.created";

    /**
     * Routing key dell'evento pubblicato da catalog-service alla creazione
     * di un listing (piano 02-01, {@code ListingPublishService}). Primo
     * consumer: {@code ListingIndexer} nello stesso servizio.
     */
    public static final String LISTING_CREATED = "listing.created";

    /**
     * Routing key dell'evento pubblicato da catalog-service alla modifica
     * di un listing (piano 02-01, dichiarata ora anche se il primo
     * publisher reale e il piano 02-03 — {@code edit_listing}). Primo
     * consumer: {@code ListingIndexer} nello stesso servizio.
     */
    public static final String LISTING_UPDATED = "listing.updated";

    /**
     * Routing key dell'evento pubblicato da catalog-service quando un
     * listing transita a {@code REMOVED} (piano 02-01, dichiarata ora anche
     * se il primo publisher reale e un piano successivo — ritiro venditore
     * o rimozione admin, D-12). Nominata per la transizione di stato che
     * riporta davvero (docs/architecture.md §5 la scrive come una chiave a
     * forma di delete, ma il documento listing non viene mai cancellato da
     * MongoDB — solo spostato a {@code REMOVED}, ch. 12). Primo consumer:
     * {@code ListingIndexer}, che cancella il documento di ricerca (D-14).
     */
    public static final String LISTING_REMOVED = "listing.removed";

    /**
     * Routing key dell'evento pubblicato da catalog-service quando un
     * utente aggiunge un listing ai preferiti (piano 02-01, dichiarata ora
     * anche se il primo publisher reale e SOCL-03 in un piano successivo).
     */
    public static final String LISTING_FAVOURITED = "listing.favourited";

    /**
     * Routing key dell'evento pubblicato da catalog-service quando un
     * utente rimuove un listing dai preferiti (piano 02-01, dichiarata ora
     * anche se il primo publisher reale e SOCL-04 in un piano successivo).
     */
    public static final String LISTING_UNFAVOURITED = "listing.unfavourited";

    /**
     * Routing key dell'evento pubblicato da catalog-service al rilascio di
     * una nuova versione software (piano 02-01, dichiarata ora anche se il
     * primo publisher reale e CAT-06 in un piano successivo).
     */
    public static final String LISTING_VERSION_RELEASED = "listing.version.released";

    /**
     * Routing key dell'evento consumato da catalog-service quando un ordine
     * viene annullato (piano 02-01, dichiarata ora anche se order-service —
     * il futuro publisher, Fase 3 — non esiste ancora). Riporta ad
     * {@code ACTIVE} un listing hardware prenotato via {@code PATCH
     * /internal/listings/{id}/reserve}.
     */
    public static final String ORDER_CANCELLED = "order.cancelled";

    /**
     * Routing key dell'evento consumato da catalog-service quando un ordine
     * viene rimborsato (piano 02-01, dichiarata ora anche se order-service —
     * il futuro publisher, Fase 3 — non esiste ancora). Il catalogo rilascia
     * la prenotazione solo se il payload porta un {@code reservationId}, che
     * order-service valorizza unicamente per un rimborso da {@code PAID}
     * (D-21): un rimborso successivo alla spedizione non riapre il listing.
     */
    public static final String ORDER_REFUNDED = "order.refunded";

    /**
     * Routing key dell'evento pubblicato da order-service alla creazione di
     * un'offerta (D-18, piano 03.1-01). Primo consumer reale:
     * messaging-service (Fase 5), che disegna la card dell'offerta nella
     * conversazione compratore-venditore (D-17).
     */
    public static final String OFFER_CREATED = "offer.created";

    /**
     * Routing key dell'evento pubblicato da order-service all'accettazione
     * di un'offerta (D-18, piano 03.1-01). Stesso consumer di
     * {@link #OFFER_CREATED}.
     */
    public static final String OFFER_ACCEPTED = "offer.accepted";

    /**
     * Routing key dell'evento pubblicato da order-service al rifiuto di
     * un'offerta (D-18, piano 03.1-01). Stesso consumer di
     * {@link #OFFER_CREATED}.
     */
    public static final String OFFER_REJECTED = "offer.rejected";

    /**
     * Routing key dell'evento pubblicato da order-service al ritiro di
     * un'offerta da parte del compratore (D-18, piano 03.1-01). Stesso
     * consumer di {@link #OFFER_CREATED}.
     */
    public static final String OFFER_WITHDRAWN = "offer.withdrawn";

    /**
     * Routing key dell'evento pubblicato da order-service alla scadenza di
     * un'offerta (D-18, piano 03.1-01). Stesso consumer di
     * {@link #OFFER_CREATED}.
     */
    public static final String OFFER_EXPIRED = "offer.expired";

    /**
     * Routing key interna con cui order-service chiede a catalog-service di
     * rilasciare l'hold di un'offerta che nessun Order possiede piu (D-07,
     * piano 03.1-01). Consumer: la coda {@code catalog.order-lifecycle} di
     * catalog-service, che rilascia l'hold per reservation id esattamente
     * come fa gia per {@link #ORDER_CANCELLED}. La chiave e composta da tre
     * parole separate da punto (non due, come le transizioni D-18 sopra),
     * cosi un binding a forma di chat sul pattern a parola singola
     * {@code offer.*} non la riceve mai: nessun id di prenotazione interno
     * deve raggiungere i consumer della conversazione in Fase 5.
     */
    public static final String OFFER_HOLD_RELEASED = "offer.hold.released";

    /**
     * Routing key dell'evento pubblicato da order-service quando un ordine
     * passa a {@code PAID} (D-38, Fase 4). Primo consumer reale:
     * messaging-service (Fase 5), per le notifiche.
     */
    public static final String ORDER_PAID = "order.paid";

    /**
     * Routing key dell'evento pubblicato da order-service quando il venditore
     * segna un ordine come spedito (D-38, Fase 4). Primo consumer reale:
     * notifiche di messaging-service (Fase 5).
     */
    public static final String ORDER_SHIPPED = "order.shipped";

    /**
     * Routing key dell'evento pubblicato da order-service quando un ordine
     * risulta consegnato (D-38, Fase 4). Primo consumer: la proiezione delle
     * vendite completate di user-service (piano 04-05); in Fase 5 anche il
     * prompt di recensione.
     */
    public static final String ORDER_DELIVERED = "order.delivered";

    /**
     * Routing key dell'evento pubblicato da order-service all'apertura di un
     * problema su un ordine (D-38, Fase 4). Primo consumer: notifiche di
     * messaging-service (Fase 5). La chiave e composta da tre parole separate
     * da punto, cosi un binding sul pattern a parola singola {@code order.*}
     * non la riceve mai, come per {@link #OFFER_HOLD_RELEASED}.
     */
    public static final String ORDER_PROBLEM_OPENED = "order.problem.opened";

    /**
     * Routing key dell'evento pubblicato da order-service alla risoluzione di
     * un problema su un ordine (D-38, Fase 4). Stesso consumer e stessa forma
     * a tre parole di {@link #ORDER_PROBLEM_OPENED}: un binding
     * {@code order.*} non la riceve.
     */
    public static final String ORDER_PROBLEM_RESOLVED = "order.problem.resolved";

    /**
     * Routing key dell'evento pubblicato da order-service quando emette una
     * licenza (D-38, Fase 4). Primo consumer: notifiche di messaging-service
     * (Fase 5). L'ortografia britannica {@code licence} e deliberata (D-39).
     * Il payload non porta mai la chiave di licenza.
     */
    public static final String LICENCE_ISSUED = "licence.issued";

    /**
     * Routing key dell'evento pubblicato da order-service quando revoca una
     * licenza (D-38, Fase 4). Primo consumer: notifiche di messaging-service
     * (Fase 5). L'ortografia britannica {@code licence} e deliberata (D-39).
     * Il payload non porta mai la chiave di licenza.
     */
    public static final String LICENCE_REVOKED = "licence.revoked";

    private RebyteExchanges() {
    }
}
