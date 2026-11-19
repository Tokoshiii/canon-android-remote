# Canon Remote

Application Android (Kotlin / Jetpack Compose) pour piloter un Canon EOS 90D depuis un Pixel : réglages, déclenchement, mise au point et live view.

## État : V1

- **Direct USB** : fonctionnel (protocole PTP sur câble USB).
- Connexion, Wi-Fi, Bluetooth, Relais : « Bientôt ».

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

Dans l'app, onglet **Logs** → **Copier les logs** → coller le texte dans le message.
