# Canon Remote

Application Android (Kotlin / Jetpack Compose) pour piloter un Canon EOS 90D depuis un Pixel : réglages, déclenchement, mise au point et live view.

## État : 0.4.0

Trois modes, à choisir en haut de l'onglet **Connexion** :

- **Direct** : le téléphone pilote le 90D branché en USB-C (protocole PTP).
- **Relais** : le téléphone reste branché en USB au 90D et accepte **une** télécommande à la
  fois. Il tourne en service de premier plan (notification « Relais actif », bouton
  « Arrêter ») et continue écran éteint. L'écran Appareil du relais reste utilisable.
- **Télécommande** : un second téléphone Android pilote le 90D à travers le relais, avec le
  même écran Appareil (curseurs, mise au point, live view, déclencheur).

Une coupure de liaison ne touche jamais l'appareil (réglages et live view inchangés côté
relais). La télécommande affiche « Connexion au relais perdue », retente pendant ~15 s, puis
propose « Se reconnecter ». Une commande sans réponse au moment de la coupure est signalée
(ex. « Déclenchement non confirmé — vérifie l'appareil »).

## Appairer une télécommande

Sur le relais : onglet **Connexion** → **Relais** → connecter le 90D en USB → choisir les
liaisons → **Activer le relais**. Un code d'appairage à 6 chiffres s'affiche (nouveau à
chaque démarrage du relais).

1. **Wi-Fi local** (les deux téléphones sur le même réseau, ex. la box) : sur la
   télécommande, onglet **Connexion** → **Télécommande** → toucher le relais trouvé dans
   « Relais sur le Wi-Fi » → saisir le code.
2. **Point d'accès** (sans box) : le relais crée son propre réseau Wi-Fi et affiche un
   QR code. Sur la télécommande : **Scanner un QR code** → accepter la demande de connexion
   Wi-Fi du système. Le réseau, le mot de passe et le code sont dans le QR code.
3. **Bluetooth** : sur le relais, **Rendre visible (2 min)** ; sur la télécommande,
   **Rechercher** puis toucher le relais dans « Appareils à proximité » (accepter
   l'appairage sur les deux téléphones), ou directement dans « Appareils appairés » s'il
   l'est déjà. Pas de code : l'appairage Android sert d'authentification.

**Limite Bluetooth** : le débit est trop faible pour le live view. Il est désactivé sur une
liaison Bluetooth ; l'activer affiche un message proposant de passer en Wi-Fi (réseau local
ou point d'accès du relais). Réglages, mise au point et déclenchement fonctionnent.

Autorisations demandées au moment voulu : notifications (relais), « Appareils à proximité »
(point d'accès, Bluetooth ; localisation sur Android 12).

## Écran Appareil

En haut : **Basique / Avancé**, puis le mode de la molette du boîtier (M, Av, Tv, P, B,
C1–C3, « A+ » ou « Scène » pour les modes scène) et l'état de la connexion (toucher → onglet Connexion).

**Basique**
- **Vitesse, Ouverture, ISO** : un curseur à crans par réglage, un cran par valeur proposée
  par l'appareil (points sur les valeurs principales). La valeur suit le doigt et n'est
  envoyée qu'au relâchement (ou à l'appui sur le rail) ; si l'appareil la refuse, l'affichage
  revient à sa valeur. Un réglage imposé par le mode (ex. vitesse en Av) est grisé avec
  « Auto » et une aide (« Molette du boîtier sur M ou Tv pour régler »). ISO : la première
  graduation est « Auto ».
- **Mise au point** : indicateur AF/MF de l'objectif, boutons **Proche / AF / Loin**. Proche et
  Loin demandent le live view ; AF est désactivé si l'objectif est en MF.
- **Balance des blancs** (liste) et **Live view** côte à côte.
- **Déclencher + retardateur** : le bouton rond à gauche (chronomètre, délai écrit dedans quand
  il est actif) s'ouvre en mini-roue Désactivé / 2 s / 10 s à faire glisser ; le toucher à
  nouveau la referme. Toucher le déclencheur (rond quand la roue est ouverte) referme la roue
  et déclenche, ou lance le compte à rebours : « Annuler · n », un toucher annule ; changer le
  délai l'annule aussi. Le délai est mémorisé ; le compte à rebours tourne sur le téléphone
  qui déclenche.

**Avancé** : emplacement du timelapse (bientôt).

## Paramètres

- **Apparence** : thème, couleurs dynamiques.
- **Prise de vue** : garder l'écran allumé ; vibration au déclenchement (oui) ; son au
  déclenchement (non) ; vibration à chaque cran des curseurs et du retardateur (oui) ; pas de
  mise au point de Proche / Loin : Moyen ou Grand (Grand par défaut).
- **Connexion** : mémoriser les liaisons du relais (oui) — Wi-Fi local / point d'accès /
  Bluetooth cochés au lancement suivant.
- **Diagnostic** : journal (logs) ; mode développeur (essayer l'interface sans appareil).

## Télécharger l'APK

1. Sur GitHub, ouvrir l'onglet **Actions**.
2. Ouvrir le dernier run du workflow **Android CI**.
3. Télécharger l'artefact `canon-remote-debug` et le dézipper.
4. Installer `app-debug.apk` sur le Pixel (autoriser les sources inconnues si demandé).

Les APK suivants s'installent par-dessus le précédent grâce à la clé de debug fixe.

## Lancer les tests

```
./gradlew -PskipApp=true test
```

(`-PskipApp=true` ignore le module Android `:app`, qui n'est compilé que par la CI.)

## Envoyer des logs

Dans l'app, onglet **Paramètres** → **Journal (logs)** → **Copier les logs** → coller le texte dans le message.
