# rebyte — Progettazione (analisi e ristrutturazione per Java)

**Progetto**: rebyte — marketplace di hardware di seconda mano e software indipendente **Documento**: Specifica dei requisiti, raffinamento, specifica delle classi, vincoli esterni, use case, diagramma delle classi (concettuale e ristrutturato) **Version**: 1.0

---

## Indice

1. Introduzione
2. Specifica dei requisiti
3. Glossario
4. Raffinamento dei requisiti
5. Specifica dei tipi di dato
6. Diagramma delle classi concettuale (non ristrutturato)
7. Specifica delle classi
8. Specifica dei vincoli esterni
9. Specifica degli use case
10. Ristrutturazione dello schema
11. Diagramma delle classi ristrutturato
12. Scheletro Java
13. Appendice — mappatura verso l'architettura a microservizi

---

## 1. Introduzione

Si vuole progettare e realizzare **rebyte**, un sistema informatico per la compravendita on-line di prodotti tecnologici di seconda mano e di software realizzato da sviluppatori indipendenti.

Il sistema deve permettere agli utenti registrati di pubblicare annunci per la vendita di singoli prodotti, di acquistarli con la formula "compralo subito", di comunicare fra loro tramite messaggi ancorati all'annuncio, di seguire i venditori di interesse, di salvare annunci fra i preferiti e di ricevere notifiche relative alla propria attività. Al termine di una compravendita l'acquirente può recensire il venditore; dalle recensioni ricevute il sistema calcola la _valutazione media_ di ogni venditore, mostrata sul suo profilo insieme al numero di vendite concluse.

---

## 2. Specifica dei requisiti

