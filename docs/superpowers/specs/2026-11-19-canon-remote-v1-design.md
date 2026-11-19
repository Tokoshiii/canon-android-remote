# Canon Remote — V1 (mode Direct USB, UI d'abord) — Design

## Intention

App Android native (Kotlin, Jetpack Compose, Material 3 / Material You) pour piloter
un **Canon EOS 90D** en USB via les extensions PTP de Canon. L'objectif immédiat de
l'utilisateur est de **tester l'interface sur son Pixel 8 Pro sans avoir l'appareil
sous la main** ; le pilotage réel sera validé plus tard grâce à l'écran de logs.

## Objectifs (V1)

- Écran de commandes visible et utilisable sans appareil : les commandes sont
  **affichées mais grisées** tant qu'aucun appareil n'est connecté.
- Onglet **Connexion** : état de la connexion, détection du 90D en USB OTG,
  demande de permission USB, connexion / déconnexion.
- Contrôle **basique et manuel** une fois connecté : ISO, vitesse, ouverture,
  balance des blancs, mise au point (AF + réglage fin manuel), déclenchement.
- Live view **désactivé par défaut** (laisse la place aux commandes), activable
  par un bouton.
- Onglet **Logs** : journal horodaté (dont chaque paquet PTP envoyé/reçu en hexa),
  bouton « Copier les logs », bouton « Effacer ».
- Tests unitaires sur l'encodage/décodage PTP.
- Workflow GitHub Actions qui lance les tests et produit une APK debug téléchargeable.

## Non-objectifs (V1)

