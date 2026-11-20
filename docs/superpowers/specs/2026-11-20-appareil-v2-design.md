# Canon Remote — Onglet Appareil v2, mode développeur, réglages, correctif Bluetooth — Design

## Intention

Après les essais réels (90D en USB, deux téléphones en Relais/Télécommande), rendre la prise
en main des réglages d'exposition plus rapide et plus lisible, permettre de tester toute
l'interface sans appareil, et corriger la liaison Bluetooth introuvable. Le timelapse
(onglet Avancé) est un projet suivant, conçu à part.

## Objectifs

1. **Correctif Bluetooth** : la télécommande doit pouvoir trouver et appairer le relais
   depuis l'app.
2. **Onglet Appareil v2** (maquette retenue : style C « curseur à crans »,
   https://claude.ai/artifact/ULxQ1oPefZF8C187TTPqyx#artboard-Curseur).
3. **Mode développeur** pour manipuler l'interface sans appareil.
4. **Quatre réglages** : vibration au déclenchement et son, vibration à chaque cran
   (interrupteur séparé), retardateur, taille des pas de mise au point, mémorisation des
   liaisons du relais.

## Non-objectifs

- Timelapse et contenu réel de l'onglet Avancé (projet suivant ; ici un emplacement « Bientôt »).
- Pose longue en mode B, réglage de la molette de mode (mécanique, impossible à distance).
- Afficher une distance de mise au point (l'appareil ne la communique pas).

## Anti-objectifs

- Saturer la liaison (USB, relais, Bluetooth) en envoyant chaque cran traversé.
- Qu'un réglage automatique semble réglable, ou l'inverse.
- Que le mode développeur puisse être confondu avec un vrai appareil ou envoie des commandes.
- Régression du mode Direct ou des modes Relais/Télécommande.

## Approches envisagées (curseurs)

1. **`Slider` Material 3 d'Android à crans (choisie par l'utilisateur)** : composant
   natif (rail épais, poignée verticale, points), accessibilité et geste inclus ; un cran
   par valeur annoncée par l'appareil ; l'étiquette suit le doigt, la valeur part au
   relâchement (ou à l'appui direct sur le rail) ; vibration légère à chaque cran si activée.
2. Curseur dessiné à la main : fidélité au pixel, mais accessibilité, geste et entretien à
   refaire, sans gain décisif. Écartée.

## 1. Correctif Bluetooth

Cause : la télécommande ne liste que les appareils déjà appairés dans Android, et le relais
ne se rend jamais visible.
- **Relais** (section Bluetooth du relais) : bouton « Rendre visible (2 min) » →
  `BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE` (120 s), permission `BLUETOOTH_ADVERTISE`.
  Texte d'aide : « Sur la télécommande, touche Rechercher ».
- **Télécommande** (section Bluetooth) : deux listes, « Appareils appairés » (actuelle) et
  « Appareils à proximité » alimentée par un bouton « Rechercher » (`startDiscovery`,
  `ACTION_FOUND`, `BLUETOOTH_SCAN`, arrêt à la fin ou en quittant l'écran). Toucher un
  appareil non appairé lance `createBond()` (dialogue système), puis la connexion dès
  `BOND_BONDED`. Appareils sans nom ignorés.

## 2. Onglet Appareil v2

### En-tête
- À gauche : boutons segmentés **Basique / Avancé** (même style que le sélecteur de mode de
  l'onglet Connexion), à la place du nom de l'appareil.
- À droite : pastille du **mode du boîtier** (M, Av, Tv, P, B, ou libellé du mode scène),
  puis la pastille d'état de connexion existante (toucher → onglet Connexion).

### Mode du boîtier (nouvelle propriété)
- `:core` lit la propriété Canon `0xD105` (mode d'exposition) dans les événements, comme
  ISO/vitesse : P=0, Tv=1, Av=2, M=3, B=4 ; scènes / A+ / Créatif auto / Vidéo → « Auto »
  (libellés : liste libgphoto2) ; C1–C3 et valeurs inconnues → « C » / hexa.
- Verrouillages : M → rien ; Av → vitesse ; Tv → ouverture ; P → vitesse + ouverture ;
  B → vitesse affiche « Bulb » (verrouillée) ; modes scène/A+ → vitesse + ouverture + ISO ;
  C1–C3 / inconnu → rien verrouillé (l'appareil refusera si besoin, erreur affichée).
- Mode non reçu (ancien boîtier) → rien verrouillé.

### Onglet Basique
- **Trois curseurs empilés**, presque toute la largeur : Vitesse, Ouverture, ISO.
  - Carte arrondie : titre à gauche, valeur actuelle en grand à droite, `Slider` à crans
    dessous ; un cran par valeur de `available` ; points affichés sur les valeurs
    principales (diaphs/stops entiers : liste de valeurs « principales » dans `CanonValues`).
  - Glissement : l'étiquette suit, l'envoi (`set`) se fait au relâchement ; vibration de cran
    si le réglage est activé ; la valeur affichée revient à celle de l'appareil si le
    réglage échoue.
  - **Auto** : réglage verrouillé par le mode → curseur désactivé (opacité réduite) avec
    « Auto » en surimpression et une ligne d'aide (« Molette du boîtier sur M ou Tv pour
    régler ») ; la poignée suit la valeur courante si l'appareil la transmet.
  - **ISO Auto** : première graduation « Auto » (valeur Canon 0x00 si annoncée) ; sur Auto, la
    carte affiche « Auto » mais le curseur reste manipulable.
- **Mise au point** : carte avec indicateur AF/MF de l'objectif (propriété Canon `0xD108`,
  3 = MF ; sinon AF), boutons **Proche / AF / Loin** (grands crans seulement : pas réglable
  Moyen=2 ou Grand=3). Proche/Loin nécessitent le live view (inchangé, aide affichée) ; AF
  désactivé si l'objectif est en MF (« Objectif en MF »).
- **Balance des blancs** (feuille de choix existante) et **Live view** côte à côte.
- **Déclencher** (pilule) avec retardateur : désactivé / 2 s / 10 s ; pendant le compte à
  rebours le bouton affiche « Annuler · 2 » et un toucher annule. Le compte à rebours
  s'exécute sur le téléphone qui déclenche (télécommande ou direct).

### Onglet Avancé
- Carte « Timelapse — bientôt » (le projet suivant la remplacera).

## 3. Mode développeur
- Paramètres → Diagnostic → interrupteur **Mode développeur**.
- Actif seulement quand aucun appareil réel n'est connecté : l'onglet Appareil utilise alors
  un `DevCameraController : CameraController` (dans `:app`, nommé et documenté comme outil de
  test) avec des listes de valeurs réalistes du 90D, sans aucune communication.
- Bandeau en tête de l'onglet Appareil : « Mode développeur — aucune commande n'est
  envoyée », avec bouton ouvrant un panneau : choix du mode simulé (M/Av/Tv/P/B/A+), AF/MF
  simulé, live view simulé (image de test).
- Chaque commande est notée dans le journal, préfixée « [dev] ».
- Dès qu'un vrai appareil se connecte, il reprend la main (bandeau masqué).

## 4. Réglages (Paramètres)
- **Prise de vue** : Vibration au déclenchement (oui par défaut) ; Son au déclenchement (non,
  `MediaActionSound` SHUTTER_CLICK) ; Vibration à chaque cran (oui) — interrupteur séparé ;
  Retardateur (désactivé / 2 s / 10 s) ; Pas de mise au point (Moyen / Grand, défaut Grand).
- **Connexion** : Mémoriser les liaisons du relais (oui) → Wi-Fi local / Point d'accès /
  Bluetooth restaurés au lancement suivant.
- Tous persistés dans `AppSettings` (testés en JVM).

## Contraintes
- Mêmes que précédemment : Pixel 8 Pro, `minSdk 31`, `:app` compilé en CI uniquement,
  `:ptp`/`:core` testés en JVM, textes en français, Material 3 / couleurs dynamiques.
- `0xD105` et `0xD108` : codes et valeurs d'après libgphoto2, à confirmer avec les logs du 90D.

## Laissé au constructeur
Découpage exact des composables, valeurs « principales » de chaque table, intensité de
vibration, image de test du live view simulé, disposition fine du panneau développeur.
