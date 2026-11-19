# Canon Remote — Modes Relais et Télécommande — Design

## Intention

Piloter le Canon EOS 90D **à distance**, jusqu'aux limites de portée du Wi-Fi ou du
Bluetooth : un téléphone (le **relais**) reste branché en USB à l'appareil, un second
téléphone Android (la **télécommande**) le pilote avec la même interface que le mode
Direct. Plus tard, un navigateur (PC, iPhone…) pourra aussi piloter le relais via une
page web ; le protocole est conçu pour ça dès maintenant.

## Objectifs (cette itération)

- Choix du mode dans l'onglet Connexion : **Direct** (actuel), **Relais**, **Télécommande**.
- Relais : pilote le 90D en USB (code existant) et accepte **une** télécommande à la fois.
- Trois liaisons relais ↔ télécommande :
  1. **Wi-Fi local** (même réseau, ex. box) : relais découvert automatiquement.
  2. **Point d'accès créé par le relais** : le relais affiche un **QR code** (réseau,
     mot de passe, adresse, code d'appairage) que la télécommande scanne.
  3. **Bluetooth** (classique) : sans live view.
- Télécommande : écran Appareil identique au mode Direct (tuiles, mise au point, live
  view, déclencheur), commandes et état passant par le relais.
- **Déconnexion imprévue** : message d'erreur clair sur la télécommande, UI repassée à
  l'état déconnecté (grisée), bouton pour se reconnecter.
- **Bluetooth** : live view désactivé ; si l'utilisateur tente de l'activer, message
  expliquant la lenteur du Bluetooth et invitant à passer en Wi-Fi.

## Non-objectifs

- Page web (prévue ensuite, même protocole).
- Timelapse et autres automatismes (prévus plus tard ; le design ne doit pas les empêcher).
- Plusieurs télécommandes simultanées, pilotage par Internet / 4G, Raspberry Pi.
- Support physique, accessoires.

## Anti-objectifs

- Qu'une coupure réseau modifie l'état de l'appareil : **le relais garde l'appareil
  exactement dans son état** (réglages, live view, future prise de vue en cours).
- Un déclencheur resté enfoncé : la séquence de déclenchement est exécutée entièrement
  par le relais, une coupure ne peut pas l'interrompre à mi-chemin.
- Qu'un voisin sur le même Wi-Fi puisse prendre la main sur l'appareil.
- Croire qu'une photo est prise alors qu'elle ne l'est peut-être pas.

## Contraintes

- Mêmes contraintes que la V1 : Pixel 8 Pro, `minSdk 31`, compilation de `:app` en CI
  uniquement, `:ptp`/`:core` testables en JVM pur.
- Android impose le nom et le mot de passe d'un point d'accès créé par une app
  (`LocalOnlyHotspot`), d'où le QR code ; ce réseau n'a en général pas d'Internet.
- Débit Bluetooth insuffisant pour un live view utilisable.

## Approches envisagées

1. **Relais « tuyau »** : le relais transmet les paquets PTP bruts, la télécommande fait
   tout le pilotage. Peu de code relais, mais chaque image et chaque relecture (5/s)
   traversent le réseau (lent en limite de portée, inutilisable en Bluetooth), une
   coupure pendant un déclenchement laisse la séquence inachevée, et la page web
   devrait parler PTP. **Écartée.**