- Modes Relais et Télécommande, transports Wi-Fi / Bluetooth / Raspberry Pi
  (l'architecture les prévoit, rien n'est implémenté ; ils peuvent apparaître
  dans l'onglet Connexion comme « Bientôt », désactivés).
- Simulateur / mode démo (écarté par l'utilisateur).
- EOS 7D et autres boîtiers (seul le 90D est visé ; un autre Canon EOS peut
  fonctionner par chance, sans garantie).
- Mode d'exposition, correction d'exposition, pose longue, rafale, téléchargement
  des photos, galerie.

## Anti-objectifs

- Une UI qui plante ou reste vide sans appareil branché.
- Des erreurs PTP silencieuses : tout échec doit apparaître dans les logs et,
  de façon concise, à l'écran (snackbar).
- Devoir désinstaller l'app à chaque nouvelle APK (clé de signature debug fixe).

## Contraintes

- Pas d'Android Studio chez l'utilisateur : tout se compile en CI.
- Appareil cible : Pixel 8 Pro (Android 14+). `minSdk = 31` (Android 12,
  couleurs dynamiques natives) — choix de Claude.
- Aucun boîtier disponible pour le développement : le protocole est implémenté
  d'après la documentation publique (libgphoto2 / gphoto2) et vérifié par des
  tests unitaires ; les logs servent au débogage sur le terrain.

## Approches envisagées

1. **Simulateur parlant le vrai PTP** : faux transport répondant comme un 90D.
   Teste toute la pile sans boîtier, mais plus de travail.
2. **Faux appareil haut niveau** : valeurs factices sans PTP. Rapide, mais le
   code PTP ne tourne jamais avant le premier branchement.
3. **UI à l'état « déconnecté » + onglet Connexion (choisie par l'utilisateur)** :
   l'écran de commandes s'affiche avec ses tuiles grisées ; l'onglet Connexion
   gère le branchement réel. Pas de simulateur. On teste la mise en page, les
   thèmes et la navigation sans appareil ; le protocole est couvert par les tests
   unitaires (avec un faux transport *uniquement dans les tests*).

## Architecture

Trois modules Gradle :

- `:ptp` — **Kotlin/JVM pur**, sans dépendance Android. Encodage/décodage des
  conteneurs PTP, types de données, codes d'opérations et de propriétés Canon,
  parseur des événements Canon (`GetEvent`), tables de valeurs (ISO, vitesse,
  ouverture, BdB) ↔ libellés lisibles, parseur des données de live view.
  Testable localement avec `gradle :ptp:test`.
- `:core` — **Kotlin/JVM pur** (coroutines). Interface `Transport`
  (envoyer/recevoir des octets, fermer), `PtpSession` (transactions, ID de
  transaction, phases commande/données/réponse), `CanonCamera` (ouverture de
  session, mode remote, boucle d'événements, réglages, déclenchement, AF,
  live view), état exposé en `StateFlow<CameraState>`, `LogBus` (journal en mémoire,
  borné, ex. 5000 lignes). Testé avec un faux `Transport` scripté.
- `:app` — Android. `UsbTransport` (implémente `Transport` avec `UsbDeviceConnection`,
  endpoints bulk in/out + interrupt), gestion des permissions USB, `ViewModel`,
  UI Compose.

Plus tard : `WifiTransport`, `BluetoothTransport`, mode Relais (serveur + web) et
Télécommande s'ajouteront en implémentant `Transport` / en exposant `CanonCamera`.

## Protocole (repères pour l'implémentation)

- Conteneur PTP USB, little-endian : `longueur u32, type u16 (1=commande, 2=données,
  3=réponse, 4=événement), code u16, transactionId u32, paramètres u32 × 0..5`.
- Séquence de connexion : `OpenSession (0x1002)`, `GetDeviceInfo (0x1001)` (logué),
  `Canon SetRemoteMode 0x9114 (1)`, `Canon SetEventMode 0x9115 (1)`, puis
  `Canon GetEvent 0x9116` en boucle (~200 ms) pour l'état initial et les changements.
- Événements Canon : enregistrements `taille u32, type u32, …` ; à gérer au minimum
  `0xC189` (valeur de propriété changée : code, valeur) et `0xC18A` (liste des
  valeurs disponibles changée : code, type, nombre, valeurs), terminateur `type 0`.
  Les types inconnus sont sautés (grâce à la taille) et logués.
- Réglage : `Canon SetDevicePropValueEx 0x9110`, phase données
  `taille u32 (0x0C), code u32, valeur u32`.
- Propriétés : ouverture `0xD101`, vitesse `0xD102`, ISO `0xD103`, BdB `0xD109`,
  sortie EVF `0xD1B0`.
- Déclenchement : `RemoteReleaseOn 0x9128` / `RemoteReleaseOff 0x9129`
  (séquence calquée sur libgphoto2).
- Mise au point : `DoAf 0x9154` ; réglage fin `DriveLens 0x9155`
  (pas proche 1/2/3, lointain 0x8001/0x8002/0x8003) — nécessite le live view.
- Live view : sortie EVF vers PC (`0xD1B0 = 2`), puis `GetViewFinderData 0x9153`
  en boucle ; extraire le bloc JPEG et l'afficher. Arrêt : `0xD1B0 = 0`.
- Valeurs : tables Canon de libgphoto2. Valeur inconnue → affichée en hexa
  (ex. « 0x4B »), jamais de crash. Seules les valeurs annoncées disponibles
  par l'appareil (`0xC18A`) sont proposées.

## Interface

Navigation par barre inférieure Material 3 : **Appareil · Connexion · Logs**.
Couleurs dynamiques (fond d'écran), thème clair/sombre suivant le système.

Onglet Appareil (sans live view, état par défaut) :

```
┌─────────────────────────────┐
│ Canon EOS 90D   ● Déconnecté│  ← puce d'état ; touche → onglet Connexion
├─────────────────────────────┤
│ ┌──────────┐ ┌──────────┐   │
│ │ ISO      │ │ Vitesse  │   │  ← tuiles arrondies (cartes M3)
│ │ 400      │ │ 1/250    │   │     grisées + « — » si déconnecté
│ └──────────┘ └──────────┘   │
│ ┌──────────┐ ┌──────────┐   │
│ │Ouverture │ │ Bal.blanc│   │
│ │ f/5.6    │ │ Auto     │   │
│ └──────────┘ └──────────┘   │
│ Mise au point  [AF] [◀◀][◀][▶][▶▶] │
│ [ Live view  ○ ]            │  ← interrupteur, éteint par défaut
│                             │
│   (       DÉCLENCHER      ) │  ← bouton pilule pleine largeur
└─────────────────────────────┘
```

- Toucher une tuile ouvre une **feuille inférieure** (bottom sheet) listant les
  valeurs disponibles, valeur actuelle surlignée.
- Live view activé : une carte 3:2 apparaît en haut ; les tuiles passent en
  défilement sous elle. Les boutons de mise au point manuelle ne sont actifs
  qu'avec le live view.
- Déconnecté : tout est visible mais désactivé (état `enabled = false` M3).

Onglet Connexion : carte « USB » (état : aucun appareil / appareil détecté /
permission requise / connecté, avec modèle et numéro de série issus de
GetDeviceInfo), bouton Connecter/Déconnecter. Cartes « Wi-Fi », « Bluetooth »,
« Relais » grisées avec « Bientôt ». Le branchement d'un Canon (vendor ID
`0x04A9`) peut ouvrir l'app (filtre `USB_DEVICE_ATTACHED`).

Onglet Logs : liste monospace défilante, niveau (INFO/WARN/ERR/PTP), boutons
« Copier les logs » (presse-papiers, avec en-tête : version de l'app, modèle du
téléphone, version d'Android) et « Effacer ».

## Build & CI

- Gradle wrapper commité, Kotlin DSL, catalogue de versions.
- `.github/workflows/android.yml` : sur push et pull request, JDK 17,
  `./gradlew test assembleDebug`, APK publiée en artefact (`canon-remote-debug.apk`).
- Clé debug fixe commitée (`app/debug.keystore`) pour que les APK successives
  s'installent par-dessus la précédente.
- Instructions d'installation sur le Pixel dans le README.

## Laissé au constructeur

Noms exacts des classes, bibliothèques précises (Compose BOM, coroutines),
fréquence exacte des boucles, présentation détaillée des tables de valeurs,
gestion fine des erreurs USB (déconnexion à chaud → retour à l'état déconnecté
+ log).
