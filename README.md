# Canon Remote

Application Android (Kotlin / Jetpack Compose) pour piloter un Canon EOS 90D depuis un Pixel : réglages, déclenchement, mise au point et live view.

## État : 0.3.0

Trois modes, à choisir en haut de l'onglet **Connexion** :

- **Direct** : le téléphone pilote le 90D branché en USB-C (protocole PTP).
- **Relais** : le téléphone reste branché en USB au 90D et accepte **une** télécommande à la
  fois. Il tourne en service de premier plan (notification « Relais actif », bouton
  « Arrêter ») et continue écran éteint. L'écran Appareil du relais reste utilisable.
- **Télécommande** : un second téléphone Android pilote le 90D à travers le relais, avec le
  même écran Appareil (tuiles, mise au point, live view, déclencheur).

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
3. **Bluetooth** : appairer d'abord les deux téléphones dans les réglages Bluetooth
   d'Android. Sur la télécommande : « Relais Bluetooth appairés » → toucher le relais (pas
   de code : l'appairage Android sert d'authentification).

**Limite Bluetooth** : le débit est trop faible pour le live view. Il est désactivé sur une
liaison Bluetooth ; l'activer affiche un message proposant de passer en Wi-Fi (réseau local
ou point d'accès du relais). Réglages, mise au point et déclenchement fonctionnent.

Autorisations demandées au moment voulu : notifications (relais), « Appareils à proximité »
(point d'accès, Bluetooth ; localisation sur Android 12).

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