Il sistema deve permettere agli utenti registrati (di cui interessano lo _username_ scelto, l'indirizzo di posta elettronica, la password, l'istante di registrazione e, facoltativamente, la località, una biografia testuale e un'immagine di profilo) di pubblicare annunci per la vendita di _singoli_ prodotti. Tali annunci sono chiamati **annunci** (o _listing_). Di ogni annuncio vanno specificati un titolo, una descrizione, il prezzo di vendita, la categoria alla quale appartiene, i metodi di pagamento accettati (carta di credito, PayPal o bonifico) e fino a cinque fotografie, di cui la prima funge da immagine di copertina.

Gli annunci sono di **due tipologie distinte**. Gli _annunci hardware_ riguardano un bene fisico usato, esistente in un unico esemplare: per essi il venditore deve dichiarare la condizione del bene, nella gamma di valori _come nuovo, ottimo, buono, accettabile_, la località dalla quale il bene viene spedito, il costo della spedizione. Gli _annunci software_ riguardano invece un'applicazione realizzata da uno sviluppatore indipendente, a consegna istantanea: per essi il venditore deve dichiarare le piattaforme supportate e almeno una versione. Di ogni versione interessano il numero di versione, l'istante di rilascio, le note di rilascio e il riferimento al file distribuito, che è ciò che l'acquirente scarica.

La differenza fondamentale fra le due tipologie è che un annuncio hardware descrive un pezzo unico e può quindi essere venduto **una sola volta**, mentre un annuncio software può essere venduto **un numero illimitato di volte**: ogni acquisto di software genera una licenza, identificata da una chiave univoca, che dà diritto anche a tutte le versioni rilasciate successivamente all'acquisto.

Tutti gli utenti registrati possono pubblicare annunci hardware, acquistare, recensire e usare le funzionalità sociali del sistema. Per pubblicare annunci software è invece necessario aver attivato il **profilo sviluppatore**, di cui interessa l'istante di attivazione: un utente ne possiede al più uno e può attivarlo in qualunque momento dopo la registrazione, senza che ciò comporti la creazione di un nuovo account: annunci, ordini, recensioni e contatti già accumulati restano quelli. Essere sviluppatore non è quindi un'alternativa all'essere un venditore comune, ma un ruolo aggiuntivo: lo stesso utente può mettere in vendita un portatile usato e, accanto, un'applicazione di cui è l'autore.

Un annuncio viene venduto al primo utente che procede con l'acquisto. Di ogni acquisto (**ordine**) interessano l'istante in cui è stato effettuato, il prezzo effettivamente pagato, il metodo di pagamento scelto, l'indirizzo di spedizione (solo per gli ordini di hardware, dato che il software non viene spedito) e lo stato di avanzamento, nella gamma di valori _in attesa di pagamento, pagato, spedito, consegnato, annullato, rimborsato_. Un utente non può acquistare un annuncio che ha pubblicato lui stesso. Quando un annuncio hardware viene acquistato, esso non è più disponibile per altri acquirenti e sparisce dal catalogo delle ricerche.

Il venditore può modificare in qualunque momento il prezzo di un proprio annuncio finché questo è disponibile. Il sistema deve mantenere lo **storico delle variazioni di prezzo** (istante, prezzo precedente, prezzo nuovo), sia per poter notificare i cali di prezzo agli utenti interessati, sia per poter mostrare il prezzo di listino precedente accanto a quello corrente.

Per semplificare la ricerca degli annunci, il sistema deve permettere di organizzare le categorie in modo **gerarchico** (ad albero, ovvero in categorie principali, di secondo livello, ecc.). Le categorie principali sono due e sono fissate: _Hardware_ e _Software_. Da una categoria deve essere possibile ricavare l'insieme delle sue sottocategorie, se esistono. Un annuncio deve essere classificato in una categoria che non abbia sottocategorie (categoria foglia), coerente con la propria tipologia. Il sistema deve consentire la ricerca degli annunci disponibili per testo libero, per categoria, per tipologia (hardware o software) e per fascia di prezzo.

Ogni utente può salvare fra i propri **preferiti** un numero qualsiasi di annunci pubblicati da altri, e può **seguire** altri utenti per essere avvisato della loro attività. Di entrambe le relazioni interessa l'istante in cui sono state stabilite.

Il sistema deve consentire a un utente interessato di contattare il venditore direttamente dall'annuncio. Ogni **conversazione** è ancorata a un annuncio e coinvolge esattamente due utenti, l'interessato e il venditore dell'annuncio; fra una coppia (annuncio, interessato) esiste al più una conversazione, che viene creata al primo messaggio. Di ogni **messaggio** interessano il testo, l'istante di invio, il mittente e l'informazione sul fatto che sia stato letto o meno dal destinatario.

Il sistema deve inoltre inviare agli utenti delle **notifiche**, di cui interessano l'istante e l'informazione sul fatto che siano state lette. Le notifiche sono di otto tipologie distinte, ciascuna delle quali rimanda a un elemento del sistema: un nuovo follow ricevuto da un altro utente (rimanda al profilo di chi segue), un nuovo messaggio ricevuto (rimanda alla conversazione), l'aggiunta di un proprio annuncio ai preferiti di un altro utente (rimanda all'annuncio), il calo di prezzo di un annuncio fra i propri preferiti (rimanda all'annuncio), il rilascio di una nuova versione di un software di cui si possiede la licenza o il cui autore si segue (rimanda alla versione), la ricezione di una nuova recensione (rimanda alla recensione), la ricezione di un'offerta e la sua accettazione (rimandano all'offerta).

Per favorire l'esclusione dal sistema dei venditori scorretti, il sistema deve permettere agli utenti che hanno **completato** un acquisto di esprimere un giudizio sulla qualità della transazione e sul loro grado generale di soddisfazione. Tali giudizi sono denominati **recensioni** e consistono in un voto numerico da 0 (non soddisfatto) a 5 (molto soddisfatto), al quale può aggiungersi un commento testuale. Ogni ordine può dare luogo ad al più una recensione, e solo l'acquirente di quell'ordine può scriverla.

Per ogni utente il sistema deve permettere di calcolare, a partire dalle recensioni ricevute per i prodotti che ha messo in vendita, la **valutazione media** (media aritmetica dei voti ricevuti, reale compreso fra 0 e 5) e il **numero di vendite concluse**. Entrambi i valori, insieme al numero complessivo di recensioni, sono mostrati sul profilo pubblico del venditore.

Il sistema deve infine prevedere la figura dell'**amministratore**, che non compra né vende, e al quale è consentito creare nuove categorie e rimuovere annunci offensivi o non conformi, registrando l'istante e la motivazione della rimozione.

---

## 3. Glossario

I nomi di classi, attributi, operazioni, associazioni e vincoli sono in inglese; la prosa del documento resta in italiano. La tabella mette in corrispondenza i due registri.

|Termine|Significato|
|---|---|
|**User**|Persona registrata al sistema, che può comprare, vendere, seguire e recensire.|
|**DeveloperProfile**|Ruolo aggiuntivo, attivabile da qualunque utente, che abilita la pubblicazione di annunci software.|
|**Listing**|Inserzione relativa a un singolo prodotto in vendita (nella prosa: _annuncio_).|
|**HardwareListing**|Listing relativo a un bene fisico usato, pezzo unico.|
|**SoftwareListing**|Listing relativo a un'applicazione a consegna istantanea, vendibile più volte.|
|**Version**|Rilascio identificato di un software (numero, istante, note).|
|**License**|Diritto d'uso generato da un ordine di software, identificato da una chiave.|
|**Order**|Acquisto "compralo subito" di un annuncio da parte di un utente.|
|**Review**|Voto (0–5) e commento lasciati dall'acquirente al termine di un ordine.|
|**averageRating**|Media aritmetica, in [0,5], dei voti delle recensioni ricevute da un venditore.|
|**Favourite**|Salvataggio di un annuncio nella lista personale di un utente.|
|**Follow**|Relazione per cui un utente segue l'attività di un altro utente.|
|**Conversation**|Thread di messaggi fra un interessato e il venditore, ancorato a un annuncio.|
|**Notification**|Avviso ricevuto da un utente, con rimando all'elemento che l'ha generato.|
|**Category**|Nodo dell'albero di classificazione degli annunci.|
|**Admin**|Operatore del sistema; crea categorie e modera annunci.|

---

## 4. Raffinamento dei requisiti

1. **Degli utenti interessa:** 1.1. Username (univoco) 1.2. Email (univoca) 1.3. Password 1.4. Istante di registrazione (DateTime) 1.5. Località (opzionale) 1.6. Biografia (opzionale) 1.7. Immagine di profilo (URL, opzionale) 1.8. Gli annunci da loro pubblicati (v. req. 2) 1.9. Gli ordini da loro effettuati come acquirenti (v. req. 6) 1.10. Gli annunci salvati fra i preferiti (v. req. 8) 1.11. Gli utenti che seguono e da cui sono seguiti (v. req. 9) 1.12. Le conversazioni a cui partecipano (v. req. 10) 1.13. Le notifiche ricevute (v. req. 11) 1.14. Le recensioni ricevute sui propri annunci (v. req. 12), la valutazione media (calcolabile, reale in 0..5) e il numero di vendite concluse (calcolabile) 1.15. Il profilo sviluppatore, se attivato (al più uno, v. req. 14): senza di esso l'utente può pubblicare solo annunci hardware (v. req. 2.15)
    
2. **Degli annunci interessa:** 2.1. Titolo 2.2. Descrizione 2.3. Prezzo (reale > 0) 2.4. Istante di pubblicazione (DateTime) 2.5. Stato (attivo, venduto, rimosso) 2.6. Le fotografie (da 0 a 5, ordinate; la prima è la copertina) 2.7. I metodi di pagamento accettati (almeno uno fra bonifico, carta di credito, PayPal) 2.8. La categoria di appartenenza (una e una sola, e deve essere una foglia — v. req. 3) 2.9. Il venditore, cioè l'utente che lo ha pubblicato (uno e uno solo, v. req. 1) 2.10. Lo storico delle variazioni di prezzo (v. req. 5) 2.11. Gli utenti che lo hanno salvato fra i preferiti (v. req. 8) 2.12. Le conversazioni ad esso ancorate (v. req. 10) 2.13. Gli ordini che lo riguardano (v. req. 6) 2.14. La disponibilità (calcolabile: attivo e, se hardware, non ancora venduto) 2.15. Se annuncio hardware: 2.15.1. Condition (come nuovo, ottimo, buono, accettabile) 2.15.2. Località di spedizione 2.15.3. Costo di spedizione (reale ≥ 0) 2.15.4. Può essere oggetto di al più un ordine non annullato (pezzo unico) 2.16. Se annuncio software: 2.16.1. Le piattaforme supportate (almeno una) 2.16.2. Le versioni rilasciate (almeno una, v. req. 4) 2.16.3. La versione corrente (calcolabile: quella con istante di rilascio massimo) 2.16.4. Può essere oggetto di un numero illimitato di ordini (consegna istantanea)
    
3. **Delle categorie interessa:** 3.1. Nome 3.2. La categoria padre (se esiste) 3.3. Le sottocategorie dirette (se esistono) 3.4. L'insieme di tutte le sottocategorie, anche indirette, data una categoria (calcolabile) 3.5. La categoria radice della gerarchia a cui appartiene (calcolabile): _Hardware_ oppure _Software_
    
4. **Delle versioni di un software interessa:** 4.1. Numero di versione (univoco nell'ambito dell'annuncio) 4.2. Istante di rilascio (DateTime) 4.3. Note di rilascio (opzionale) 4.4. Il file distribuito (URL): è ciò che l'acquirente scarica in forza della propria licenza
    
5. **Delle variazioni di prezzo interessa:** 5.1. Istante (DateTime) 5.2. Prezzo precedente (reale > 0) 5.3. Prezzo nuovo (reale > 0) 5.4. Se si tratta di un calo di prezzo (calcolabile: prezzo nuovo < prezzo precedente)
    
6. **Degli ordini interessa:** 6.1. Istante (DateTime) 6.2. Prezzo pagato (reale > 0) 6.3. Metodo di pagamento scelto 6.4. Stato (in attesa di pagamento, pagato, spedito, consegnato, annullato, rimborsato) 6.4bis. L'indirizzo di spedizione, presente se e solo se l'annuncio acquistato è hardware 6.5. L'acquirente (uno e uno solo, v. req. 1) 6.6. L'annuncio acquistato (uno e uno solo, v. req. 2) 6.7. La licenza generata, se l'annuncio è software (v. req. 7) 6.8. La recensione lasciata dall'acquirente, se esiste (v. req. 12) 6.9. Se è recensibile (calcolabile: stato = consegnato e recensione non ancora presente)
    
7. **Delle licenze interessa:** 7.1. Chiave (univoca) 7.2. Istante di rilascio (DateTime) 7.3. Le versioni incluse (calcolabile: tutte le versioni dell'annuncio software acquistato)
    
8. **Dei preferiti interessa:** 8.1. L'utente che ha salvato l'annuncio (v. req. 1) 8.2. L'annuncio salvato (v. req. 2) 8.3. L'istante del salvataggio (DateTime)
    
9. **Dei follow interessa:** 9.1. L'utente che segue (v. req. 1) 9.2. L'utente seguito (v. req. 1) 9.3. L'istante (DateTime)
    
10. **Delle conversazioni interessa:** 10.1. L'annuncio a cui sono ancorate (uno e uno solo, v. req. 2) 10.2. L'utente interessato che le ha avviate (v. req. 1) 10.3. Il venditore (calcolabile: il venditore dell'annuncio) 10.4. I messaggi scambiati (almeno uno, ordinati per istante) 10.5. L'ultimo messaggio (calcolabile) e il numero di messaggi non letti da un dato utente (calcolabile) 10.6. Dei messaggi interessa: 10.6.1. Testo 10.6.2. Istante (DateTime) 10.6.3. Il mittente (v. req. 1) 10.6.4. Se è stato letto dal destinatario
    
11. **Delle notifiche interessa:** 11.1. L'istante (DateTime) 11.2. Se è stata letta 11.3. Il destinatario (uno e uno solo, v. req. 1) 11.4. Se è una notifica di messaggio: il messaggio ricevuto (v. req. 10.6) 11.5. Se è una notifica di preferito: il preferito che l'ha generata (v. req. 8) 11.6. Se è una notifica di calo di prezzo: la variazione di prezzo (v. req. 5) 11.7. Se è una notifica di rilascio: la versione rilasciata (v. req. 4) 11.8. Se è una notifica di recensione: la recensione ricevuta (v. req. 12) 11.9. Se è una notifica di follow: il follow che l'ha generata (v. req. 9)
    
12. **Delle recensioni interessa:** 12.1. Voto (intero da 0 a 5) 12.2. Commento (opzionale) 12.3. Istante (DateTime) 12.4. L'ordine a cui si riferiscono (uno e uno solo, v. req. 6) 12.5. L'autore (calcolabile: l'acquirente dell'ordine) 12.6. Il destinatario (calcolabile: il venditore dell'annuncio dell'ordine)
    
13. **Degli amministratori interessa:** 13.1. Username (univoco) 13.2. Password 13.3. Le categorie create (v. req. 3) 13.4. Le moderazioni effettuate, di cui interessano l'istante, la motivazione e l'annuncio rimosso
    
14. **Del profilo sviluppatore interessa:** 14.1. L'utente a cui appartiene (uno e uno solo, v. req. 1) 14.2. L'istante di attivazione (DateTime) 14.3. Gli annunci software pubblicati dall'utente (calcolabile, v. req. 2.16)
    

---

**999. Il sistema deve permettere le seguenti funzionalità:**

- 999.1. Agli utenti non registrati, di registrarsi, e agli utenti registrati di autenticarsi, di attivare il proprio profilo sviluppatore e di modificare il proprio profilo e le proprie credenziali.
- 999.2. Agli utenti, di pubblicare annunci hardware; ai soli utenti con profilo sviluppatore, di pubblicare anche annunci software e di rilasciarne nuove versioni.
- 999.3. Ai venditori, di modificare e di rimuovere i propri annunci, e di modificarne il prezzo mantenendone lo storico.
- 999.4. Agli utenti, di cercare gli annunci disponibili per testo, categoria, tipologia e fascia di prezzo.
- 999.5. Agli utenti, di acquistare un annuncio disponibile pubblicato da altri con la formula "compralo subito", e al venditore di aggiornare lo stato dell'ordine.
- 999.6. Agli utenti che hanno ricevuto un ordine in stato "consegnato", di lasciare una recensione al venditore.
- 999.7. Agli utenti, di salvare annunci fra i preferiti e di seguire altri utenti.
- 999.8. Agli utenti, di avviare una conversazione a partire da un annuncio e di scambiare messaggi.
- 999.9. Agli utenti, di consultare le proprie notifiche e di segnarle come lette.
- 999.10. Agli utenti, di consultare il profilo pubblico di un altro utente, con la sua valutazione media, le recensioni ricevute e il numero di vendite concluse.
- 999.11. Agli amministratori, di creare categorie.
- 999.12. Agli amministratori, di rimuovere annunci offensivi.

---

## 5. Specifica dei tipi di dato

Il modello concettuale è espresso in UML puro: gli attributi usano i **tipi primitivi UML** (`String`, `Integer`, `Real`, `Boolean`; `UnlimitedNatural` non è utilizzato) più i tipi di dato e le enumerazioni dichiarati qui sotto. I domini ristretti (prezzi positivi, voti da 0 a 5) **non sono tipi**: sono vincoli sull'attributo, scritti fra parentesi graffe accanto ad esso. Le collezioni non sono tipi: sono molteplicità.

### 5.1 «dataType»

```
DateTime      istante temporale (data e ora)
URL           String conforme allo standard
Email         String conforme allo standard RFC 5322
VersionNumber String nel formato "maggiore.minore.patch" (semantic versioning)
Address       recipient: String, street: String, city: String,
              postalCode: String, country: String
```

`Address` è un tipo di dato e non una classe perché è privo di identità propria: due ordini spediti allo stesso indirizzo portano due valori uguali, non due riferimenti allo stesso oggetto, e nessuna associazione del modello punta a un indirizzo. È inoltre uno _snapshot_: l'indirizzo registrato sull'ordine è quello valido al momento dell'acquisto e non deve cambiare se l'utente in seguito trasloca.

### 5.2 «enumeration»

```
Condition:     {likeNew, veryGood, good, acceptable}

ListingStatus: {active, sold, removed}

OrderStatus:   {awaitingPayment, paid, shipped, delivered, cancelled, refunded}

OfferStatus:   {pending, accepted, rejected, expired, withdrawn}

PaymentMethod: {bankTransfer, creditCard, payPal}

Platform:      {windows, macOS, linux, web, android, iOS}

ListingType:   {hardware, software}
```

`ListingType` è il **discriminante della generalizzazione** `Listing {HardwareListing, SoftwareListing}`: non aggiunge informazione al modello, perché la tipologia di un annuncio è già determinata dalla classe di cui è istanza. Viene dichiarata perché serve come _valore_, non come tipo, in due punti: è il parametro di filtro dello use case `search` (capitolo 9.4, corrispondente al selettore All / Hardware / Software del prototipo) e, dopo la ristrutturazione, il valore restituito dall'operazione `type()` che rende interrogabile la sottoclasse senza ricorrere a `instanceof` (capitolo 12).

Trattandosi di informazione duplicata, va tenuta allineata: `a.type() = hardware` se e solo se `a` è istanza di `HardwareListing`, e analogamente per il software. È una ridondanza controllata, garantita per costruzione dal fatto che `type()` è implementata come costante in ciascuna sottoclasse e non come attributo modificabile.

### 5.3 Vincoli di dominio sugli attributi

```
Listing.price          Real    {> 0}
HardwareListing.shippingCost   Real    {>= 0}
PriceChange.oldPrice   Real    {> 0}
PriceChange.newPrice   Real    {> 0}
Order.pricePaid        Real    {> 0}
Offer.amount           Real    {> 0}
Review.rating          Integer {0..5}
```

---

## 6. Diagramma delle classi concettuale (non ristrutturato)

### 6.1 Classi e attributi

Le molteplicità delle **associazioni** sono scritte per esteso nella forma `min..max` (`1..1`, `0..1`, `1..*`, `0..*`) nella tabella 6.3 e sul diagramma. Sugli **attributi** la molteplicità è indicata solo quando diversa da quella implicita `1..1`: `[0..1]` per gli attributi opzionali, `[0..5]` e `[1..*]` per quelli multivalore. Gli attributi derivati sono marcati con `/`; i vincoli di dominio sono fra parentesi graffe.

```
User
	username: String {unique}
	email: Email {unique}
	password: String
	registration: DateTime
	location: String [0..1]
	bio: String [0..1]
	avatar: URL [0..1]
	/averageRating: Real {0..5}   (derivato)
	/completedSales: Integer {>= 0}   (derivato)

	/isDeveloper: Boolean   (derivato: esiste il link role)

DeveloperProfile
	activation: DateTime

Admin
	username: String {unique}
	password: String

Listing {astratta}
	title: String
	description: String
	price: Real {> 0}
	publication: DateTime
	status: ListingStatus
	photos: URL [0..5] {ordered}
	paymentMethods: PaymentMethod [1..*]
	/available: Boolean   (derivato)
	/favouriteCount: Integer {>= 0}   (derivato)

	HardwareListing  (sottoclasse di Listing)
		condition: Condition
		shippingLocation: String
		shippingCost: Real {>= 0}

	SoftwareListing  (sottoclasse di Listing)
		platforms: Platform [1..*]
		/currentVersion: Version   (derivato)

Category
	name: String
	/root: Category   (derivato)

Version
	number: VersionNumber
	release: DateTime
	notes: String [0..1]
	artifact: URL

PriceChange
	instant: DateTime
	oldPrice: Real {> 0}
	newPrice: Real {> 0}
	/drop: Boolean   (derivato)

Offer
	amount: Real {> 0}
	instant: DateTime
	status: OfferStatus
	expiresAt: DateTime [0..1]

Order
	instant: DateTime
	pricePaid: Real {> 0}
	paymentMethod: PaymentMethod
	status: OrderStatus
	shippingAddress: Address [0..1]
	/reviewable: Boolean   (derivato)

License
	key: String {unique}
	release: DateTime
	/includedVersions: Version [0..*]   (derivato)

Review
	rating: Integer {0..5}
	comment: String [0..1]
	instant: DateTime
	/author: User   (derivato)
	/recipient: User   (derivato)

Conversation
	creation: DateTime
	/seller: User   (derivato)
	/lastMessage: Message   (derivato)

Message
	text: String
	instant: DateTime
	read: Boolean

Notification {astratta}
	instant: DateTime
	read: Boolean

	MessageNotification, FavouriteNotification, PriceDropNotification,
	ReleaseNotification, ReviewNotification, FollowNotification,
	OfferReceivedNotification, OfferAcceptedNotification
	                                     (sottoclassi di Notification)
```

### 6.2 Gerarchie

Restano due sole gerarchie. La distinzione fra utente comune e sviluppatore **non** è una generalizzazione, ma un ruolo reificato (`DeveloperProfile`): un utente può vendere hardware e software insieme, quindi le due qualifiche si sovrappongono e una generalizzazione disgiunta sarebbe falsa.

|Gerarchia|Sottoclassi|Vincoli|
|---|---|---|
|`Listing`|`HardwareListing`, `SoftwareListing`|disgiunta, completa (classe padre astratta)|
|`Notification`|`MessageNotification`, `FavouriteNotification`, `PriceDropNotification`, `ReleaseNotification`, `ReviewNotification`, `FollowNotification`, `OfferReceivedNotification`, `OfferAcceptedNotification`|disgiunta, completa (classe padre astratta)|

### 6.3 Associazioni

|Nome|Classe A|Molt. A|Classe B|Molt. B|Note|
|---|---|---|---|---|---|
|`role`|User|1..1|DeveloperProfile|0..1|ruolo aggiuntivo, attivabile dopo la registrazione|
|`publishes`|User (venditore)|1..1|Listing|0..*|v. vincolo `[V.SoftwareListing.developersOnly]`|
|`listing_cat`|Listing|0..*|Category|1..1|la categoria deve essere una foglia|
|`subcategory`|Category (padre)|0..1|Category (figlia)|0..*|gerarchia ad albero, aciclica|
|`priceChange`|Listing|1..1|PriceChange|0..*|storico prezzi|
|`version`|SoftwareListing|1..1|Version|1..*||
|`favourites`|User|0..*|Listing|0..*|classe di associazione `Favourite {instant}`|
|`follows`|User (seguace)|0..*|User (seguito)|0..*|classe di associazione `Follow {instant}`|
|`makes`|User (offerente)|1..1|Offer|0..*||
|`receives`|Listing|1..1|Offer|0..*|solo hardware|
|`offer_order`|Offer|0..1|Order|0..1|un'offerta accettata può generare un solo ordine|
|`buys`|User|1..1|Order|0..*||
|`order_listing`|Listing|1..1|Order|0..*|per l'hardware al più 1 ordine non annullato|
|`order_license`|Order|1..1|License|0..1|presente solo per ordini di software|
|`order_review`|Order|1..1|Review|0..1||
|`listing_conv`|Listing|1..1|Conversation|0..*||
|`user_conv`|User|1..1|Conversation|0..*|al più una conversazione per (annuncio, interessato)|
|`contains`|Conversation|1..1|Message|1..*|{ordinato per istante}|
|`sender`|User|1..1|Message|0..*||
|`recipient`|User|1..1|Notification|0..*||
|`mes_not`|MessageNotification|0..*|Message|1..1|bersaglio del deep link|
|`fav_not`|FavouriteNotification|0..*|Favourite|1..1|il bersaglio è la classe di associazione, unico oggetto che porta la tripla (utente, annuncio, istante); v. `[V.Notification.targetIntegrity]`|
|`pc_not`|PriceDropNotification|0..*|PriceChange|1..1|bersaglio del deep link|
|`rel_not`|ReleaseNotification|0..*|Version|1..1|bersaglio del deep link|
|`rev_not`|ReviewNotification|0..1|Review|1..1|bersaglio del deep link|
|`follow_not`|FollowNotification|0..1|Follow|1..1|bersaglio del deep link; v. `[V.Notification.targetIntegrity]`|
|`offer_received_not`|OfferReceivedNotification|0..1|Offer|1..1|destinatario: venditore dell'annuncio|
|`offer_accepted_not`|OfferAcceptedNotification|0..1|Offer|1..1|destinatario: offerente|
|`creates`|Admin|1..1|Category|0..*||
|`moderation`|Admin|0..*|Listing|0..*|classe di associazione `Moderation {instant, reason}`|

> **Nota di progetto — perché un ruolo e non una sottoclasse.** Sono state considerate due alternative.
> 
> Una generalizzazione disgiunta e completa `{Privato, Sviluppatore}` è scorretta: lo stesso utente può vendere hardware usato e software di cui è l'autore (è il caso di @alexbuilds nella schermata 09 del prototipo), quindi le due classi non partizionano nulla.
> 
> Una generalizzazione parziale con la sola sottoclasse `Sviluppatore` sarebbe invece corretta e più economica, e renderebbe `[V.SoftwareListing.developersOnly]` un vincolo strutturale anziché un vincolo esterno. È stata scartata per un motivo preciso: **la qualifica di sviluppatore è acquisibile dopo la registrazione**. Un utente si iscrive, vende qualche pezzo di hardware e solo in seguito pubblica la sua prima applicazione. Un oggetto non può però cambiare classe dopo la creazione: con la sottoclasse, quel passaggio richiederebbe di creare una nuova istanza `Sviluppatore` e di trasferirvi tutti i link della vecchia (annunci, ordini, preferiti, follower, conversazioni, notifiche, recensioni ricevute), distruggendo l'identità dell'oggetto. Con il ruolo reificato lo stesso passaggio è la creazione di un singolo link `role`, ed è esattamente il problema per cui il pattern esiste. È il caso classico in cui una qualifica che si acquisisce nel tempo va modellata come ruolo e non come tipo.
> 
> **Nota di progetto — perché una classe e non un attributo.** Con il solo attributo `activation`, `DeveloperProfile` sarebbe equivalente a un attributo opzionale `attivazioneSviluppatore: DateTime [0..1]` su `User`. La classe è preferibile perché il ruolo è un oggetto citabile nei vincoli e nelle precondizioni anziché un campo nullo da controllare, e perché è il punto di aggancio naturale per le informazioni che il ruolo acquisirà in seguito (stato di verifica da parte di un amministratore, dati di pagamento del venditore, termini di licenza predefiniti).

### 6.4 Diagramma (Mermaid)

Il diagramma completo è nel file `class_diagram_concettuale.mermaid` (visualizzabile su [https://mermaid.live](https://mermaid.live/)). Due scostamenti sono imposti dai limiti di Mermaid, non dal modello: gli **attributi derivati** sono scritti come operazioni, perché Mermaid non ha il prefisso `/`; i **vincoli fra parentesi graffe** (`{> 0}`, `{unique}`, `{ordered}`) sono riportati come note a bordo classe, perché le graffe nei membri non sono ammesse dal parser. Le molteplicità delle associazioni sono invece rese fedelmente e per esteso su entrambi i lati (`1..1`, `0..1`, `1..*`, `0..*`); sugli attributi compaiono solo quando diverse da `1..1`. In un diagramma UML disegnato con uno strumento UML vero, entrambe le voci vanno riportate nel riquadro degli attributi nella forma canonica: `/averageRating: Real {0..5}`.

Fanno comunque fede l'elenco 6.1, la tabella dei vincoli di dominio in 5.3 e la tabella delle associazioni in 6.3.

---

## 7. Specifica delle classi

### 7.1 Specifica della classe User

```
isDeveloper(): Boolean
	precondizioni: nessuna.
	postcondizioni:
		- Non ha side effect.
		- result = (esiste ps:DeveloperProfile tale che (this,ps):role)


reviewsReceived(i: DateTime): Review [0..*]
	precondizioni: nessuna.
	postcondizioni:
		- Non ha side effect.
		- result = { r:Review | esiste o:Order con (o,r):order_review, (a,o):order_listing,
		  (this,a):publishes e r.instant <= i }


averageRating(i: DateTime): Real {0..5}
	precondizioni:
		- Sia R = this.reviewsReceived(i). R deve avere almeno un elemento.
	postcondizioni:
		- Non ha side effect.
		- Sia S la somma dei r.rating tali che r in R.
		- result = S / |R|


completedSales(i: DateTime): Integer {>= 0}
	precondizioni: nessuna.
	postcondizioni:
		- Non ha side effect.
		- Sia A l'insieme degli a:Listing tali che (this,a):publishes.
		- Sia O l'insieme degli o:Order tali che esiste a in A con (a,o):order_listing,
		  o.status = delivered e o.instant <= i.
		- result = |O|


availableListings(): Listing [0..*]
	precondizioni: nessuna.
	postcondizioni:
		- Non ha side effect.
		- result = { a:Listing | (this,a):publishes e a.available() }


unreadNotifications(): Integer {>= 0}
	precondizioni: nessuna.
	postcondizioni:
		- Non ha side effect.
		- result = | { n:Notification | (this,n):recipient e non n.read } |


unreadMessages(): Integer {>= 0}
	precondizioni: nessuna.
	postcondizioni:
		- Non ha side effect.
		- Sia C l'insieme delle c:Conversation tali che (this,c):user_conv
		  oppure c.seller() = this.
		- result = la somma di c.unreadFor(this) per c in C.
```

### 7.2 Specifica della classe Listing

```
available(): Boolean
	precondizioni: nessuna.
	postcondizioni:
		- Non ha side effect.
		- result = (this.status = active)


favouriteCount(): Integer {>= 0}
	precondizioni: nessuna.
	postcondizioni:
		- Non ha side effect.
		- result = | { p:Favourite | p.listing = this } |


lastPriceDrop(): PriceChange [0..1]
	precondizioni: nessuna.
	postcondizioni:
		- Non ha side effect.
		- Sia V l'insieme delle v:PriceChange tali che (this,v):priceChange e v.drop().
		- result = la v in V con v.instant massimo, se V non e vuoto; indefinito altrimenti.
```

### 7.3 Specifica della classe SoftwareListing

```
currentVersion(): Version
	precondizioni:
		- Sia V l'insieme delle v:Version tali che (this,v):version. V deve avere
		  almeno un elemento (garantito dalla molteplicita 1..*).
	postcondizioni:
		- Non ha side effect.
		- result = la v in V con v.release massimo.
```

### 7.4 Specifica della classe Category

```
subcategories(): Category [0..*]
	precondizioni: nessuna.
	postcondizioni:
		- Non ha side effect.
		- result = { c:Category | (this,c):subcategory } (sottocategorie dirette)


allSubcategories(): Category [0..*]
	precondizioni: nessuna.
	postcondizioni:
		- Non ha side effect.
		- result e il piu piccolo insieme S tale che:
			- this.subcategories() e contenuto in S;
			- per ogni c in S, c.subcategories() e contenuto in S.
		  (chiusura transitiva della relazione sottocategoria)


root(): Category
	precondizioni: nessuna.
	postcondizioni:
		- Non ha side effect.
		- result = this se this non ha categoria padre;
		  result = parent.root() altrimenti.


leaf(): Boolean
	precondizioni: nessuna.
	postcondizioni:
		- Non ha side effect.
		- result = (this.subcategories() e vuoto)
```

### 7.5 Specifica della classe Order

```
reviewable(): Boolean
	precondizioni: nessuna.
	postcondizioni:
		- Non ha side effect.
		- result = (this.status = delivered) e (non esiste r:Review con (this,r):order_review)


seller(): User
	precondizioni: nessuna.
	postcondizioni:
		- Non ha side effect.
		- Sia a l'annuncio tale che (a,this):order_listing.
		- result = l'utente u tale che (u,a):publishes.
```

### 7.6 Specifica della classe License

```
includedVersions(): Version [0..*]
	precondizioni: nessuna.
	postcondizioni:
		- Non ha side effect.
		- Sia o l'ordine tale che (o,this):order_license e sia a l'annuncio (necessariamente
		  di tipo SoftwareListing) tale che (a,o):order_listing.
		- result = { v:Version | (a,v):version }
		  (la licenza include anche le versioni rilasciate dopo l'acquisto)
```

### 7.7 Specifica della classe Conversation

```
seller(): User
	precondizioni: nessuna.
	postcondizioni:
		- Non ha side effect.
		- Sia a l'annuncio tale che (a,this):listing_conv.
		- result = l'utente u tale che (u,a):publishes.


lastMessage(): Message
	precondizioni:
		- Sia M l'insieme dei m:Message tali che (this,m):contains. M deve avere almeno
		  un elemento (garantito dalla molteplicita 1..*).
	postcondizioni:
		- Non ha side effect.
		- result = il m in M con m.instant massimo.


unreadFor(u: User): Integer {>= 0}
	precondizioni:
		- u deve essere l'interessato di this oppure this.seller().
	postcondizioni:
		- Non ha side effect.
		- result = | { m:Message | (this,m):contains e non m.read e
		            il mittente di m e diverso da u } |
```

### 7.8 Specifica della classe Review

```
author(): User
	precondizioni: nessuna.
	postcondizioni:
		- Non ha side effect.
		- Sia o l'ordine tale che (o,this):order_review.
		- result = l'utente u tale che (u,o):buys.


recipient(): User
	precondizioni: nessuna.
	postcondizioni:
		- Non ha side effect.
		- Sia o l'ordine tale che (o,this):order_review.
		- result = o.seller().
```

### 7.9 Specifica della classe PriceChange

```
drop(): Boolean
	precondizioni: nessuna.
	postcondizioni:
		- Non ha side effect.
		- result = (this.newPrice < this.oldPrice)
```

---

### 7.10 Specifica della classe Offer

Un'offerta nasce in stato `pending` su un annuncio hardware disponibile, con importo positivo
strettamente inferiore al prezzo richiesto al momento dell'invio; non è prevista una soglia
minima ulteriore. Il venditore può accettarla o rifiutarla, senza controfferte; l'offerente
può ritirarla solo finché è `pending`.

L'accettazione porta l'offerta a `accepted`, imposta `expiresAt` a 24 ore da adesso (durata
configurabile), riserva l'annuncio all'offerente e porta tutte le altre offerte `pending`
sullo stesso annuncio a `rejected`. L'annuncio passa a `sold` per la prenotazione e non è
più disponibile nella ricerca. La prenotazione non costituisce un pagamento.

La conversione in ordine crea il link `offer_order` una sola volta e conserva l'importo
accettato. Lo sweep di riconciliazione della fase 3, esteso nella fase 3.1, porta a `expired`
un'offerta il cui termine è trascorso senza pagamento e rilascia la prenotazione riportando
l'annuncio a `active`. Se il checkout è già iniziato, la scadenza è coordinata con la
riconciliazione del pagamento e l'eventuale annullamento dell'ordine: un rilascio della sola
offerta non può annullare una vendita né una prenotazione successiva. Le offerte rifiutate
restano tali; per riprovare occorre inviarne di nuove.

## 8. Specifica dei vincoli esterni

**[V.UserListing.publicationAfterRegistration]** Un utente non può pubblicare annunci prima di essersi registrato.

> Non possono esistere utenti che hanno pubblicato annunci prima della propria registrazione. _(informale)_
> 
> Non esiste u:User e a:Listing tali che (u,a):publishes e u.registration > a.publication. _(formale)_

---

**[V.SoftwareListing.developersOnly]** Gli annunci software possono essere pubblicati solo dagli utenti che hanno attivato il profilo sviluppatore, e non prima di tale attivazione.

> Non esiste u:User e a:SoftwareListing tali che (u,a):publishes e non u.isDeveloper(). _(formale)_
> 
> Per ogni a:SoftwareListing, sia u l'utente tale che (u,a):publishes e sia ps il profilo tale che (u,ps):role: ps deve esistere e deve valere ps.activation <= a.publication. _(formale, versione completa)_

---

**[V.DeveloperProfile.activationAfterRegistration]** Il profilo sviluppatore non può essere attivato prima della registrazione dell'utente, e ogni utente ne ha al più uno.

> Per ogni u:User e ps:DeveloperProfile con (u,ps):role deve essere u.registration <= ps.activation. L'unicità è garantita dalla molteplicità 0..1 dell'associazione `role`. _(formale)_

---

**[V.Order.noSelfPurchase]** Un utente non può acquistare un annuncio pubblicato da sé stesso.

> Non esiste u:User, a:Listing e o:Order tali che (u,a):publishes, (a,o):order_listing e (u,o):buys. _(formale)_

---

**[V.Order.purchaseAfterPublication]** L'istante di un ordine deve essere successivo alla pubblicazione dell'annuncio acquistato.

> Per ogni o:Order e a:Listing coinvolti in un link (a,o):order_listing deve essere a.publication <= o.instant. _(formale)_

---

**[V.HardwareListing.singleItem]** Un annuncio hardware descrive un pezzo unico e può quindi essere oggetto di al più un ordine non annullato; un annuncio hardware è in stato "venduto" se esiste tale ordine oppure una prenotazione per offerta accettata ancora da rilasciare.

> Per ogni a:HardwareListing, sia O = { o:Order | (a,o):order_listing e o.status non in {cancelled, refunded} }: deve essere |O| <= 1. Inoltre a.status = sold se e solo se |O| = 1 oppure esiste una prenotazione per offerta accettata non ancora convertita in ordine né rilasciata. Esiste al più una prenotazione corrente per annuncio; la conversione in ordine la riutilizza senza crearne una seconda. _(formale)_

_(Nota: per gli annunci software non vale alcun vincolo analogo, dato che la consegna è istantanea e la disponibilità illimitata.)_

---

**[V.Order.shippingAddressIffHardware]** Un ordine porta un indirizzo di spedizione se e solo se l'annuncio acquistato è hardware.

> Per ogni o:Order, sia a l'annuncio tale che (a,o):order_listing: o.shippingAddress è presente se e solo se a è istanza di HardwareListing. _(formale)_

_(Nota di progetto: il modello registrava la località **di partenza** del bene, in `HardwareListing.shippingLocation`, e il costo della spedizione, ma non la destinazione — un ordine hardware non era quindi spedibile da nessuna parte. L'indirizzo sta sull'ordine e non sull'utente perché è uno snapshot: deve restare quello dichiarato all'acquisto anche se l'acquirente in seguito trasloca, e lo stesso utente può farsi spedire ordini diversi a indirizzi diversi.)_

---

**[V.Order.availableOnly]** Un ordine può essere creato su un annuncio disponibile oppure dall'offerente titolare della prenotazione per un'offerta accettata e non scaduta.

> Per ogni o:Order e a:Listing con (a,o):order_listing, immediatamente prima della creazione deve valere a.available(), oppure deve esistere f:Offer con (a,f):receives, (u,f):makes e (u,o):buys, f.status = accepted, o.instant < f.expiresAt, prenotazione corrente di a intestata a f e nessun ordine già collegato a f. _(formale)_

---

**[V.Offer.noSelfOffer]** Un utente non può inviare offerte su un proprio annuncio.

> Non esistono u:User, a:Listing e f:Offer tali che (u,a):publishes, (u,f):makes e (a,f):receives. _(formale)_

---

**[V.Offer.singlePendingPerBuyer]** Un utente può avere al più un'offerta pendente per annuncio.

> Per ogni u:User e a:Listing, | { f:Offer | (u,f):makes e (a,f):receives e f.status = pending } | <= 1. _(formale)_

---

**[V.Offer.hardwareOnly]** Le offerte riguardano esclusivamente annunci hardware.

> Per ogni f:Offer e a:Listing con (a,f):receives, a è istanza di HardwareListing. _(formale)_

---

**[V.Review.legalReview]** Una recensione può essere lasciata solo dall'acquirente di un ordine consegnato, e solo dopo la consegna.

> Non può esistere r:Review che non sia collegata a un o:Order tramite (o,r):order_review. Inoltre, per ogni r:Review, sia o l'ordine tale che (o,r):order_review, deve valere:
> 
> - o.status = delivered
> - r.instant >= o.instant
> - r.author() = l'utente u tale che (u,o):buys _(garantito per costruzione dall'attributo derivato)_
> 
> _(formale)_

_(Nota di progetto: ancorando la recensione all'ordine anziché direttamente all'annuncio, il vincolo "solo chi ha comprato può recensire" diventa in gran parte strutturale; resta da vincolare esplicitamente solo lo stato dell'ordine e l'ordinamento temporale.)_

---

**[V.Review.onePerOrder]** Ogni ordine può dare luogo ad al più una recensione.

> Garantito dalla molteplicità 0..1 dell'associazione `review`.

---

**[V.Favourite.noSelfFavourite]** Un utente non può salvare fra i preferiti un annuncio pubblicato da sé stesso.

> Non esiste p:Favourite, u:User e a:Listing tali che p.user = u, p.listing = a e (u,a):publishes. _(formale)_

---

**[V.Favourite.afterPublicationAndUnique]** Un annuncio può essere salvato solo dopo la sua pubblicazione, e una sola volta per utente.

> Per ogni p:Favourite deve essere p.listing.publication <= p.instant. Non esistono p1, p2:Favourite distinti tali che p1.user = p2.user e p1.listing = p2.listing. _(formale)_

---

**[V.Follow.noSelfFollow]** Un utente non può seguire sé stesso, e non può seguire lo stesso utente due volte.

> Non esiste f:Follow tale che f.follower = f.followed. Non esistono f1, f2:Follow distinti tali che f1.follower = f2.follower e f1.followed = f2.followed. _(formale)_

---

**[V.Conversation.distinctParticipants]** L'utente interessato di una conversazione non può coincidere con il venditore dell'annuncio.

> Non esiste c:Conversation tale che l'utente u con (u,c):user_conv soddisfa u = c.seller(). _(formale)_

---

**[V.Conversation.onePerListingAndUser]** Fra un annuncio e un dato utente interessato esiste al più una conversazione.

> Non esistono c1, c2:Conversation distinte tali che l'annuncio di c1 = l'annuncio di c2 e l'interessato di c1 = l'interessato di c2. _(formale)_

---

**[V.Message.senderInConversation]** Il mittente di un messaggio deve essere uno dei due partecipanti della conversazione che lo contiene, e il messaggio non può precedere la creazione della conversazione.

> Per ogni c:Conversation e m:Message con (c,m):contains, sia u il mittente di m: deve essere u = l'interessato di c oppure u = c.seller(), e c.creation <= m.instant. _(formale)_

---

**[V.Category.acyclicHierarchy]** La gerarchia delle categorie è un albero: nessuna categoria può essere sottocategoria, diretta o indiretta, di sé stessa.

> Per ogni c:Category, c non appartiene a c.allSubcategories(). _(formale)_

---

**[V.Category.fixedRoots]** Esistono esattamente due categorie senza padre, di nome "Hardware" e "Software".

> | { c:Category | c non ha categoria padre } | = 2, e i loro nomi sono "Hardware" e "Software". _(formale)_

---

**[V.Category.uniqueNameAmongSiblings]** Due sottocategorie della stessa categoria padre non possono avere lo stesso nome. Nomi uguali sotto padri diversi sono invece leciti: "Accessori" può esistere sia sotto Hardware sia sotto Software.

> Non esistono c1, c2:Category distinte con lo stesso nome e la stessa categoria padre. _(formale)_

---

**[V.Listing.leafAndConsistentCategory]** Un annuncio deve appartenere a una categoria foglia, la cui radice sia coerente con la tipologia dell'annuncio.

> Per ogni a:Listing e c:Category con (a,c):listing_cat deve valere c.leaf() = vero, e inoltre:
> 
> - se a:HardwareListing, c.root().name = "Hardware";
> - se a:SoftwareListing, c.root().name = "Software".
> 
> _(formale)_

---

**[V.Version.uniqueOrderedNumber]** Due versioni dello stesso annuncio software non possono avere lo stesso numero, e la versione con numero maggiore non può essere stata rilasciata prima di una con numero minore.

> Non esistono v1, v2:Version distinte dello stesso a:SoftwareListing tali che v1.number = v2.number. Per ogni v1, v2 dello stesso a: se v1.number < v2.number allora v1.release < v2.release. _(formale)_

---

**[V.Version.releaseAfterPublication]** Nessuna versione può essere rilasciata prima della pubblicazione dell'annuncio a cui appartiene.

> Per ogni a:SoftwareListing e v:Version con (a,v):version deve essere a.publication <= v.release. _(formale)_

---

**[V.License.softwareOrdersOnly]** Una licenza esiste se e solo se l'ordine che la genera riguarda un annuncio software.

> Per ogni o:Order, esiste l:License con (o,l):order_license se e solo se l'annuncio a con (a,o):order_listing è istanza di SoftwareListing. _(formale)_

---

**[V.PriceChange.consistentHistory]** Lo storico dei prezzi di un annuncio deve essere coerente e concatenato.

> Per ogni a:Listing, sia V la sequenza delle v:PriceChange con (a,v):priceChange ordinate per istante crescente:
> 
> - per ogni v in V deve essere a.publication <= v.instant e v.newPrice != v.oldPrice;
> - per ogni coppia consecutiva (v_k, v_k+1) deve essere v_k.newPrice = v_k+1.oldPrice;
> - se V non è vuoto, l'ultimo elemento deve avere newPrice = a.price.
> 
> _(formale)_

---

**[V.PriceDropNotification.interestedRecipient]** Una notifica di calo di prezzo può essere inviata solo a un utente che ha l'annuncio fra i propri preferiti, e solo se la variazione è effettivamente un calo.

> Per ogni n:PriceDropNotification, sia v la variazione con (n,v):pc_not, sia a l'annuncio con (a,v):priceChange e sia u il destinatario di n: deve esistere p:Favourite con p.user = u e p.listing = a, e deve valere v.drop(). _(formale)_

---

**[V.FavouriteNotification.sellerRecipient]** Una notifica di preferito viene inviata al venditore dell'annuncio salvato.

> Per ogni n:FavouriteNotification, sia p il preferito con (n,p):fav_not: il destinatario di n deve essere l'utente u tale che (u, p.listing):publishes. _(formale)_

---

**[V.ReviewNotification.reviewedRecipient]** Una notifica di recensione viene inviata all'utente recensito.

> Per ogni n:ReviewNotification, sia r la recensione con (n,r):rev_not: il destinatario di n deve essere r.recipient(). _(formale)_

---

**[V.Notification.notificationAfterEvent]** Nessuna notifica può precedere l'evento che la genera.

> Per ogni n:Notification, n.instant >= dell'istante dell'oggetto bersaglio (messaggio, preferito, follow, variazione di prezzo, versione o recensione). _(formale)_

---

**[V.Notification.targetIntegrity]** Nessuna notifica può sopravvivere al proprio bersaglio: se l'oggetto bersaglio viene eliminato, le notifiche che lo referenziano vengono eliminate con esso.

> Ogni notifica deve puntare a un oggetto bersaglio esistente. _(informale)_
> 
> Non esiste n:Notification priva del link verso il proprio bersaglio (imposto dalla molteplicità 1..1 del lato bersaglio). In particolare, per ogni p:Favourite eliminato devono essere eliminate tutte le n:FavouriteNotification con (n,p):fav_not, e per ogni f:Follow eliminato tutte le n:FollowNotification con (n,f):follow_not. _(formale)_

_(Nota di progetto: il vincolo morde solo per le notifiche di preferito e di follow, perché sono le uniche il cui bersaglio è un oggetto cancellabile. Message, PriceChange, Version e Review non vengono mai eliminati — un annuncio si rimuove passando in stato `removed`, senza distruggere lo storico — quindi per le altre quattro sottoclassi la molteplicità 1..1 è già garantita. La cascata rimuove anche notifiche già lette: si accetta questa perdita di cronologia perché la notifica descrive un fatto che nel frattempo è stato disfatto, e perché l'alternativa — bersaglio opzionale, con notifiche orfane da interpretare in lettura — complicherebbe sia il modello sia la schermata Notifiche.)_

---

**[V.Moderation.listingRemoved]** Un annuncio moderato è in stato "rimosso" e non può più essere acquistato.

> Per ogni m:Moderation, il suo annuncio a deve avere a.status = removed e non deve esistere o:Order con (a,o):order_listing e o.instant > m.instant. _(formale)_

---

## 9. Specifica degli use case

### 9.1 Attori e casi d'uso

|Attore|Casi d'uso|
|---|---|
|**Visitatore** (non registrato)|registrarsi, cercare e consultare annunci|
|**User**|autenticarsi, gestire il profilo, attivare il profilo sviluppatore, pubblicare/modificare/rimuovere annunci hardware, cercare, acquistare, inviare/ritirare offerte, accettare/rifiutare offerte ricevute, recensire, gestire preferiti e follow, messaggiare, gestire notifiche|
|**User con profilo sviluppatore**|(tutto quanto sopra) + pubblicare annunci software e rilasciare versioni|
|**Admin**|creare categorie, rimuovere annunci offensivi|

I casi d'uso sono raggruppati nei seguenti _strumenti_: **registrazione**, **pubblicazione**, **consultazione**, **acquisto**, **social**, **messaggistica**, **notifiche**, **amministrazione**.

Lo schema del diagramma dei casi d'uso è nel file `use_case_diagram.mermaid`, con gli otto strumenti resi come raggruppamenti.

**Generalizzazione fra attori.** `User` specializza `Visitatore` e `User con profilo sviluppatore` specializza `User`: ogni attore eredita i casi d'uso di quello che generalizza, quindi uno sviluppatore può fare tutto ciò che fa un utente e in più pubblicare software. Vale la pena notare che qui la generalizzazione è lecita anche se nel diagramma delle classi la qualifica di sviluppatore è un ruolo e non una sottoclasse: gli attori _sono_ ruoli, e la stessa persona può presentarsi come attori diversi in momenti diversi. `Admin` non specializza nulla, perché non compra né vende.

**Relazioni fra casi d'uso.**

|Relazione|Significato|
|---|---|
|`buy` «include» `confirm_payment`|il pagamento è parte obbligatoria dell'acquisto, non un caso a sé che l'utente possa scegliere di saltare|
|`start_conversation` «include» `send_message`|avviare una conversazione significa scriverci il primo messaggio: non esistono conversazioni vuote (molteplicità `1..*` su `messages`)|
|`publish_software` «include» `release_version`|pubblicare un'applicazione comporta il rilascio della prima versione|

Non sono state usate relazioni «extend»: nessun caso d'uso di rebyte è un'estensione opzionale di un altro. Le operazioni che sembrano candidate — per esempio la generazione delle notifiche in `change_price` o in `add_favourite` — non sono casi d'uso, perché non sono avviate da un attore: sono effetti descritti nelle postcondizioni.

**Cosa il diagramma non deve contenere.** Le frecce fra attore e caso d'uso non hanno direzione né significato di flusso dati: sono associazioni. Non vanno inseriti casi d'uso di sola lettura per ogni entità (visualizzare l'elenco degli ordini, aprire un annuncio): sono navigazione dell'interfaccia, non funzionalità del sistema. E non va scomposto ogni caso d'uso nelle quattro operazioni CRUD, che è l'errore più comune: `edit_listing` e `remove_listing` compaiono perché sono richieste esplicite del capitolo 999, non perché esista una regola per cui ogni classe deve avere le sue quattro operazioni.

### 9.2 Specifica dello use case Strumenti registrazione

```
register(u: String, e: Email, pw: String): User
	precondizioni:
		- non esiste x:User tale che x.username = u oppure x.email = e
	postcondizioni:
		- crea e restituisce un oggetto n di classe User (in breve, n:User):
			- n.username = u
			- n.email = e
			- n.password = pw
			- n.registration = adesso
		- n non ha profilo sviluppatore: n.isDeveloper() = falso


activate_developer_profile(u: User): DeveloperProfile
	precondizioni:
		- non u.isDeveloper()
	postcondizioni:
		- crea e restituisce ps:DeveloperProfile con ps.activation = adesso
		- crea il link (u,ps):role
		- da questo istante u puo' pubblicare annunci software


authenticate(e: Email, pw: String): User
	precondizioni:
		- esiste u:User tale che u.email = e e u.password = pw
	postcondizioni:
		- Non ha side effect.
		- result = l'unico u:User tale che u.email = e e u.password = pw


update_profile(u: User, newUsername: String, location: String [0..1],
                 bio: String [0..1], img: URL [0..1])
	precondizioni:
		- non esiste x:User, x != u, tale che x.username = newUsername
	postcondizioni:
		- u.username = newUsername, u.location = location, u.bio = bio, u.avatar = img
		- Nessun altro oggetto viene modificato: lo username non e' l'identificatore
		  di User (v. 10.6), quindi tutti i riferimenti verso u restano validi.


update_credentials(u: User, newEmail: Email, newPassword: String)
	precondizioni:
		- non esiste x:User, x != u, tale che x.email = newEmail
	postcondizioni:
		- u.email = newEmail, u.password = newPassword
```

### 9.3 Specifica dello use case Strumenti pubblicazione

```
publish_hardware(u: User, t: String, d: String, p: Real, c: Category,
                  mp: PaymentMethod [1..*], f: URL [0..5] {ordered}, condition: Condition,
                  location: String, shipping: Real): HardwareListing
	precondizioni:
		- c.leaf() e c.root().name = "Hardware"
		- mp non e' vuoto; |f| <= 5; p > 0; shipping >= 0
	postcondizioni:
		- crea e restituisce a:HardwareListing con:
			- a.title = t, a.description = d, a.price = p, a.photos = f,
			  a.paymentMethods = mp, a.condition = condition, a.shippingLocation = location,
			  a.shippingCost = shipping
			- a.status = active
			- a.publication = adesso
		- crea il link (u,a):publishes e il link (a,c):listing_cat


publish_software(u: User, t: String, d: String, p: Real, c: Category,
                  mp: PaymentMethod [1..*], f: URL [0..5] {ordered},
                  platforms: Platform [0..*], versionNumber: VersionNumber,
                  notes: String [0..1], artifact: URL): SoftwareListing
	precondizioni:
		- u.isDeveloper()                            [V.SoftwareListing.developersOnly]
		- c.leaf() e c.root().name = "Software"
		- mp e piat non sono vuoti; |f| <= 5; p > 0
	postcondizioni:
		- crea e restituisce a:SoftwareListing con:
			- a.title = t, a.description = d, a.price = p, a.photos = f,
			  a.paymentMethods = mp, a.platforms = platforms
			- a.status = active
			- a.publication = adesso
		- crea il link (u,a):publishes e il link (a,c):listing_cat
		- crea v:Version con v.number = versionNumber, v.notes = notes,
		  v.artifact = artifact, v.release = adesso, e il link (a,v):version


release_version(u: User, a: SoftwareListing, n: VersionNumber,
                  notes: String [0..1], artifact: URL): Version
	precondizioni:
		- (u,a):publishes
		- non esiste v:Version con (a,v):version e v.number = n
		- n e' maggiore del numero di a.currentVersion()
	postcondizioni:
		- crea e restituisce v:Version con v.number = n, v.notes = notes,
		  v.artifact = artifact, v.release = adesso
		- crea il link (a,v):version
		- per ogni x:User che possiede una licenza su a oppure che segue u,
		  crea n:ReleaseNotification con destinatario x, bersaglio v,
		  n.instant = adesso e n.read = falso


change_price(u: User, a: Listing, newPrice: Real)
	precondizioni:
		- (u,a):publishes
		- a.status = active
		- nuovoPrezzo > 0 e nuovoPrezzo != a.price
	postcondizioni:
		- crea v:PriceChange con v.instant = adesso,
		  v.oldPrice = a.price, v.newPrice = newPrice,
		  e il link (a,v):priceChange
		- a.price = newPrice
		- se v.drop(), per ogni p:Favourite con p.listing = a crea
		  n:PriceDropNotification con destinatario p.user, bersaglio v,
		  n.instant = adesso e n.read = falso


edit_listing(u: User, a: Listing, t: String, d: String, c: Category,
                  f: URL [0..5] {ordered})
	precondizioni:
		- (u,a):publishes e a.status = active
		- c.leaf() e c.root() coerente con il tipo di a
	postcondizioni:
		- a.title = t, a.description = d, a.photos = f
		- il link (a, oldCategory):listing_cat viene sostituito da (a,c):listing_cat


remove_listing(u: User, a: Listing)
	precondizioni:
		- (u,a):publishes
		- a.status = active
	postcondizioni:
		- a.status = removed
		- tutti i p:Favourite con p.listing = a vengono eliminati, insieme alle
		  n:FavouriteNotification che li hanno come bersaglio
		                                       [V.Notification.targetIntegrity]
		- le conversazioni ancorate ad a e gli ordini gia' conclusi restano invariati
		  (storico non distruttivo)
```

### 9.4 Specifica dello use case Strumenti consultazione

```
search(text: String [0..1], c: Category [0..1], type: ListingType [0..1],
      pMin: Real [0..1], pMax: Real [0..1]): Listing [0..*]
	precondizioni:
		- se pMin e pMax sono entrambi presenti, pMin <= pMax
	postcondizioni:
		- Non ha side effect.
		- result = { a:Listing | a.available()
		             e (testo assente oppure testo e' contenuto in a.title o a.description)
		             e (c assente oppure la categoria di a appartiene a
		                {c} unione c.allSubcategories())
		             e (type assente oppure a e' istanza della sottoclasse corrispondente)
		             e (pMin assente oppure a.price >= pMin)
		             e (pMax assente oppure a.price <= pMax) }


view_profile(u: User): User
	precondizioni: nessuna.
	postcondizioni:
		- Non ha side effect.
		- result = u (con i suoi annunci attivi, le recensioni ricevute,
		  la valutazione media e il numero di vendite concluse)
```

### 9.5 Specifica dello use case Strumenti acquisto

```
buy(u: User, a: Listing, mp: PaymentMethod, addr: Address [0..1], f: Offer [0..1]): Order
	precondizioni:
		- se f e' assente: a.available()
		- se f e' presente: (u,f):makes, (a,f):receives, f.status = accepted,
		  adesso < f.expiresAt, nessun ordine collegato a f e prenotazione corrente
		  di a intestata a f; solo questo acquirente puo' ignorare a.available()
		- non (u,a):publishes                        [V.Order.noSelfPurchase]
		- mp appartiene ad a.paymentMethods
		- addr e' presente se e solo se a:HardwareListing
		                                     [V.Order.shippingAddressIffHardware]
	postcondizioni:
		- crea e restituisce o:Order con:
			- o.instant = adesso
			- o.pricePaid = (f.amount se f e' presente, a.price altrimenti)
			  + (a.shippingCost se a:HardwareListing, 0 altrimenti)
			- o.paymentMethod = mp
			- o.shippingAddress = addr
			- o.status = awaitingPayment
		- crea i link (u,o):buys e (a,o):order_listing
		- se f e' presente, crea (f,o):offer_order atomicamente con l'ordine e
		  riutilizza la prenotazione di f; l'offerta non puo' generare altri ordini
		- se a:HardwareListing, a.status = sold
		- se a:SoftwareListing, crea l:License con l.key = una nuova chiave univoca,
		  l.release = adesso, e il link (o,l):order_license


make_offer(u: User, a: Listing, amount: Real): Offer
	precondizioni:
		- a.available() e a:HardwareListing          [V.Offer.hardwareOnly]
		- non (u,a):publishes                        [V.Offer.noSelfOffer]
		- non esiste f:Offer pending di u su a        [V.Offer.singlePendingPerBuyer]
		- 0 < amount < a.price; nessuna soglia minima ulteriore
	postcondizioni:
		- crea e restituisce f:Offer con f.amount = amount, f.instant = adesso,
		  f.status = pending, f.expiresAt assente
		- crea i link (u,f):makes e (a,f):receives
		- crea n:OfferReceivedNotification con destinatario il venditore di a,
		  bersaglio f, n.instant = adesso, n.read = falso


respond_to_offer(u: User, f: Offer, response: OfferStatus)
	precondizioni:
		- sia a l'annuncio con (a,f):receives; (u,a):publishes
		- f.status = pending e response in {accepted, rejected}
		- se response = accepted: a.available()
	postcondizioni:
		- f.status = response; f.amount non cambia (nessuna controfferta)
		- se response = accepted, riserva a all'offerente di f, a.status = sold,
		  f.expiresAt = adesso + 24 ore (durata configurabile); porta ogni altra
		  offerta pending su a a rejected, senza riattivarla alla scadenza di f
		- l'accettazione e' esclusiva rispetto ad altre accettazioni e acquisti
		- se response = accepted, crea n:OfferAcceptedNotification con destinatario
		  l'offerente di f, bersaglio f, n.instant = adesso, n.read = falso


withdraw_offer(u: User, f: Offer)
	precondizioni:
		- (u,f):makes e f.status = pending
	postcondizioni:
		- f.status = withdrawn


confirm_payment(o: Order)
	precondizioni:
		- o.status = awaitingPayment
	postcondizioni:
		- o.status = paid
		- se l'annuncio di o e' software, o.status = delivered (consegna istantanea)


update_order_status(u: User, o: Order, s: OrderStatus)
	precondizioni:
		- u = o.seller() se s in {shipped}; u = l'acquirente di o se s in {delivered}
		- la transizione da o.status a s e' ammessa:
			awaitingPayment -> paid | cancelled
			paid            -> shipped | refunded
			shipped         -> delivered | refunded
			delivered       -> refunded
	postcondizioni:
		- o.status = s
		- se s = cancelled o s = refunded e l'annuncio e' hardware,
		  l'annuncio torna in stato active


leave_review(u: User, o: Order, v: Integer, comment: String [0..1]): Review
	precondizioni:
		- (u,o):buys
		- o.reviewable()                            [V.Review.legalReview]
		- 0 <= v <= 5
	postcondizioni:
		- crea e restituisce r:Review con r.rating = v, r.comment = comment,
		  r.instant = adesso, e il link (o,r):order_review
		- crea n:ReviewNotification con destinatario r.recipient(), bersaglio r,
		  n.instant = adesso, n.read = falso
```

### 9.6 Specifica dello use case Strumenti social

```
add_favourite(u: User, a: Listing): Favourite
	precondizioni:
		- a.available()
		- non (u,a):publishes                          [V.Favourite.noSelfFavourite]
		- non esiste p:Favourite con p.user = u e p.listing = a
	postcondizioni:
		- crea e restituisce p:Favourite con p.user = u, p.listing = a, p.instant = adesso
		- crea n:FavouriteNotification con destinatario il venditore di a, bersaglio p,
		  n.instant = adesso, n.read = falso


remove_favourite(u: User, a: Listing)
	precondizioni:
		- esiste p:Favourite con p.user = u e p.listing = a
	postcondizioni:
		- ogni n:FavouriteNotification con (n,p):fav_not viene eliminata,
		  letta o non letta che sia            [V.Notification.targetIntegrity]
		- p viene eliminato


follow(u: User, v: User): Follow
	precondizioni:
		- u != v                                      [V.Follow.noSelfFollow]
		- non esiste f:Follow con f.follower = u e f.followed = v
	postcondizioni:
		- crea e restituisce f:Follow con f.follower = u, f.followed = v, f.instant = adesso
		- crea n:FollowNotification con destinatario v, bersaglio f,
		  n.instant = adesso, n.read = falso


unfollow(u: User, v: User)
	precondizioni:
		- esiste f:Follow con f.follower = u e f.followed = v
	postcondizioni:
		- ogni n:FollowNotification con (n,f):follow_not viene eliminata
		                                       [V.Notification.targetIntegrity]
		- f viene eliminato

```

### 9.7 Specifica dello use case Strumenti messaggistica

```
start_conversation(u: User, a: Listing, text: String): Conversation
	precondizioni:
		- a.status = active
		- non (u,a):publishes                          [V.Conversation.distinctParticipants]
		- non esiste c:Conversation ancorata ad a con interessato u
	postcondizioni:
		- crea e restituisce c:Conversation con c.creation = adesso,
		  e i link (a,c):listing_conv e (u,c):user_conv
		- crea m:Message con m.text = text, m.instant = adesso, m.read = falso,
		  e i link (c,m):contains e (u,m):sender
		- crea n:MessageNotification con destinatario c.seller(), bersaglio m,
		  n.instant = adesso, n.read = falso


send_message(u: User, c: Conversation, text: String): Message
	precondizioni:
		- u e' l'interessato di c oppure u = c.seller()
	postcondizioni:
		- crea e restituisce m:Message con m.text = text, m.instant = adesso,
		  m.read = falso, e i link (c,m):contains e (u,m):sender
		- crea n:MessageNotification con destinatario l'altro partecipante di c,
		  bersaglio m, n.instant = adesso, n.read = falso


mark_conversation_read(u: User, c: Conversation)
	precondizioni:
		- u e' l'interessato di c oppure u = c.seller()
	postcondizioni:
		- per ogni m:Message con (c,m):contains e mittente diverso da u: m.read = vero
		- le eventuali MessageNotification di u relative a tali messaggi hanno read = vero
```

### 9.8 Specifica dello use case Strumenti notifiche

```
mark_notification_read(u: User, n: Notification)
	precondizioni:
		- (u,n):recipient
	postcondizioni:
		- n.read = vero


mark_all_read(u: User)
	precondizioni: nessuna.
	postcondizioni:
		- per ogni n:Notification con (u,n):recipient, n.read = vero


unread_notifications(u: User): Integer
	precondizioni: nessuna.
	postcondizioni:
		- Non ha side effect.
		- result = u.unreadNotifications()
```

### 9.9 Specifica dello use case Strumenti amministrazione

```
create_category(adm: Admin, name: String, parent: Category [0..1]): Category
	precondizioni:
		- non esiste c:Category con lo stesso nome e la stessa categoria parent
		                                     [V.Category.uniqueNameAmongSiblings]
		- se padre e' assente, nome deve essere "Hardware" o "Software" e tale radice
		  non deve gia' esistere                       [V.Category.fixedRoots]
		- se padre e' presente, non esistono annunci gia' classificati in padre
		  (una categoria non foglia non puo' contenere annunci)
	postcondizioni:
		- crea e restituisce c:Category con c.name = name
		- se padre e' presente, crea il link (padre,c):subcategory
		- crea il link (adm,c):creates


remove_offensive_listing(adm: Admin, a: Listing, reason: String): Moderation
	precondizioni:
		- a.status != removed
	postcondizioni:
		- a.status = removed
		- crea e restituisce m:Moderation con m.instant = adesso, m.reason = reason,
		  m.admin = adm, m.listing = a
		- tutti i p:Favourite con p.listing = a vengono eliminati, insieme alle
		  n:FavouriteNotification che li hanno come bersaglio
		                                       [V.Notification.targetIntegrity]
```

---

## 10. Ristrutturazione dello schema

La ristrutturazione elimina dallo schema concettuale i costrutti che in Java non hanno corrispettivo, ottenendo uno schema traducibile in codice senza ulteriori decisioni. I passi sono applicati nell'ordine seguente.

### 10.1 Eliminazione degli attributi derivati

Ogni attributo marcato `/` scompare e viene sostituito da un'operazione, già specificata nel capitolo 7. L'informazione non viene memorizzata: viene ricalcolata a ogni richiesta.

|Attributo derivato|Diventa|
|---|---|
|`User./isDeveloper`|`boolean isDeveloper()`|
|`User./averageRating`|`double averageRating(LocalDateTime i)`|
|`User./completedSales`|`int completedSales(LocalDateTime i)`|
|`Listing./available`|`boolean isAvailable()`|
|`Listing./favouriteCount`|`int favouriteCount()`|
|`SoftwareListing./currentVersion`|`Version currentVersion()`|
|`Category./root`|`Category root()`|
|`Order./reviewable`|`boolean isReviewable()`|
|`License./includedVersions`|`Set<Version> includedVersions()`|
|`Review./author`, `/recipient`|`User author()`, `User recipient()`|
|`Conversation./seller`, `/lastMessage`|`User seller()`, `Message lastMessage()`|
|`PriceChange./drop`|`boolean isDrop()`|

### 10.2 Eliminazione delle classi di associazione

Una classe di associazione è insieme classe e associazione: Java non ha nulla di simile. Ciascuna diventa una classe ordinaria che porta al proprio interno i riferimenti alle due classi partecipanti, e l'associazione originaria scompare.

|Classe di associazione|Diventa|Vincolo da aggiungere|
|---|---|---|
|`Favourite {instant}` su `favourites`|classe `Favourite` con `user: User [1..1]`, `listing: Listing [1..1]`, `instant`|unicità della coppia (`user`, `listing`)|
|`Follow {instant}` su `follows`|classe `Follow` con `follower: User [1..1]`, `followed: User [1..1]`, `instant`|unicità della coppia, non riflessività|
|`Moderation {instant, reason}` su `moderation`|classe `Moderation` con `admin: Admin [1..1]`, `listing: Listing [1..1]`, `instant`, `reason`|—|

L'unicità della coppia era implicita nella semantica dell'associazione (fra due oggetti esiste al più un link): reificando, quella garanzia si perde e va riscritta come vincolo.

### 10.3 Eliminazione delle molteplicità sugli attributi

La molteplicità non è un costrutto Java: sopravvive solo come tipo di collezione o come riferimento che può valere `null`.

|Attributo concettuale|Diventa|
|---|---|
|`photos: URL [0..5] {ordered}`|`List<String> photos` — l'ordine è garantito dalla lista, il limite di 5 da un controllo|
|`paymentMethods: PaymentMethod [1..*]`|`Set<PaymentMethod> paymentMethods`, da verificare non vuoto|
|`platforms: Platform [1..*]`|`Set<Platform> platforms`, da verificare non vuoto|
|`location`, `bio`, `avatar`, `comment`, `notes`, `shippingAddress` `[0..1]`|campo che ammette `null`|

Lo stesso vale per le molteplicità delle associazioni: `0..*` e `1..*` diventano collezioni, `0..1` e `1..1` riferimenti singoli. I limiti inferiori (`1..1`, `1..*`) non sono esprimibili e passano fra i vincoli del 10.8.

### 10.4 Eliminazione delle gerarchie

Entrambe le gerarchie sono `{disjoint, complete}`, ma hanno struttura diversa e ricevono trattamenti diversi.

#### `Listing` → divisione con associazioni `0..1`

`Listing` conserva gli attributi comuni e diventa una classe concreta; `HardwareListing` e `SoftwareListing` restano classi autonome, non più sottoclassi, collegate a `Listing` da due associazioni:

```
Listing  1..1 ── hardware ── 0..1  HardwareListing
Listing  1..1 ── software ── 0..1  SoftwareListing
```

**[V.Listing.exactlyOnePart]** Per ogni `l:Listing` è presente esattamente una delle due parti: o `l.hardware` o `l.software`, mai entrambe (disgiunzione) e mai nessuna (completezza).

Il test di tipologia, che nel concettuale era l'appartenenza a una sottoclasse, diventa la presenza della parte: `l.hardware != null`. Tutti i vincoli del capitolo 8 che dicono "per ogni `a:HardwareListing`" vanno riletti come "per ogni `a:Listing` con parte hardware presente"; il testo del capitolo 8 resta però quello concettuale e non viene riscritto.

Si è scelta la divisione anziché l'accorpamento verso l'alto perché quest'ultimo avrebbe reso opzionali quattro attributi obbligatori (`condition`, `shippingLocation`, `shippingCost`, `platforms`), avrebbe abbassato l'associazione `version` da `1..*` a `0..*` — costringendo `currentVersion()` a una precondizione che prima era strutturale — e avrebbe richiesto tre vincoli di coerenza fra discriminante e attributi presenti anziché uno solo. L'accorpamento verso il basso è stato scartato perché `Listing` è coinvolta in sette associazioni, che si sarebbero duplicate tutte.

#### `Notification` → accorpamento verso l'alto

Le otto sottoclassi non aggiungono attributi propri: differiscono unicamente per la classe dell'oggetto bersaglio. Vengono quindi accorpate nella superclasse, che acquista un attributo discriminante e otto riferimenti opzionali, uno per tipo di notifica:

```
Notification
	instant: DateTime
	read: Boolean
	type: NotificationType
	recipient: User [1..1]
	mes_not: Message [0..1]
	fav_not: Favourite [0..1]
	pc_not: PriceChange [0..1]
	rel_not: Version [0..1]
	rev_not: Review [0..1]
	follow_not: Follow [0..1]
	offer_received_not: Offer [0..1]
	offer_accepted_not: Offer [0..1]
```

Si introduce il tipo enumerativo `NotificationType: {message, favourite, priceDrop, release, review, follow, offerReceived, offerAccepted}`, che non esisteva nel concettuale perché lì l'informazione era portata dalla classe stessa.

**[V.Notification.targetMatchesType]** Per ogni `n:Notification` è presente esattamente il riferimento corrispondente a `n.type`, e gli altri sette sono assenti: se `n.type = message` allora `n.mes_not` è presente e gli altri no, e così via per gli altri sette casi.

L'operazione polimorfa `target()`, che nel concettuale sarebbe stata astratta e ridefinita in ciascuna sottoclasse, diventa una selezione sul discriminante.

Il costo dell'accorpamento è appunto questo vincolo, più sette riferimenti sempre nulli in ogni istanza. È accettabile perché le sottoclassi erano prive di stato proprio: mantenerle avrebbe significato otto classi da un solo campo ciascuna. Su `Listing`, dove le sottoclassi hanno attributi obbligatori e comportamento distinto, la stessa scelta sarebbe stata sbagliata — ed è la ragione per cui le due gerarchie ricevono trattamenti opposti.

### 10.5 Sostituzione dei tipi di dato

I tipi UML del capitolo 5 vengono sostituiti con tipi Java.

|Tipo UML|Tipo Java|Nota|
|---|---|---|
|`String`, `Boolean`|`String`, `boolean`|—|
|`Integer`|`int`||
|`Real` (importi)|`java.math.BigDecimal`|non `double`: gli errori di arrotondamento binario sono inaccettabili sui prezzi|
|`DateTime`|`java.time.LocalDateTime`||
|`URL`, `Email`, `VersionNumber`|`String`|il formato non è esprimibile nel tipo e va validato|
|`Address`|`record Address(...)`|tipo di dato senza identità: un `record` immutabile, non una classe con `id`|
|identificatore surrogato|`java.util.UUID`|solo per le tre classi prive di identificatore naturale|
|«enumeration»|`enum`|`Condition`, `ListingStatus`, `OrderStatus`, `OfferStatus`, `PaymentMethod`, `Platform`, `ListingType`, `NotificationType`|

I **vincoli di dominio** del capitolo 5.3 (`{> 0}`, `{>= 0}`, `{0..5}`) e quelli di **unicità** (`{unique}` su `username`, `email`, `key`) non hanno corrispettivo nel sistema dei tipi: passano fra i vincoli del 10.8.

### 10.6 Individuazione degli identificatori

Ogni classe dello schema ristrutturato deve avere un identificatore. Dove esiste un identificatore naturale lo si adotta; dove l'identificazione passa per una o più associazioni si tratta di un **identificatore esterno**, e le estremità coinvolte devono avere molteplicità `1..1`; dove non esiste né l'uno né l'altro si introduce un codice surrogato.

**Convenzione di notazione.** Una classe può avere più identificatori. Qui si scrive `{id1}` quello **primario**, cioè quello con cui la classe viene referenziata e indirizzata, e `{id2}` quello **secondario**, che resta vero e va garantito ma non è usato per riferirsi all'oggetto. Dove l'identificatore è uno solo si scrive `{id}` senza numero. Quando un identificatore è **composto**, tutte le sue componenti portano lo stesso numero: in `Conversation` sia `listing` sia `interestedUser` sono marcate `{id}` perché sono due parti di un unico identificatore, non due identificatori distinti. La numerazione è quindi una gerarchia adottata in questo documento; UML di per sé numera gli identificatori senza ordinarli.

|Classe|`{id1}` (primario)|`{id2}`, `{id3}` (secondari)|
|---|---|---|
|`User`|`id: UUID`|`username` `{id2}` · `email` `{id3}`|
|`Admin`|`id: UUID`|`username` `{id2}`|
|`DeveloperProfile`|`user` (esterno, assoc. `u_role`)|—|
|`Listing`|`id: UUID`|—|
|`HardwareListing`|`listing` (esterno, assoc. `l_hardware`)|—|
|`SoftwareListing`|`listing` (esterno, assoc. `l_software`)|—|
|`Category`|`id: UUID`|—|
|`Version`|`softwareListing` + `number` (esterno composto: **entrambi** `{id1}`)|—|
|`PriceChange`|`id: UUID`|—|
|`Offer`|`id: UUID`|nessuno|
|`Order`|`id: UUID`|`license` `{id2}` · `review` `{id3}`|
|`License`|`key` (interno)|`order` `{id2}` (esterno)|
|`Review`|`id: UUID`|`order` `{id2}` (esterno)|
|`Favourite`|`user` + `listing` (esterno composto: entrambi `{id1}`)|—|
|`Follow`|`follower` + `followed` (esterno composto: entrambi `{id1}`)|—|
|`Moderation`|`id: UUID`|—|
|`Conversation`|`id: UUID`|`listing` + `interestedUser` (composto: entrambi `{id2}`)|
|`Message`|`id: UUID`|—|
|`Notification`|`id: UUID`|—|

`Address` non compare: è un tipo di dato, non una classe, e non ha identità propria.

Le componenti di un identificatore **composto** portano tutte lo stesso numero, perché sono parti di un unico identificatore e nessuna identifica da sola. Numeri diversi su attributi diversi indicano invece identificatori **distinti**, ciascuno dei quali identifica per conto proprio.

I secondari di `Order` (`license`, `review`) sono le uniche chiavi alternative parzialmente nulle del modello: valgono quando il riferimento è presente. Sono ridondanti rispetto agli identificatori di `License` e `Review`, che dicono la stessa cosa dall'altro lato, e possono essere omessi senza perdita.

**Vincoli assorbiti.** Diversi vincoli del capitolo 8 sono esattamente asserzioni di unicità: diventando identificatori non vanno più garantiti a parte.

- `[V.Favourite.afterPublicationAndUnique]` — l'unicità della coppia (utente, annuncio) è l'identificatore di `Favourite`; resta da verificare solo l'ordinamento temporale.
- `[V.Follow.noSelfFollow]` — l'unicità della coppia è l'identificatore di `Follow`; resta la non riflessività.
- `[V.Conversation.onePerListingAndUser]` — è l'identificatore secondario `{id2}` di `Conversation`: resta un identificatore a tutti gli effetti, quindi il vincolo resta assorbito anche ora che il primario è un surrogato.
- `[V.Version.uniqueOrderedNumber]` — l'unicità del numero nell'ambito dell'annuncio è l'identificatore di `Version`; resta la monotonia rispetto all'istante di rilascio.
- `[V.Review.onePerOrder]` — è l'identificatore secondario `{id2}` di `Review`.

**Codici surrogati.** Undici classi ricevono un identificatore surrogato, per cinque ragioni distinte.

`Listing`, `Order`, `Offer` e `Notification` non hanno alcuna combinazione di attributi e associazioni che le identifichi in modo affidabile: due annunci dello stesso venditore possono coincidere in tutto, e lo stesso acquirente può comprare due volte lo stesso annuncio software.

`User` un identificatore naturale ce l'avrebbe, anzi due — `username` ed `email` — ma **entrambi sono modificabili**: `update_profile` cambia lo username, `update_credentials` cambia l'email. Un identificatore deve invece essere immutabile, perché è ciò a cui gli altri oggetti si riferiscono: se cambiasse, cambierebbe con lui ogni identificatore esterno costruito su di esso, cioè quelli di `Favourite`, `Follow`, `Conversation` e `DeveloperProfile`. `username` ed `email` restano identificatori a tutti gli effetti — sono unici e non nulli — ma **secondari**: sono marcati `{id2}` e `{id3}`, mentre il primario diventa un `id` surrogato che l'utente non vede e che nessuna operazione modifica. È la stessa distinzione fra chiave primaria e chiavi alternative: una chiave alternativa può cambiare valore senza danno, perché nessuno la usa per riferirsi all'oggetto.

`Message`, `PriceChange` e `Moderation` avrebbero un identificatore esterno della forma (oggetto contenitore, istante), che però è **fragile**: regge solo se ci si fida della risoluzione del timestamp, che è un dettaglio implementativo. In un sistema distribuito, con orologi diversi e scritture concorrenti, non è una garanzia su cui fondare l'identità di un oggetto. Il caso più esposto è `Message`, dove due messaggi nella stessa conversazione possono realisticamente cadere nello stesso istante.

`Review` e `Conversation` un identificatore esterno solido ce l'hanno — rispettivamente l'ordine e la coppia (annuncio, interessato) — ma sono oggetti che il sistema deve poter **indirizzare direttamente**, senza passare da ciò che li contiene. Ricevono quindi un surrogato come identificatore primario, e conservano quello esterno come `{id2}`: la doppia marcatura è ciò che tiene assorbiti i vincoli `[V.Review.onePerOrder]` e `[V.Conversation.onePerListingAndUser]`, che altrimenti tornerebbero nella lista del 10.8.

Anche offerte successive dello stesso utente sullo stesso annuncio devono avere identità distinte: `singlePendingPerBuyer` limita soltanto quelle pendenti.

A tutte e undici si aggiunge quindi l'attributo `id`, assegnato dal sistema alla creazione e mai modificato. Si realizza come `java.util.UUID`, generato con `UUID.randomUUID()`: rispetto a un contatore incrementale non richiede un'entità che tenga il conto e resta valido anche se gli oggetti vengono creati da processi diversi, cosa che conta per l'implementazione a microservizi dell'appendice.

Le altre otto classi **non** ricevono un identificatore surrogato: hanno già un identificatore interno o esterno, e aggiungerne uno lo renderebbe ridondante, obbligando per giunta a riscrivere come vincoli di unicità le proprietà che l'identificatore esterno garantisce da solo. Un `id: UUID` su ogni classe è la scelta tipica quando si passa a una base di dati o a un ORM, dove ogni tabella vuole una chiave primaria semplice; è però una decisione di quel livello, non della ristrutturazione.

**License, l'unico caso a due identificatori.** La chiave è primaria perché è ciò che l'acquirente esibisce per riscattare la licenza e con cui il sistema la cerca; l'ordine resta un identificatore altrettanto valido — un ordine genera al più una licenza — ma secondario, perché nessuno raggiunge una licenza partendo dall'ordine.

**Category, il surrogato che semplifica.** L'identificatore naturale sarebbe la coppia (categoria padre, nome), dato che il dominio vieta solo l'omonimia fra sorelle. Non è però utilizzabile come identificatore esterno, perché l'estremità `parent` ha molteplicità `0..1` e le due radici non hanno padre. Le alternative erano due: rendere `name` univoco a livello globale — cioè **imporre al dominio un vincolo che non ha**, impedendo di avere "Accessori" sia sotto Hardware sia sotto Software — oppure introdurre un surrogato e lasciare l'unicità fra sorelle come semplice vincolo. Si è scelta la seconda: qui il surrogato non aggiunge complessità, la toglie. Va inoltre nella stessa direzione dell'immutabilità, perché rinominare una categoria è un'operazione plausibile che oggi il modello non prevede ma che non deve poter rompere l'identificazione.

### 10.7 Eliminazione delle associazioni

Java non ha il concetto di associazione: ha soltanto riferimenti fra oggetti, e un riferimento è per sua natura orientato. Ogni associazione va quindi tradotta in uno o due attributi, a seconda che serva percorrerla in una sola direzione o in entrambe. La molteplicità determina il tipo dell'attributo: `0..*` e `1..*` diventano collezioni, `0..1` e `1..1` riferimenti singoli.

|Associazione|Attributi introdotti|Direzione|
|---|---|---|
|`role`|`User.role: DeveloperProfile` · `DeveloperProfile.user: User`|doppia|
|`publishes`|`User.listings: List<Listing>` · `Listing.seller: User`|doppia|
|`hardware`|`Listing.hardware: HardwareListing` · `HardwareListing.listing: Listing`|doppia|
|`software`|`Listing.software: SoftwareListing` · `SoftwareListing.listing: Listing`|doppia|
|`listing_cat`|`Listing.category: Category`|singola|
|`subcategory`|`Category.parent: Category` · `Category.subcategories: List<Category>`|doppia|
|`priceHistory`|`Listing.priceHistory: List<PriceChange>` · `PriceChange.listing: Listing`|doppia|
|`versions`|`SoftwareListing.versions: List<Version>` · `Version.softwareListing: SoftwareListing`|doppia|
|`makes`|`Offer.buyer: User`|singola|
|`receives`|`Offer.listing: Listing`|singola|
|`offer_order`|`Offer.order: Order`|singola|
|`buys`|`User.orders: List<Order>` · `Order.buyer: User`|doppia|
|`order_listing`|`Listing.orders: List<Order>` · `Order.listing: Listing`|doppia|
|`order_license`|`Order.license: License` · `License.order: Order`|doppia|
|`order_review`|`Order.review: Review` · `Review.order: Order`|doppia|
|`listing_conv`|`Listing.conversations: List<Conversation>` · `Conversation.listing: Listing`|doppia|
|`interestedUser`|`User.conversations: List<Conversation>` · `Conversation.interestedUser: User`|doppia|
|`messages`|`Conversation.messages: List<Message>` · `Message.conversation: Conversation`|doppia|
|`sender`|`Message.sender: User`|singola|
|`recipient`|`User.notifications: List<Notification>` · `Notification.recipient: User`|doppia|
|bersagli delle notifiche|`Notification.messageTarget`, `favouriteTarget`, `priceDropTarget`, `releaseTarget`, `reviewTarget`, `followTarget`, `offerReceivedTarget`, `offerAcceptedTarget`|singola|
|`favourites` (reificata)|`Favourite.user`, `Favourite.listing` · `User.favourites`, `Listing.favourites`|doppia|
|`follows` (reificata)|`Follow.follower`, `Follow.followed` · `User.following`, `User.followers`|doppia|
|`moderation` (reificata)|`Moderation.admin`, `Moderation.listing` · `Admin.moderations`|doppia|
|`creates`|`Admin.createdCategories: List<Category>`|singola|

Tre osservazioni.

La direzione **non è sempre libera**: dove l'identificatore è esterno, la classe identificata deve poter raggiungere quella che la identifica, altrimenti il suo stesso identificatore non è calcolabile. È il caso di `Version.softwareListing`, `Favourite.user`, `Favourite.listing`, `Conversation.listing`, `Message.conversation` e di tutte le altre righe del 10.6 con identificatore esterno: lì la direzione è imposta.

Dove la direzione è singola, è perché nessuna operazione del capitolo 7 e nessuno use case del capitolo 9 percorre l'associazione al contrario: nessuna operazione chiede "tutti i messaggi spediti da un utente" o "tutti gli annunci di una categoria", quindi `sender` e `listing_cat` restano a senso unico. Aggiungere il verso inverso costerebbe una collezione da mantenere allineata senza che nessuno la legga.

La doppia direzione introduce infatti **ridondanza**: `user.listings.contains(l)` e `l.seller == user` sono la stessa informazione scritta due volte, e possono divergere. Ogni operazione che crea o elimina un link deve aggiornare entrambi i lati — motivo per cui nel codice conviene che quel lavoro lo facciano metodi dedicati, e non l'assegnamento diretto dei campi.

---

### 10.8 Vincoli non esprimibili nello schema ristrutturato

Ciò che il diagramma concettuale esprimeva con la notazione UML e che Java non sa rappresentare resta come vincolo, da garantire nelle operazioni che realizzano gli use case del capitolo 9 e nei costruttori delle classi coinvolte.

|Origine|Vincoli|
|---|---|
|Molteplicità minime|`paymentMethods` e `platforms` non vuoti; `versions` non vuota; `messages` non vuota; ogni riferimento `1..1` non nullo|
|Molteplicità massime|al più 5 `photos`; al più un `Order` non annullato per un annuncio hardware|
|Vincoli di dominio (5.3)|`price > 0`, `shippingCost >= 0`, `pricePaid > 0`, `Offer.amount > 0`, `oldPrice > 0`, `newPrice > 0`, `0 <= rating <= 5`|
|Unicità|`User.username` e `User.email`, identificatori secondari da garantire; `Category.name` univoco fra le sorelle di uno stesso padre, che non essendo un identificatore va controllato esplicitamente; il resto è assorbito dagli identificatori del 10.6|
|Gerarchie eliminate|`[V.Listing.exactlyOnePart]`, `[V.Notification.targetMatchesType]`|
|Vincoli esterni|tutti quelli del capitolo 8|

---

## 11. Diagramma delle classi ristrutturato

La fase 3.1 realizza `Offer`, identificata da `UUID`, con `amount: BigDecimal`,
`instant: LocalDateTime`, `status: OfferStatus`, `expiresAt: LocalDateTime` opzionale,
`rejectionReason: RejectionReason` opzionale, `holdReservationId: UUID` opzionale, e
riferimenti a `buyer: User`, `listing: Listing` e `order: Order` opzionale, letto come l'ordine
vivo corrente dell'offerta (§13.4: al più un ordine non annullato, nessuno dopo un ordine
`PAID`). `OfferStatus` comprende `PENDING`, `ACCEPTED`, `REJECTED`, `EXPIRED`, `WITHDRAWN`. Le
dichiarazioni Java sono riportate nel capitolo 12; i diagrammi esterni restano da allineare a
questa estensione.

Il diagramma è nel file `class_diagram_ristrutturato.mermaid`. Rispetto al concettuale:

1. non compaiono attributi derivati, sostituiti da operazioni;
2. `Favourite`, `Follow` e `Moderation` sono classi ordinarie con due riferimenti ciascuna;
3. non ci sono più generalizzazioni: `Listing` è collegata alle due parti da riferimenti opzionali, e `Notification` è una classe unica con discriminante;
4. **non ci sono più associazioni**: ogni classe elenca fra i propri attributi i riferimenti che la collegano alle altre (`User.listings: List<Listing>`, `Listing.seller: User`, e così via), realizzati come collezione per `0..*` e `1..*` e come riferimento singolo per `0..1` e `1..1`;
5. i tipi sono quelli di Java;
6. ogni classe porta il proprio identificatore: interno, esterno o surrogato (`UUID`).

Le frecce disegnate fra le classi non sono associazioni UML: rappresentano gli stessi riferimenti già elencati fra gli attributi, e sono lasciate per rendere leggibile la struttura. Un'associazione bidirezionale del concettuale compare quindi come due frecce opposte, cioè come i due attributi che la realizzano.

Sulle frecce le **molteplicità sono conservate**, anche se il tipo Java ne trattiene solo una parte: `List<Listing>` dice che gli annunci sono molti, ma non che sono almeno uno, e un riferimento singolo non distingue `0..1` da `1..1`. Riportarle serve a due cose: rende leggibile il diagramma senza dover risalire al concettuale, e tiene visibile ciò che il codice deve garantire per conto proprio — sono esattamente i limiti inferiori elencati fra i vincoli del 10.8.

---

## 12. Scheletro Java

```java
// ---------- tipi enumerativi ----------
public enum Condition { LIKE_NEW, VERY_GOOD, GOOD, ACCEPTABLE }
public enum ListingStatus { ACTIVE, SOLD, REMOVED }
public enum OrderStatus { AWAITING_PAYMENT, PAID, SHIPPED, DELIVERED, CANCELLED, REFUNDED }
public enum OfferStatus { PENDING, ACCEPTED, REJECTED, EXPIRED, WITHDRAWN }
public enum RejectionReason { SELLER, OTHER_ACCEPTED, LISTING_UNAVAILABLE, PRICE_CHANGED }
public enum PaymentMethod { BANK_TRANSFER, CREDIT_CARD, PAYPAL }
public enum Platform { WINDOWS, MACOS, LINUX, WEB, ANDROID, IOS }
public enum ListingType { HARDWARE, SOFTWARE }
public enum NotificationType { MESSAGE, FAVOURITE, PRICE_DROP, RELEASE, REVIEW, FOLLOW, OFFER_RECEIVED, OFFER_ACCEPTED }

// ---------- tipi di dato ----------
public record Address(String recipient, String street, String city,
                      String postalCode, String country) {}

// ---------- utenti ----------
public class User {
    private final UUID id;                   // {id} surrogato
    private String username;                 // {id2}, modificabile
    private String email;                    // {id3}, modificabile
    private String password;
    private final LocalDateTime registration;
    private String location, bio, avatar;    // possono valere null
    private DeveloperProfile role;           // può valere null
    private final List<Listing> listings = new ArrayList<>();
    private final List<Order> orders = new ArrayList<>();
    private final List<Favourite> favourites = new ArrayList<>();
    private final List<Follow> following = new ArrayList<>(), followers = new ArrayList<>();
    private final List<Conversation> conversations = new ArrayList<>();
    private final List<Notification> notifications = new ArrayList<>();

    public boolean isDeveloper() { return role != null; }
    public Set<Review> reviewsReceived(LocalDateTime i) { /* cap. 7.1 */ }
    public double averageRating(LocalDateTime i) { /* cap. 7.1 */ }
    public int completedSales(LocalDateTime i) { /* cap. 7.1 */ }
    public Set<Listing> availableListings() { /* cap. 7.1 */ }
    public int unreadNotifications() { /* cap. 7.1 */ }
    public int unreadMessages() { /* cap. 7.1 */ }
}

public class DeveloperProfile {
    private final LocalDateTime activation;
    private User user;
}

public class Admin {
    private String username;                 // {id}
    private String password;
    private final List<Category> createdCategories = new ArrayList<>();
    private final List<Moderation> moderations = new ArrayList<>();
}

// ---------- catalogo: gerarchia sostituita da due parti opzionali ----------
public class Listing {
    private final UUID id;                   // {id} surrogato
    private String title, description;
    private BigDecimal price;                                    // {> 0}
    private final LocalDateTime publication;
    private ListingStatus status;
    private final List<String> photos = new ArrayList<>();       // al più 5, ordinate
    private final Set<PaymentMethod> paymentMethods = new HashSet<>();   // non vuoto
    private User seller;
    private Category category;
    private HardwareListing hardware;        // esattamente una delle due
    private SoftwareListing software;        // e' diversa da null
    private final List<PriceChange> priceHistory = new ArrayList<>();
    private final List<Favourite> favourites = new ArrayList<>();
    private final List<Order> orders = new ArrayList<>();
    private final List<Conversation> conversations = new ArrayList<>();

    public boolean isAvailable() { return status == ListingStatus.ACTIVE; }
    public int favouriteCount() { return favourites.size(); }
    public Optional<PriceChange> lastPriceDrop() { /* cap. 7.2 */ }
    public ListingType type() {
        return hardware != null ? ListingType.HARDWARE : ListingType.SOFTWARE;
    }
}

public class HardwareListing {
    private Listing listing;
    private Condition condition;
    private String shippingLocation;
    private BigDecimal shippingCost;         // {>= 0}
}

public class SoftwareListing {
    private Listing listing;
    private final Set<Platform> platforms = new HashSet<>();     // non vuoto
    private final List<Version> versions = new ArrayList<>();    // non vuota
    public Version currentVersion() { /* cap. 7.3 */ }
}

public class Version {
    private String number;
    private final LocalDateTime release;
    private String notes;                    // può valere null
    private String artifact;                 // URL del file distribuito
    private SoftwareListing softwareListing;
}

public class PriceChange {
    private final UUID id;                   // {id} surrogato
    private final LocalDateTime instant;
    private final BigDecimal oldPrice, newPrice;                 // {> 0}
    private Listing listing;
    public boolean isDrop() { return newPrice.compareTo(oldPrice) < 0; }
}

public class Category {
    private final UUID id;                   // {id} surrogato
    private String name;                     // univoco fra le sorelle
    private Category parent;                 // può valere null: e' una radice
    private final List<Category> subcategories = new ArrayList<>();
    public Set<Category> allSubcategories() { /* cap. 7.4 */ }
    public Category root() { /* cap. 7.4 */ }
    public boolean isLeaf() { return subcategories.isEmpty(); }
}

// ---------- offerte e ordini ----------
public class Offer {
    private final UUID id;                   // {id} surrogato
    private final BigDecimal amount;          // {> 0}
    private final LocalDateTime instant;
    private OfferStatus status;
    private LocalDateTime expiresAt;          // presente dall'accettazione
    private RejectionReason rejectionReason;  // presente solo se status = REJECTED
    private UUID holdReservationId;           // prenotazione catalog del tentativo di accettazione; presente da accettato
    private User buyer;
    private Listing listing;                 // solo hardware
    private Order order;                     // al più un ordine vivo (non annullato); nessuno dopo un ordine PAID (§13.4)
}

public class Order {
    private final UUID id;                   // {id} surrogato
    private final LocalDateTime instant;
    private BigDecimal pricePaid;                                // {> 0}
    private PaymentMethod paymentMethod;
    private OrderStatus status;
    private Address shippingAddress;         // non null sse l'annuncio e' hardware
    private User buyer;
    private Listing listing;
    private License license;                 // può valere null: solo per il software
    private Review review;                   // può valere null
    public boolean isReviewable() { /* cap. 7.5 */ }
    public User seller() { return listing.getSeller(); }
}

public class License {
    private String key;                      // {id1}; l'ordine e' {id2}
    private final LocalDateTime release;
    private Order order;
    public Set<Version> includedVersions() { /* cap. 7.6 */ }
}

public class Review {
    private final UUID id;                   // {id1}; l'ordine e' {id2}
    private final int rating;                                    // {0..5}
    private String comment;                  // può valere null
    private final LocalDateTime instant;
    private Order order;
    public User author() { /* cap. 7.8 */ }
    public User recipient() { /* cap. 7.8 */ }
}

// ---------- classi di associazione reificate ----------
public class Favourite {
    private User user; private Listing listing;
    private final LocalDateTime instant;
}

public class Follow {
    private User follower; private User followed;
    private final LocalDateTime instant;
}

public class Moderation {
    private final UUID id;                   // {id} surrogato
    private Admin admin; private Listing listing;
    private final LocalDateTime instant; private String reason;
}

// ---------- messaggistica ----------
public class Conversation {
    private final UUID id;                   // {id1}; (listing, interestedUser) e' {id2}
    private final LocalDateTime creation;
    private Listing listing;
    private User interestedUser;
    private final List<Message> messages = new ArrayList<>();    // non vuota, ordinata
    public User seller() { return listing.getSeller(); }
    public Message lastMessage() { /* cap. 7.7 */ }
    public int unreadFor(User u) { /* cap. 7.7 */ }
}

public class Message {
    private final UUID id;                   // {id} surrogato
    private String text;
    private final LocalDateTime instant;
    private boolean read;
    private User sender;
    private Conversation conversation;
}

// ---------- notifiche: gerarchia accorpata ----------
public class Notification {
    private final UUID id;                   // {id} surrogato
    private final LocalDateTime instant;
    private boolean read;
    private User recipient;
    private NotificationType type;
    // esattamente il riferimento corrispondente a type e' diverso da null
    private Message messageTarget;
    private Favourite favouriteTarget;
    private PriceChange priceDropTarget;
    private Version releaseTarget;
    private Review reviewTarget;
    private Follow followTarget;
    private Offer offerReceivedTarget;
    private Offer offerAcceptedTarget;

    public Object target() {
        switch (type) {
            case MESSAGE:    return messageTarget;
            case FAVOURITE:  return favouriteTarget;
            case PRICE_DROP: return priceDropTarget;
            case RELEASE:    return releaseTarget;
            case REVIEW:     return reviewTarget;
            case FOLLOW:     return followTarget;
            case OFFER_RECEIVED: return offerReceivedTarget;
            case OFFER_ACCEPTED: return offerAcceptedTarget;
        }
    }
}
```

---

## 13. Appendice — mappatura verso l'architettura a microservizi

Lo schema ristrutturato è indipendente dall'architettura di deployment. Per l'implementazione a microservizi, i confini seguono i raggruppamenti già emersi nell'analisi:

|Servizio|Classi di competenza|Persistenza|
|---|---|---|
|**user**|`User`, `DeveloperProfile`, `Follow`, `Admin`|relazionale|
|**catalog**|`Listing` con le parti `HardwareListing` e `SoftwareListing`, `Category`, `Version`, `PriceChange`, `Favourite`, `Moderation`|documentale|
|**messaging**|`Conversation`, `Message`, `Notification`|documentale|
|**order**|`Order`, `Offer`, `License`, `Review`|relazionale|
|**payment**|stati e transizioni di `Order` lato pagamento|relazionale|

Osservazioni utili in sede di discussione:

- I riferimenti che attraversano il confine di servizio (per esempio `Listing.seller` o `Order.buyer`) diventano identificatori (`username` / `UUID`) più eventuali dati denormalizzati per la sola visualizzazione, non riferimenti a oggetti.
- I vincoli del capitolo 8 che coinvolgono classi di servizi diversi (`noSelfPurchase`, `singleItem`, `legalReview`) non possono più essere garantiti da una transazione unica: vanno realizzati con una saga e con la consistenza finale, oppure verificati dal servizio proprietario dell'aggregato (per esempio la disponibilità dell'annuncio è verificata da _catalog_ su richiesta di _order_).
- Gli attributi derivati costosi (`averageRating`, `completedSales`, `favouriteCount`) sono buoni candidati per una vista materializzata aggiornata dagli eventi, anziché per un calcolo a ogni lettura.

### 13.1 Scostamenti introdotti dalla fase 1 — Identità e profili

L'implementazione del servizio `user` (fase 1 della roadmap) introduce quattro scostamenti
rispetto allo schema ristrutturato dei capitoli 10-12. Sono registrati qui, con la loro
motivazione, perché ciascuno sia leggibile come una decisione tracciata e non come
un'incoerenza fra questo documento e il codice.

**1. La chiave d'identità e il suo tipo realizzato (10.6, `User.{id1}`).** L'identificatore
primario di `User` è realizzato come `sub`, il claim identificativo emesso dal provider di
identità esterno (AWS Cognito), adottato direttamente invece che tramite un surrogato generato
internamente. Soddisfa le tre proprietà che il §10.6 richiede a un surrogato: assegnato dal
sistema alla creazione, immutabile, mai modificato da alcuna operazione — lo scostamento sta
nella *realizzazione*, non nel ruolo dell'identificatore. Il tipo dichiarato dal §10.6 è
`java.util.UUID`; l'implementazione lo realizza invece come **stringa opaca di al più 64
caratteri** (`String`, colonna `VARCHAR(64)` con charset `ascii`), perché il provider documenta
esplicitamente che `sub` non è garantito in un formato UUID specifico, incluso RFC UUID, e che
il suo formato non va validato rigidamente (D-01 AMENDED). La conseguenza che rende la scelta
vincolante: un surrogato interno costringerebbe ogni altro servizio o a tradurre `sub`→`id` a
ogni scrittura via Feign, oppure a memorizzare comunque `sub`, nel qual caso il surrogato non
guadagnerebbe nulla.

**2. Le colonne assenti: credenziale e indirizzo di posta (10.6, `User`).** La tabella utente
non porta alcuna colonna credenziale né alcun vincolo di unicità su un indirizzo di posta: il
provider esterno possiede sia le credenziali sia l'unicità dell'indirizzo (D-02). Più a fondo:
l'indirizzo di posta non è memorizzato affatto — vive solo nel provider e nel token — perché
questa fase non ha bisogno dell'indirizzo di nessun altro utente, e una copia memorizzata
localmente si disallineerebbe non appena l'indirizzo cambiasse a monte, senza alcun percorso di
riconciliazione (D-03). Conseguenza accettata, non nascosta: una fase successiva che debba
inviare posta dovrà ottenere l'indirizzo dal provider, o dal token, al momento dell'invio.

**3. L'identificatore di `Admin` — una contraddizione interna a questo documento, risolta.**
Il cap. 10.6 contiene una contraddizione interna sull'identificatore di `Admin`: la prosa elenca
dieci classi con surrogato ed esclude esplicitamente `Admin`; il cap. 12 lo chiave per
`username {id}`; ma la tabella del cap. 10.6 (riga 1286) assegna ad `Admin` `id: UUID`.
L'implementazione risolve la contraddizione senza scegliere nessuno dei due lati: `Admin.id` è
lo stesso claim Cognito `sub` di `User.id` (D-01 AMENDED), tipizzato `String`/`VARCHAR(64)`
`ascii`, non `java.util.UUID` — AWS documenta che `sub` non è garantito in formato RFC UUID.
La riga `admin` è creata just-in-time al primo accesso autenticato che porta il gruppo Cognito
`ADMIN` (identico meccanismo del provisioning di `User`), non è mai un'origine di autorizzazione
(l'autorizzazione legge sempre l'authority derivata dal claim `cognito:groups`), e non viene mai
cancellata: la retrocessione lascia la riga al suo posto perché `Moderation.admin` è un
riferimento obbligatorio `[1..1]` che il modello richiede (D-08).

**4. Il calcolo della reputazione, ricollocato (10.1, §7.1).** Il §10.1 elimina gli attributi
derivati sulla base del fatto che il valore non viene memorizzato ma ricalcolato a ogni
richiesta. Questa proprietà è **preservata**, non abbandonata: la media e il conteggio delle
valutazioni continuano a essere calcolati a ogni richiesta, ma su una proiezione locale delle
recensioni, perché le classi su cui il §7.1 definisce `averageRating` (`Review`, `Order`,
`Listing`) appartengono a un altro servizio che `user` non può leggere direttamente (D-48). È
cambiata solo la **collocazione** del dato, imposta dall'isolamento fra servizi — non la sua
semantica: un aggregato memorizzato avrebbe abbandonato sia la semantica del §10.1 sia la sua
regola di non-memorizzazione. `completedSales` (§7.1) non è invece costruito in questa fase,
deliberatamente: l'evento da cui si deriva (`order.delivered`) non esiste finché non lo
pubblica una fase successiva (D-49).

### 13.2 Scostamenti introdotti dalla fase 2 — Catalogo, attributi e ricerca

La fase 2 realizza `Category.attributeDefinitions` come definizioni tipate ereditate dagli
antenati e `Listing.attributes` come mappa indicizzata dall'identificatore della definizione.
Le classi Java corrispondenti sono `AttributeDefinition`, `AttributeValue`,
`EffectiveSchemaResolver` e `AttributeValueValidator`.

La regola `required` è applicata al momento della pubblicazione o modifica, non come vincolo
retroattivo su ogni documento già memorizzato. Gli annunci esistenti possono risultare
incompleti dopo l'aggiunta di una definizione obbligatoria, senza essere rimossi dal catalogo.

La ricerca proietta gli attributi nello spazio `attr.*`; le faccette devono derivare dai valori
realmente presenti nell'indice, così il ritiro di un valore ENUM non rende invisibili gli
annunci storici.

L'implementazione mantiene i `Favourite` quando un annuncio passa a `REMOVED`, in contrasto con
i postcondizioni di `remove_listing` e `remove_offensive_listing`. La scelta evita di cancellare
silenziosamente la lista dell'utente e richiede alla vista dei preferiti di rappresentare
l'annuncio come non disponibile.

La memoria runtime e l'indice popolato non sono stati ancora misurati su uno stack completo:
questa è una verifica aperta, non una conformità dichiarata al budget.

### 13.3 Scostamenti introdotti dalla fase 3 - Checkout e pagamenti

La fase 3 realizza l'acquisto con PayPal Orders v2 in ambiente sandbox. Il modello dei
capitoli 10-12 resta la fonte dei nomi e dei vincoli di dominio; le scelte seguenti precisano
come il pagamento reale e i confini dei microservizi soddisfano quei vincoli.

**Pagamento e stato dell'ordine (D-03, D-20, D-21, D-22, D-26, D-28).** Il checkout crea
sincronicamente un ordine PayPal e restituisce l'`approvalUrl`. Dopo l'approvazione del
compratore, `payment-service` richiede la cattura, ma l'esito HTTP del provider non porta
`Order` a `PAID`: solo l'evento `PAYMENT.CAPTURE.COMPLETED`, ricevuto sul corpo originale e
verificato con la firma PayPal, genera `payment.succeeded`. La stessa regola vale in senso
inverso per il rimborso: l'API amministrativa restituisce `REFUND_PENDING` e solo il webhook
firmato produce `payment.refunded` e `Order.REFUNDED`. Le ripetizioni della cattura e del
webhook sono deduplicate con identificatori persistiti. `creditCard` usa il percorso guest
checkout di PayPal; il test sandbox ha osservato un modulo carta senza login, ma non ha
completato una transazione carta. Tutti gli incassi della fase arrivano al conto business
sandbox di Rebyte; i pagamenti ai venditori sono fuori dall'ambito della fase 3.

**Software e bonifico (D-03, D-20).** Un ordine software si ferma a `PAID` nella fase 3:
la creazione della `License` e la transizione a `DELIVERED` sono compiti della fase 4.
`bankTransfer` resta nel modello concettuale, ma il checkout della fase 3 lo rifiuta con
422; non esiste una conferma manuale capace di impostare `PAID` aggirando il webhook.

**Pezzo unico e compensazione (D-18, D-33).** L'unicità dell'acquisto hardware dipende
dal vincolo MySQL unico e nullable su `Order.activeListingGuard`, non da un lock Redis.
`order-service` legge prezzo e disponibilità correnti da catalog, salva l'ordine e il
guard, poi chiede la prenotazione con un `reservationId` scelto dal chiamante (l'ID
dell'ordine). La cancellazione libera il guard e scrive `order.cancelled` nello stesso
commit; catalog rilascia solo se la prenotazione ancora appartiene a quell'ID e registra
un fence che impedisce a una prenotazione tardiva di rivendere l'annuncio. Un'autorizzazione
alla cattura già concessa impedisce la cancellazione. I valori esatti di
`cancellationReason` sono `PAYMENT_DECLINED`, `TIMEOUT`, `BUYER_ABORTED`,
`PAYMENT_UNAVAILABLE` e `LISTING_UNAVAILABLE`.

**Integrazione del provider (D-44).** `payment-service` usa un `PayPalClient` concreto
per OAuth, create, capture, GET di riconciliazione, verifica firma e refund. Una porta
`PaymentGateway` con implementazioni mock/manuale/PayPal non è stata introdotta:
la fase realizza un solo provider reale, quindi quella gerarchia non avrebbe una seconda
implementazione utile. Le letture sincrone fra servizi passano da `/internal/**` via Feign;
gli eventi di stato e di rilascio passano dall'outbox a RabbitMQ.

### 13.4 Scostamenti introdotti dalla fase 3.1 - Offerte

La fase 3.1 «Offers» realizza `Offer` e l'intero suo ciclo di vita: invio, accettazione,
rifiuto, ritiro, scadenza, checkout, rilascio della prenotazione e sincronizzazione con i
cambiamenti dell'annuncio. Non è più un'estensione pianificata: gli scostamenti seguenti
registrano ciò che l'implementazione ha richiesto rispetto al capitolo 13.4 come originariamente
previsto.

**Ambito, proprietà e operazioni (capitoli 6-9).** Le offerte sono ammesse solo per hardware
(`V.Offer.hardwareOnly`), con importo positivo strettamente inferiore al prezzo richiesto al
momento dell'invio, senza soglia minima ulteriore. `make_offer`, `respond_to_offer` e
`withdraw_offer` applicano anche `V.Offer.noSelfOffer` e `V.Offer.singlePendingPerBuyer`. Il
venditore accetta o rifiuta; non sono previste controfferte. Il ritiro è ammesso solo per
offerte pendenti, e nessuna delle due parti può interrompere anticipatamente una prenotazione
`ACCEPTED`. **`Offer` appartiene a `order-service`**, con persistenza relazionale in `order_db`;
i riferimenti a utente e annuncio attraversano il confine di servizio come identificatori
(`buyerSub`, `sellerSub`, `listingId`), senza letture dirette dei database altrui — la vista e
l'autorizzazione dell'offerta usano lo snapshot preso alla creazione (`listingTitle`,
`sellerSub`, `askingPrice`), non una nuova lettura di catalog.

**Il motivo del rifiuto (D-03).** `Offer` porta `rejectionReason`, enum nullable
`{ SELLER, OTHER_ACCEPTED, LISTING_UNAVAILABLE, PRICE_CHANGED }`, valorizzato dalla transizione
che rifiuta l'offerta: `SELLER` quando il venditore rifiuta esplicitamente, `OTHER_ACCEPTED`
quando un'altra offerta pendente sullo stesso annuncio viene accettata, `LISTING_UNAVAILABLE`
quando l'annuncio lascia lo stato attivo o un tentativo di accettazione fallisce in modo
definitivo, `PRICE_CHANGED` quando un ricalcolo del prezzo scende all'altezza o sotto l'importo
offerto. Un'offerta ritirata (`WITHDRAWN`) o scaduta (`EXPIRED`) non porta `rejectionReason`,
perché non è un rifiuto.

**L'identificatore della prenotazione (D-07).** Ogni tentativo di accettazione genera un nuovo
UUID, salvato sull'offerta come `holdReservationId`; non è, come previsto originariamente,
uguale all'identificatore dell'offerta. Lo stesso identificatore viene riusato dalla
prenotazione in catalog, dal checkout che la riutilizza senza una seconda chiamata, dalla
cancellazione, dal rimborso e dalla scadenza: il fence di catalog su `canceledReservationIds`
bloccherebbe altrimenti per sempre un nuovo tentativo di accettazione della stessa offerta dopo
un primo tentativo fallito.

**La rivendicazione prima della prenotazione (D-06, D-20).** L'accettazione scrive
`holdReservationId` e `acceptClaimedAt` in una transazione breve, prima di chiamare catalog
fuori da qualunque transazione, con un solo nuovo tentativo sullo stesso identificatore. Un
esito indefinito (timeout) dopo il secondo tentativo lascia l'offerta `PENDING`, non tocca le
altre offerte pendenti, e pubblica una compensazione `offer.hold.released` per quell'
identificatore, così una prenotazione tardiva non può restare valida; il venditore riceve 503 e
può ritentare. Un esito definito di prenotazione già presa o annuncio non trovato rifiuta con
409 e porta l'offerta a `LISTING_UNAVAILABLE`. Un `expectedVersion` non più valido
(`NOT_RESERVABLE`) restituisce 409, lascia l'offerta e le altre pendenti intatte e non pubblica
alcuna compensazione, perché nulla è stato prenotato.

**La prenotazione sopravvive a un checkout annullato (D-08).** Se l'ordine creato da
un'offerta viene annullato prima del pagamento (`BUYER_ABORTED`, `PAYMENT_UNAVAILABLE`,
`TIMEOUT`, `PAYMENT_DECLINED`) mentre la prenotazione è ancora valida, la prenotazione resta e
l'ordine viene scollegato dall'offerta, così l'acquirente può ripetere il checkout fino a
`expiresAt`. L'associazione `offer_order` del capitolo 10.7 diventa quindi "al più un ordine
vivo (non annullato) per offerta, e nessuno dopo un ordine `PAID`" — la stessa restrizione che
`V.Order.availableOnly` impone lato annuncio si applica qui lato offerente.

**L'ordine vivo estende la prenotazione (D-10).** La scadenza della prenotazione salta
un'offerta il cui ordine collegato è ancora `AWAITING_PAYMENT`, lasciando che siano il timeout
dei 30 minuti dell'ordine e la riconciliazione dei pagamenti a decidere l'esito. Se l'ordine
viene annullato dopo `expiresAt`, l'offerta passa a `EXPIRED` e la prenotazione viene rilasciata.
La prenotazione può quindi superare la propria scadenza di al più una finestra di pagamento, e
un acquirente a metà del pagamento non viene mai interrotto; conversione e scadenza si
escludono a vicenda per costruzione, con due predicati di aggiornamento guardato speculari.

**Il consumo definitivo dell'offerta (D-11).** Una volta che l'ordine collegato raggiunge
`PAID`, l'offerta è consumata in modo permanente: resta `ACCEPTED` con il proprio legame
all'ordine, come nel modello, senza un nuovo stato. Un rimborso successivo rilascia l'annuncio
tramite `holdReservationId` e l'annuncio torna `ACTIVE` al prezzo di listino. L'offerta non può
convertirsi una seconda volta.

**Checkout al prezzo concordato (D-12).** Il checkout dell'offerta segue le regole del
buy-it-now: indirizzo di spedizione obbligatorio, metodo fra `paymentMethods`,
`Idempotency-Key` obbligatoria, e `expectedTotal = importo accettato + shippingCost corrente`.
Solo il prezzo unitario viene dall'offerta; catalog rifiuta modifiche e nuovi prezzi finché
l'annuncio non è `ACTIVE`, quindi la spesa di spedizione non può cambiare durante la
prenotazione. Il checkout verifica il possesso della prenotazione riusando il contratto di
prenotazione esistente.

**Rifiuti guidati dal sistema (D-01, D-02, D-05).** order-service consuma `listing.updated` e
`listing.removed` di catalog sulla propria coda con una DLQ dedicata, e non si fida mai del
payload dell'evento: rilegge sempre lo stato corrente tramite `GET /internal/listings/{id}`
prima di decidere. Quando l'annuncio lascia `ACTIVE` (un'altra vendita, il ritiro del
venditore, la rimozione amministrativa), le offerte `PENDING` non rivendicate vengono rifiutate
come `LISTING_UNAVAILABLE`; un ricalcolo del prezzo a un valore pari o inferiore a un'offerta
pendente la rifiuta come `PRICE_CHANGED`, mantenendo sempre valido l'invariante "un'offerta
pendente è sotto il prezzo corrente", non solo al momento dell'invio. Una rimozione
amministrativa di un annuncio `SOLD` sotto una prenotazione `ACCEPTED` non ancora convertita fa
scadere quell'offerta immediatamente (bloccando il checkout) senza alcun rilascio verso
catalog, perché `REMOVED` è terminale; un checkout già iniziato segue i percorsi esistenti di
cancellazione o rimborso della fase 3. Una cancellazione successiva che riporta l'annuncio ad
`ACTIVE` non fa rivivere le offerte rifiutate in questo modo.

**Il rilascio della prenotazione (D-21).** Quando un ordine derivato da un'offerta viene
annullato mentre la prenotazione è ancora valida, `order-service` pubblica comunque
`order.cancelled`, ma con `reservationId` nullo; catalog tratta un `reservationId` nullo o
vuoto come "nessun rilascio" invece di rifiutare l'evento. L'evento interno
`offer.hold.released` copre invece ogni rilascio che non appartiene a un ordine: un esito
indefinito o una transizione finale persa durante l'accettazione (D-06), e la scadenza
attraverso lo sweep o la rimozione amministrativa (D-01/D-05).

**Gli eventi e il contratto per la chat (D-17, D-18).** order-service pubblica ogni transizione
attraverso il proprio outbox esistente: `offer.created`, `offer.accepted`, `offer.rejected`,
`offer.withdrawn`, `offer.expired`, con payload `{eventId, offerId, listingId, listingTitle,
buyerSub, sellerSub, amount, askingPrice, status, rejectionReason (al rifiuto), expiresAt
(all'accettazione), occurredAt}`. A differenza degli eventi `order.*`, importo e titolo sono
inclusi perché la fase 5 dovrà renderizzare la card dell'offerta nella chat
compratore-venditore senza richiamare order-service. La fase 3.1 non costruisce né la
conversazione né la card: predispone solo il contratto che la fase 5 consumerà.

**Ripartizione fra fasi.** L'aggregato `Offer` e il suo intero ciclo di vita sono costruiti e
completati nella fase 3.1, non solo predisposti come previsto in origine. `NotificationType`
resta con `offerReceived` e `offerAccepted` (`OFFER_RECEIVED`, `OFFER_ACCEPTED` in Java), ma la
consegna delle notifiche è compito della fase 5 e le schermate delle offerte della fase 6, come
già previsto.

### 13.5 Scostamenti introdotti dalla fase 4 - Evasione, licenze e recensioni

La fase 4 chiude l'acquisto dopo `PAID`: spedizione e consegna dell'hardware, consegna
istantanea del software con la sua `License`, segnalazione e risoluzione dei problemi,
recensioni e proiezioni di reputazione. Gli identificatori `D-nn` di questa sezione sono
quelli del contesto della fase 4 e non vanno confusi con quelli delle sezioni 13.3 e 13.4. Il
modello dei capitoli 10-12 resta la fonte dei nomi di dominio; le scelte seguenti registrano
dove l'implementazione se ne discosta o lo estende.

**Spedizione e consegna dell'hardware (D-01, D-02, D-03, D-04, D-05, D-09).** Il
venditore dell'annuncio segna l'ordine `SHIPPED` tramite `POST /orders/{id}/ship`;
solo l'acquirente conferma la ricezione passando da `SHIPPED` a `DELIVERED` tramite
`POST /orders/{id}/deliver`. Queste azioni dedicate sostituiscono il `PATCH` generico
di `update_order_status` del capitolo 9.5 e ne rispettano i ruoli. Il ruolo opposto e un
amministratore che non sia il partecipante autorizzato ricevono 403, un estraneo 404.
La spedizione richiede un `carrier` (`POSTE_ITALIANE`, `BRT`, `GLS`, `SDA`, `DHL`, `UPS`,
`FEDEX`, `DPD`, `OTHER`) e un `trackingCode` di al più 64 caratteri; entrambi sono immutabili
dopo `SHIPPED`. La conferma dell'acquirente non richiede un ritardo minimo. Questa è la
soluzione temporanea in attesa dell'integrazione con i corrieri, che non è ancora presente.
La vista dell'ordine espone `paidAt`, `shippedAt`, `deliveredAt`, `carrier` e `trackingCode`
a compratore, venditore e amministratori.

**Consegna automatica e rimborso per mancata spedizione (D-06, D-07).** Due sweep di
`order-service` (`FulfilmentSweepService`) completano il ciclo. Un ordine `SHIPPED` diventa
`DELIVERED` dopo `rebyte.fulfilment.auto-deliver-after` (predefinito `P14D` da `shippedAt`),
a meno che esista una segnalazione `OPEN` o `REFUND_PENDING` o un rimborso già richiesto; il
ritiro o il rifiuto della segnalazione sblocca la consegna. Un ordine hardware ancora `PAID`
dopo `rebyte.fulfilment.ship-by-deadline` (predefinito `P7D` da `paidAt`) viene rimborsato
automaticamente con motivo `NOT_SHIPPED`, passando dallo stesso metodo di rimborso
amministrativo della fase 3; una segnalazione aperta non lo ferma. Per impedire che una
spedizione tardiva si sovrapponga a un rimborso in corso, l'ordine porta il marcatore
`refundRequestedAt`, scritto in una transazione propria prima della chiamata a
`payment-service`: finché è valorizzato, `ship` e `deliver` rispondono 409
(`order-refund-pending`). Un rifiuto definitivo del pagamento lo azzera, un esito ignoto lo
mantiene.

**Licenza emessa al pagamento, non all'acquisto (D-08, D-29).** Il consumer di
`payment.succeeded` porta un ordine software da `PAID` a `DELIVERED` nella stessa transazione
che crea la `License` e scrive gli eventi `order.paid`, `order.delivered` e `licence.issued`.
Nessun ordine software è `DELIVERED` senza licenza, in continuità con la regola della sezione
13.3. Il capitolo 9.5 già implicava la consegna istantanea in `confirm_payment`; ciò che cambia
è il punto preciso in cui nasce la licenza, cioè la conferma del pagamento. Gli ordini software
che la fase 3 aveva lasciato a `PAID` sono dati di sviluppo e sandbox e non vengono
retrodatati. La `License` ha la chiave come identificatore (come nel capitolo 10.6), un solo
record per ordine, stato `ACTIVE` o `REVOKED` e `revokedAt`.

**Il formato della chiave è definito dallo sviluppatore (D-24, D-25).** `SoftwareListing`
guadagna l'attributo `licenceKeyPattern`, validato alla pubblicazione e alla modifica. La
grammatica ammette i caratteri letterali `A-Z`, `0-9` e il trattino, più `#` per un carattere
base32 di Crockford estratto da `SecureRandom`; servono almeno 16 `#` (80 bit) e al più 64
caratteri in totale. Se manca vale `RBYT-#####-#####-#####-#####`, che copre anche gli annunci
già esistenti: catalog conserva `null` e risolve il valore predefinito in lettura. Il
modello applicato all'ordine è congelato al checkout dalla lettura interna dell'annuncio che
`POST /orders` esegue già, quindi il consumer del pagamento genera la chiave senza alcuna
chiamata Feign; una modifica successiva del modello riguarda solo gli ordini futuri.

**Artefatti e download vincolati alla licenza (D-26, D-40, D-41).** Nel modello
`License.includedVersions()` (capitolo 7.6) è osservabile solo attraverso un download.
`Version.artifactKey` non veniva mai persistita: viene ora impostata una sola volta dal
proprietario, con `POST /listings/{id}/versions/{number}/artifact/confirm` dopo il caricamento
con `artifact-upload-policy`, e le viste pubbliche espongono soltanto `artifactAvailable`.
Gli artefatti hanno un limite proprio, `rebyte.media.upload.artifact-max-size-bytes`
(predefinito 100 MiB), mentre le foto restano a 2 MiB. `includedVersions()` è realizzata da
`GET /listings/{id}/versions/{number}/download`, che restituisce un URL S3 prefirmato
a breve scadenza (120 secondi) per qualunque versione, comprese quelle rilasciate dopo
l'acquisto. catalog autorizza la richiesta con la lettura interna di `order-service`
`GET /internal/licences?buyerSub=&listingId=`, che risponde `{active}`; venditore e
amministratori scaricano senza licenza. Se `order-service` non risponde o risponde in modo
malformato la richiesta fallisce con 503 e non viene emesso alcun URL. Chi ha una licenza
`ACTIVE` conserva l'accesso dopo che il venditore ha rimosso l'annuncio; l'accesso è negato
quando esiste una rimozione di moderazione amministrativa.

**La nuova classe `OrderProblem` (D-11, D-12, D-13, D-14, D-17, D-19).** Il problema segnalato
dall'acquirente non esiste nei capitoli 6-12 ed è un'estensione: una classe separata, con
tabella `order_problem`, non colonne di `Order`. Porta `reason`, `description` (al più 2000
caratteri), `status`, `outcome`, `resolvedBy`, `resolutionNote` (al più 1000), `createdAt` e
`resolvedAt`. I valori di `ProblemReason` sono `NOT_RECEIVED`, `NOT_AS_DESCRIBED`, `DAMAGED`,
`LICENCE_NOT_WORKING` e `OTHER`; quelli di `ProblemStatus` sono `OPEN`, `REFUND_PENDING`,
`RESOLVED` e `WITHDRAWN`; quelli di `ProblemOutcome` sono `REFUND` e `DISMISS`. Esiste al più
una segnalazione per ordine per sempre (unicità su `order_id`): una segnalazione ritirata o
chiusa è definitiva. Si può segnalare un ordine `PAID`, `SHIPPED` o `DELIVERED` entro
`rebyte.fulfilment.report-window` (predefinito `P14D`) dalla consegna, software compreso;
negli altri stati la risposta è 409 (`problem-not-reportable`). Solo l'acquirente segnala e
ritira, e il ritiro è ammesso solo da `OPEN`. Il problema è incluso in `GET /orders/{id}` per
acquirente, venditore e amministratori, e una segnalazione aperta non impedisce la recensione
(D-18).

**Ruoli, esiti e stato (D-10, D-15, D-16, D-19).** Gli esiti sono `REFUND` e `DISMISS`; nessuna
transizione `PAID -> CANCELLED` viene introdotta e `CANCELLED` resta riservato agli ordini non
pagati. La formulazione di ORD-09 («annullato o rimborsato») è emendata di conseguenza. Il
venditore può solo concedere il rimborso con `POST /orders/{id}/problem/refund`; non può
archiviare una segnalazione contro di sé. L'amministratore rimborsa con
`POST /admin/problems/{id}/refund` o archivia con `POST /admin/problems/{id}/dismiss`, che
richiede una nota. Il rimborso porta `OPEN` a `REFUND_PENDING` e solo l'evento
`order.refunded`, dopo il webhook firmato, porta la segnalazione a `RESOLVED` con esito
`REFUND`; un rimborso che non si completa resta visibile come `REFUND_PENDING`. L'archiviazione
porta direttamente `OPEN` a `RESOLVED` con esito `DISMISS` e lascia invariato l'ordine. Gli
amministratori trovano il lavoro aperto in `GET /admin/problems`, paginato a cursore dal più
vecchio, con filtro `OPEN` o `REFUND_PENDING`.

**Rimborsi totali e riattivazione dell'annuncio (D-20, D-21).** I rimborsi sono sempre totali;
quelli parziali sono rinviati. La postcondizione del capitolo 9.5, secondo cui un ordine
annullato o rimborsato riporta l'annuncio hardware a `active`, per i rimborsi vale ora solo
da `PAID`; l'annullamento di un ordine non pagato resta invariato. Dopo `SHIPPED` o `DELIVERED` l'oggetto ha lasciato il venditore, l'annuncio resta
`SOLD` e, se l'oggetto torna, il venditore ne pubblica uno nuovo. Il meccanismo è nel
produttore: `order.refunded` porta `refundedFromStatus` e porta `reservationId` solo per un
rimborso da `PAID`; il consumer di catalog, che già tratta un `reservationId` nullo come
«nessun rilascio», non cambia. Il criterio 3 della roadmap è emendato in «rimborsato da
`PAID`».

**Rimborso del software e revoca (D-22).** Il rimborso di un ordine software imposta la
licenza a `REVOKED` con `revokedAt` e ne conserva il record per audit; i download rifiutano
una licenza revocata, e `licence.revoked` viene pubblicato una sola volta. Un acquisto
ripetuto dello stesso software resta attivo anche se un altro ordine è stato rimborsato,
perché il controllo è «esiste una licenza `ACTIVE`» per acquirente e annuncio.

**Recensioni immutabili che sopravvivono al rimborso (D-30, D-31, D-32, D-33, D-34, D-35,
D-43).** `Review` appartiene a `order-service`, con tabella `reviews` e unicità su `order_id`
(`V.Review.onePerOrder`). `POST /orders/{id}/review` accetta un voto intero da 0 a 5 e un
commento facoltativo di al più 2000 caratteri, rifilato, con il vuoto trattato come assente;
solo l'acquirente di un ordine `DELIVERED` può recensire, senza finestra temporale, coerente
con `reviewable()` del capitolo 7.5. Le recensioni non si modificano né si cancellano, e
`V.Review.legalReview` è verificato solo alla creazione: un rimborso successivo non tocca
`seller_review`. Le letture sono servite da `order-service`: `GET /reviews/{id}` e
`GET /users/{sub}/reviews`, quest'ultimo paginato a cursore dalla più recente. «Pubbliche»
significa leggibili da qualunque utente autenticato, non anonime; non viene aggiunto alcun
`permitAll`. I nomi degli autori arrivano da una sola chiamata per pagina a
`GET /internal/users?subs=` di `user-service` (fino a 50 utenti). Il payload di
`review.created` resta `reviewId`, `sellerSub`, `rating`, `occurredAt`.

**`completedSales` come proiezione a due marcature temporali (D-36, D-37).** Il capitolo 13
indicava per `completedSales` una vista materializzata aggiornata dagli eventi; in `user-service`
è la tabella `seller_sale(order_id, seller_sub, delivered_at, refunded_at)`, alimentata dalla
coda `user-service.order-sales` (con la sua DLQ) legata a `order.delivered` e `order.refunded`.
La riga non viene cancellata al rimborso, come D-36 prevedeva alla lettera: le due marcature
sono scritte in modo indipendente (`COALESCE`), così un rimborso visto prima della consegna
non lascia una vendita contata e la riproduzione dei messaggi è un no-op. La vendita conta
se `delivered_at` è valorizzato e `refunded_at` no. `completedSales`, `ratingAvg` e
`ratingCount` sono interrogazioni indicizzate eseguite a ogni richiesta sul profilo pubblico e
su `/internal/users`, senza colonne memorizzate e senza chiamate fra servizi.

**Grafia, percorsi ed eventi (D-38, D-39).** Percorsi ed eventi usano la grafia britannica
`licence`: `GET /licences/me` (paginato a cursore, con la chiave visibile solo all'acquirente),
`/internal/licences`, `licence.issued` e `licence.revoked`. Le classi Java e le tabelle
mantengono `License` e `licenses`, come nel modello. Il predicato morto `/licenses/**` del
gateway è sostituito da `/licences/**`. `order-service` pubblica dall'outbox l'intero ciclo:
`order.paid`, `order.shipped`, `order.delivered`, `order.problem.opened`,
`order.problem.resolved`, `licence.issued`, `licence.revoked` e `review.created`, accanto agli
esistenti `order.cancelled` e `order.refunded`. Le due chiavi dei problemi hanno tre parole,
così i binding a due parole su `order.*` non le ricevono. I payload non contengono codice di
tracciamento, descrizione della segnalazione né chiave di licenza. La consegna delle
notifiche resta compito della fase 5.

### 13.6 Scostamenti introdotti dalla fase 5 - Messaggistica e notifiche

La fase 5 realizza conversazioni, messaggi, notifiche e consegna delle email in
`messaging-service`, con database MongoDB `messaging_db` e canale STOMP su WebSocket.
Gli identificatori `D-nn` di questa sezione si riferiscono al contesto della fase 5,
non alle decisioni delle fasi precedenti. Il modello dei capitoli 10-12 resta vincolante;
le scelte seguenti ne registrano le estensioni e gli scostamenti effettivi.

**Notifiche di ordine e destinatari (D-11, D-12).** Ai tipi del modello si aggiunge
`ORDER`, con riferimento all'ordine e discriminante dell'evento. `order.paid` notifica
il venditore nell'applicazione e genera solo l'email transazionale per l'acquirente;
`order.shipped` notifica l'acquirente con `carrier` e `trackingCode`;
`order.delivered` notifica l'acquirente con invito alla recensione e, per il software,
indicazione del download pronto. `order.refunded` notifica entrambe le parti,
`order.problem.opened` il venditore e `order.problem.resolved` l'acquirente.
`order.cancelled`, `licence.issued`, `licence.revoked` e `user.developer_activated`
non hanno consumer in messaging e non generano notifiche autonome.

**Cancellazione e conservazione delle notifiche lette (D-21, D-24).** Il modello
non prevedeva la cancellazione manuale. L'utente può ora eliminare una propria
notifica letta con `DELETE /notifications/{id}`, oppure tutte le proprie notifiche
lette con `DELETE /notifications?read=true`; la cancellazione di una notifica non
letta risponde 409. La prima lettura imposta `readAt` e
`expiresAt = readAt + rebyte.notifications.read-retention`, predefinito `P30D`.
Un indice TTL su `expiresAt` rende la notifica eliminabile dopo 30 giorni dalla
lettura; l'eliminazione fisica segue il ciclo del monitor TTL. Le notifiche non lette
non hanno `expiresAt` e non scadono. Letture ripetute non spostano la prima `readAt`.

**Immagini private e messaggi senza testo (D-34, D-35).** Un messaggio può contenere
fino a tre immagini JPEG, PNG o WebP di al più 1 MiB ciascuna. Il primo messaggio
di una conversazione avviata via REST richiede testo; quelli successivi possono
contenere solo immagini. Il testo, quando presente, ha da 1 a 2000 punti di codice.
`POST /messages/image-upload-policy` genera una policy S3 prefirmata sotto il
prefisso privato `chat/`; all'invio vengono verificati proprietario, esistenza,
dimensione, tipo e mancato riutilizzo delle chiavi. Solo i due partecipanti ricevono
URL GET prefirmati, generati in lettura, con durata predefinita di 300 secondi e
senza CloudFront. Uno sweep elimina i caricamenti rimasti senza messaggio oltre
il periodo di grazia, predefinito `PT24H`. Il contenuto dei messaggi non è
modificabile né cancellabile; le marcature di lettura restano aggiornabili.

**Carte delle offerte e primo contatto (D-43, D-13).** `Message` distingue `CHAT`
da `OFFER`; quest'ultimo contiene la carta e il riferimento `offerId`.
`chat.offer-card` consuma `offer.created`, `offer.accepted`, `offer.rejected`,
`offer.withdrawn` e `offer.expired`, creando la conversazione se manca. Il mittente
è l'acquirente per creazione, ritiro e scadenza, il venditore per accettazione e
rifiuto: anche `offer.expired` arriva quindi dall'acquirente. Le carte incrementano
gli unread come gli altri messaggi e creano la relativa `MessageNotification`.
Solo creazione e accettazione producono inoltre la notifica dedicata,
rispettivamente `OFFER_RECEIVED` al venditore e `OFFER_ACCEPTED` all'acquirente;
rifiuto, ritiro e scadenza producono soltanto la carta con la sua notifica di messaggio.

**Notifica del messaggio nella stessa transazione (D-46).** Ogni `MessageNotification`
viene creata nella transazione Mongo del suo `Message`, insieme alla sequenza e ai
contatori della conversazione. Non esiste un passaggio asincrono separato per
generarla. Nei messaggi di chat anche il job email è registrato nell'outbox della
stessa transazione, senza includere il testo del messaggio; la consegna email avviene
successivamente e il suo fallimento non annulla la notifica nell'applicazione.

**Contratti degli eventi arricchiti e ordinamento delle relazioni (D-40, D-45).**
`FavouriteChangedEvent` aggiunge `eventId`, uguale all'UUID della riga outbox,
`occurredAt` e `listingTitle`; l'istante di aggiunta coincide con quello persistito
del preferito. Gli eventi di variazione del prezzo e rilascio di versione includono
`listingTitle`; il primo porta `oldPrice` e `newPrice` e un istante uguale alla
voce persistita della cronologia. Gli eventi del ciclo dell'ordine, evasione,
rimborso e problema aggiungono `listingTitle`, e quelli di evasione anche `carrier`
e `trackingCode`. Questo emenda D-31 della fase 3 e D-38 della fase 4: titolo e
tracciamento possono viaggiare nei relativi eventi, mentre importi e indirizzi
restano esclusi dagli eventi `order.*`. I nomi utente provengono da una lettura
Feign quando necessaria; gli eventi `user.*` restano privi di nomi ed email.
Le transizioni di preferito e follow portano inoltre il metadato facoltativo
`relationshipSequence`, strettamente crescente per coppia e conservato oltre la
rimozione: `favourite_transition_clock` in catalog e `follow_transition_sequence`
in user, aggiornati nella transazione del produttore. I consumer mantengono
`lastRelationshipSequence` in `favourite_projection` e `follow_state`: una sequenza
inferiore è obsoleta, la stessa sequenza con un altro `eventId` è un conflitto
destinato alla DLQ. I payload legacy senza sequenza restano compatibili; il confronto
usa l'intero `Instant`, conservato come stringa `lastEventTime` per non perdere
la precisione submillisecondo nella data BSON. A parità esatta di istante legacy
prevale la rimozione solo se lo stato non porta già una sequenza del produttore;
un evento legacy successivo conserva il massimo di sequenza già noto.

**Preferenza email, disiscrizione e soppressione (D-03, D-06, D-07).** Questi stati
non appartenevano al modello. `email_preference` conserva l'interruttore globale
`emailEnabled`, predefinito vero in assenza di documento, esposto da GET e PUT
`/notifications/email-preferences`. Le email di nuovo messaggio e nuova versione
includono una disiscrizione firmata: GET su
`/notifications/email-preferences/unsubscribe?token=` mostra la conferma senza
modificare dati, POST disattiva la preferenza dell'utente firmato. L'email di ordine
pagato resta transazionale e ignora l'opt-out. Tutti gli invii verificano indirizzo
disponibile e verificato e la lista locale `email_suppression`, alimentata da bounce
permanenti e complaint SES via SNS e SQS. I nuovi messaggi generano al più un'email
per conversazione e destinatario nella finestra `rebyte.email.new-message-window`,
predefinita `PT15M`, finché ci sono messaggi non letti. L'apertura della pagina più
recente azzera la marcatura email e avanza la generazione del destinatario senza
liberare un invio in corso; una conferma tardiva non riapre una finestra già azzerata.
La presa in carico durevole evita reinvii automatici quando l'accettazione SES è
incerta, ma non rende la consegna esterna esattamente una volta.

**Proiezione dei preferiti senza recupero storico (D-41, D-47).** messaging mantiene
`favourite_projection` per coppia `(userSub, listingId)`, alimentata solo da
`listing.favourited` e `listing.unfavourited`, e la usa per distribuire localmente
le notifiche di riduzione del prezzo. Non viene eseguito alcun backfill: i preferiti
precedenti alla fase 5 non ricevono queste notifiche finché non vengono rimossi e
aggiunti di nuovo. La rimozione o moderazione di un annuncio conserva preferiti e
notifiche esistenti, come deciso nella fase 2 D-14 e nella fase 5 D-20; solo la
rimozione effettiva della relazione elimina la sua notifica, letta o non letta.

**Significato dei totali non letti (D-25).** GET `/notifications/unread-summary`
e il push `/user/queue/unread` restituiscono `{notifications, messages}`.
`notifications` conta tutte le notifiche non lette del destinatario, comprese
quelle di messaggio, come `unread_notifications` del modello. `messages` somma i
contatori non letti delle conversazioni a cui l'utente partecipa, comprese le carte
offerta; non è il numero di conversazioni e i due totali non vanno sommati come
se fossero insiemi disgiunti. La lettura della pagina messaggi più recente, senza
cursore, marca i messaggi ricevuti fino alla sequenza più recente mostrata e le
relative notifiche, lasciando non letti quelli arrivati dopo; le pagine precedenti
non marcano letture. La lettura di una sola notifica non marca il messaggio letto.
