# Petal Drive

A CarPlay-style dashboard for Android that uses **Petal Maps** (Huawei Map Kit) instead of Apple/Google maps.
Landscape-only: left rail (clock, speed, shortcuts), map card, "Where to?" card, Now Playing card, and an app-grid home screen.
All icons and colors are original; nothing from Apple is used. It is not real CarPlay and can't show up on a car screen through Apple's protocol.

## Build the APK

1. Install Android Studio (Hedgehog or newer, JDK 17 is bundled) and open this folder. Let Gradle sync.
2. Set up Petal Maps (one time):
   - Create a Huawei developer account and a project + app in AppGallery Connect.
   - Use your own package name: change `applicationId` in `app/build.gradle.kts` and register the same one.
   - Run `./gradlew signingReport`, copy the SHA-256 of your debug (and later release) key, add it to the app in AppGallery Connect.
   - Enable **Map Kit** for the project, then download `agconnect-services.json` into `app/`.
   - Copy `local.properties.example` lines into `local.properties` and set `PETAL_API_KEY` to the `api_key` value from that JSON.
3. Build: **Build > Build APK(s)**, or `./gradlew assembleDebug`.
   Output: `app/build/outputs/apk/debug/app-debug.apk`
4. The device needs **HMS Core** installed for Map Kit to render the map.
5. First launch: allow location. For the Now Playing card, tap it and enable notification access for Petal Drive.

## How navigation works

Map Kit draws the map, your location, and a dropped pin (long-press the map). It does not do turn-by-turn.
**Go** hands the route to the Petal Maps app with `mapapp://navigation?saddr=…&daddr=…&type=drive`
and falls back to `https://www.petalmaps.com/navigation/…` if the app isn't installed.
"Where to?" opens a Petal Maps web search the same way.
In-app routing would need Huawei's Navigation/Directions API on top; that's a natural next step.

## Known rough edges

- Never compiled or run by the person who wrote it (no Android SDK was available). Expect to fix a typo or API-name mismatch on first sync.
- `res/raw/mapstyle_night.json` only recolors map features whose names appear in Huawei's docs, so the base map will be only partly dark.
  For a full night look, make a style in Petal Maps Studio, publish it, and put the ID in `PETAL_STYLE_ID`.
- The `mapapp://` deep link comes from a 2021 developer answer; test it against your installed Petal Maps version.
- Petal Maps coverage varies by country; check that your region is supported before relying on it.
- The map card's rounded corners may render square depending on how the SDK draws its surface.
- Installed as launcher: Petal Drive also registers as a home app, so you can choose it as the default on an Android head unit.
