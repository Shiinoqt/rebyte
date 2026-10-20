package it.rebyte.catalog.domain;

/**
 * Condizione di un {@link HardwareListing} — campo tipato fisso, mai un
 * attribute definition (D-04, 02-CONTEXT.md): resta cosi perche
 * {@code condition} e sia un campo di prima classe richiesto da CAT-01 sia
 * l'ovvio attributo ereditato sulla radice Hardware, e non deve poter
 * disaccordare tra le due rappresentazioni.
 */
public enum Condition {
    LIKE_NEW,
    VERY_GOOD,
    GOOD,
    ACCEPTABLE
}