2. **Relais « intelligent » (choisie par l'utilisateur)** : le relais pilote le 90D avec
   le code existant et échange avec la télécommande des **messages de haut niveau**
   (état, commandes, images de live view poussées). Peu de trafic, l'appareil est
   protégé des coupures, la page web réutilisera les mêmes messages, et tout le
   protocole se teste sur JVM.

## Architecture

### Abstraction « appareil » (`:core`)

Nouvelle interface `CameraController` (état observable + commandes), implémentée par :
- `CanonCamera` (existant, USB direct) ;
- `RemoteCamera` (nouveau, télécommande : traduit les commandes en messages, l'état
  reçu en `CameraState`).

Le `ViewModel` et l'écran Appareil ne dépendent que de `CameraController` : l'écran ne
change pas entre Direct et Télécommande.

Surface de l'interface = ce que l'UI utilise déjà : `state: StateFlow<CameraState>`,
`liveViewFrames: StateFlow<ByteArray?>`, `set`, `shoot`, `autofocus`, `focus`,
`setLiveView`, `clearError`, `disconnect`.

### Protocole relais ↔ télécommande (`:core`, package `remote`)

- Messages typés (classe scellée), sérialisés en **JSON** (kotlinx.serialization) ;
  les images de live view sont envoyées en **binaire** (JPEG brut), jamais en JSON.
- Champ `protocol` (version, = 1) dans la poignée de main ; version différente → refus
  avec message clair.
- Poignée de main : `Hello(protocol, token, clientName)` → `Welcome(model, serial)` ou
  `Rejected(reason)` (code faux, relais occupé, version).
- Relais → télécommande : `State(CameraState)` à chaque changement (pas de
  relecture périodique), images de live view (la plus récente gagne : si la liaison est
  lente, les images en retard sont abandonnées, pas mises en file).
- Télécommande → relais : `Set(setting, value)`, `Shoot`, `Autofocus`, `Focus(step)`,
  `LiveView(on)`, chacune avec un `id` ; réponse `Result(id, ok, error)`.
- `Ping`/`Pong` toutes les 2 s ; pas de nouvelles depuis 6 s → liaison considérée
  perdue (détecte une sortie de portée même sans erreur réseau).
- Couche `MessageChannel` (envoyer un message / un JPEG, flux entrant, fermeture),
  avec deux implémentations de transport :
  - **Wi-Fi** : WebSocket (texte = JSON, binaire = JPEG) — réutilisable par la page web ;
  - **Bluetooth** : socket RFCOMM, trames `type u8 (0=JSON, 1=JPEG), longueur u32, données`.
- `RelayServer` (côté relais) : reçoit les messages, appelle `CanonCamera`, renvoie
  état et images. `RemoteCamera` (côté télécommande) : l'inverse. Les deux sont
  testés en JVM l'un contre l'autre via un `MessageChannel` en mémoire et le
  `FakeTransport` existant.

### Comportement en cas de coupure

- **Relais** : ne touche pas à l'appareil (live view laissé tel quel côté boîtier,
  réglages inchangés) ; arrête juste d'envoyer ; se remet en attente d'une télécommande ;
  affiche « Télécommande déconnectée ». La télécommande qui revient reçoit
  immédiatement l'état complet.
- **Télécommande** : message « Connexion au relais perdue » (snackbar + carte dans
  l'onglet Connexion), UI grisée, tentatives de reconnexion automatiques pendant ~15 s
  puis bouton « Se reconnecter ». *(Durée : choix de Claude.)*
- Commande envoyée sans réponse au moment de la coupure : message spécifique, ex.
  « Déclenchement non confirmé — vérifie l'appareil ».

### Liaisons

- **Wi-Fi local** : le relais annonce un service `_canonremote._tcp` (NSD / mDNS) ; la
  télécommande liste les relais trouvés ; la connexion demande le **code d'appairage à
  6 chiffres** affiché sur le relais (ou est automatique via le QR code).
- **Point d'accès** : le relais démarre un `LocalOnlyHotspot` et affiche un QR code
  `canonremote://connect?...` (SSID, mot de passe, adresse IP, port, code). La
  télécommande scanne (scanner de code Google Play services, sans permission caméra),
  rejoint le réseau via `WifiNetworkSpecifier` (dialogue système), puis se connecte.
- **Bluetooth** : le relais écoute en RFCOMM (UUID fixe de l'app) ; la télécommande
  choisit le relais dans la liste des appareils appairés (appairage système Android,
  qui sert d'authentification) ; permissions `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN`.
  Live view : interrupteur actif mais son activation ouvre une boîte de dialogue
  « Le Bluetooth est trop lent pour le live view » avec bouton « Passer en Wi-Fi »
  (→ onglet Connexion) ; le relais refuse aussi `LiveView(on)` sur une liaison Bluetooth.

### Relais en arrière-plan

Le mode Relais tourne dans un **service de premier plan** (notification
« Relais actif ») pour continuer écran éteint ou app en arrière-plan — nécessaire pour
la portée longue et pour un futur timelapse. *(Choix de Claude.)*

### Interface

- Onglet Connexion : sélecteur de mode (boutons segmentés Direct / Relais / Télécommande).
  - Relais : état USB du 90D, liaisons actives (Wi-Fi local / point d'accès / Bluetooth,
    interrupteurs), code d'appairage, QR code (point d'accès), télécommande connectée.
  - Télécommande : relais trouvés sur le Wi-Fi, bouton « Scanner un QR code »,
    relais Bluetooth appairés, état de la liaison.
- Le relais garde son écran Appareil actif (pilotage local possible en même temps).
- Textes en français.

## Laissé au constructeur

Bibliothèques exactes (Ktor serveur/client WebSocket, zxing pour générer le QR,
`play-services-code-scanner`), port, format exact des messages JSON, découpage des
fichiers, gestion fine des permissions Android, détails visuels des cartes.
