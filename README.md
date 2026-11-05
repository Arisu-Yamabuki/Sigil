# Sigil for Android

Package: `org.sigil.app`  ·  Server: `https://sigil.adnand.com`

## Open and build

1. Open this folder in Android Studio (latest stable). It uses its bundled JDK for Gradle.
2. If `gradlew` is missing, run `gradle wrapper --gradle-version 9.1.0` in this folder, or let Android Studio create it.
3. Install SDK Platform 37 from SDK Manager.
4. Press Run, or build an APK with `./gradlew assembleRelease`.

## Versions

| Piece | Version |
|---|---|
| Gradle | 9.1.0 |
| Android Gradle Plugin | 8.13.0 |
| compileSdk / targetSdk | 37 |
| minSdk | 26 (Android 8.0) |
| Bytecode | Java 21 |
| Crypto | Bouncy Castle 1.78.1 (OpenPGP, RSA-3072) |

Gradle 9.1 runs on JDK 25 at most. Your system JDK 27 is fine for other tools. Gradle should use Android Studio's JDK, which is set under Settings → Build Tools → Gradle → Gradle JDK. Keep the bytecode at 21 so the app runs on every supported phone.

## How privacy works in the app

- Your private key is created or imported on the phone. It is saved to app-private storage, and the GPG passphrase protects it.
- Only the public key is uploaded to the server.
- Each message is encrypted on the phone to the recipient and to yourself. The server stores ciphertext.
- Your passphrase is held only in memory, and only while the app is unlocked. Lock or sign out to clear it.
- Decrypted messages are kept only in memory and are cleared when the chat screen stops.
- Screens are marked FLAG_SECURE, which blocks screenshots and the recents preview.
- Android backups are disabled, so keys and messages are not copied to the cloud.
- No profile pictures are supported.

## Notes

- Messages refresh every 3 seconds while a chat is open. This keeps the Android side simple and works with the current server.
- Losing your key file means losing access to past messages. Export your key from another device as a backup before you rely on Sigil.
