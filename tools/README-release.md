# Releasing Orbit GPS

Release builds are signed with the key in `orbitgps-release.jks` (from the separate
"OrbitGPS-signing-key" zip — not in the project zip). Gradle reads its
details from `keystore.properties` in this project folder (next to `settings.gradle.kts`).
Neither file is in the project zip, on purpose — keep them private and backed up.

`keystore.properties`:
```
storeFile=C:/path/to/orbitgps-release.jks
storePassword=...
keyAlias=orbitgps
keyPassword=...
```

Then in Android Studio: Build > Generate Signed App Bundle / APK, or from a terminal
`gradlew bundleRelease` (Play Store) and `gradlew assembleRelease` (install directly).
Bump `versionCode` (and `versionName`) in `app/build.gradle.kts` for every update.

If the key is ever lost, updates to the published app become impossible.
Play App Signing (offered when you first upload) adds a safety net: Google keeps the
app-signing key and yours becomes an "upload key" that can be reset.

## Key history
- Up to 1.5: original key (SHA-256 11:12:82:7D…). Lost: it lived only in a build workspace.
- From 1.6 (Oct 18, 2026): new key, SHA-256 95:9E:20:7C:4F:7E:80:05:97:B5:E8:A4:7C:5D:BE:FC:
  4F:80:5E:06:A3:DC:76:7D:8A:96:C6:A9:C6:E5:B0:B9. Phones with 1.5 or older must uninstall
  once before installing 1.6. Nothing had been uploaded to Play yet, so the store is unaffected.

## Voice clips
`tools/make_clips.py` makes the Kokoro "af_heart" clips. Run it with an id prefix to
make only some, e.g. `python3 make_clips.py continue_for_`, then copy the .ogg files and
manifest.json from tools/clips/ into app/src/main/assets/voice/.
