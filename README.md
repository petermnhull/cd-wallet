# CD Wallet

Just your Spotify library on Android: search it, tap, play. No feed, podcasts or recommendations.

**[petermnhull.github.io/cd-wallet](https://petermnhull.github.io/cd-wallet/)** has screenshots and full install steps.

## Build

Needs Spotify Premium, the Spotify app on your phone, and a [Spotify developer app](https://developer.spotify.com/dashboard)
with redirect URI `shelf://callback`, package `com.peterhull.shelf` and your debug SHA-1:

```
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android
```

1. Add `spotify.clientId=<your Client ID>` to `local.properties`.
2. Put `spotify-app-remote-release-0.8.0.aar` from [Spotify's Android SDK](https://github.com/spotify/android-sdk/releases) in `app/libs/`.
3. `./gradlew installRelease`, or open in Android Studio and press ▶.

For screenshots, `adb shell am start -n com.peterhull.shelf/.MainActivity --ez demo true` shows a made-up library (`false` to turn off).

## Licence

[AGPL-3.0](LICENSE). Not affiliated with Spotify.
