# Sigil for Android

Package: `org.sigil.app`  ·  Server: `https://sigil.adnand.com`

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
