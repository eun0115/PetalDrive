# Petal Drive

A CarPlay-style dashboard for Android with an embedded **Leaflet + OpenStreetMap** map and OSRM driving-route previews.
Landscape-only: clock/status and shortcuts appear in a left rail on phones and a bottom bar on tablets and widescreens, alongside the map card, "Where to?" card, Now Playing card, and an app-grid home screen.
All icons and colors are original; nothing from Apple is used. It is not real CarPlay and can't show up on a car screen through Apple's protocol.

## Build the APK

1. Install Android Studio (Hedgehog or newer, JDK 17 is bundled) and open this folder. Let Gradle sync.
2. No map API key or AppGallery Connect setup is required. The map tiles and route previews need an internet connection.
3. Build: **Build > Build APK(s)**, or `./gradlew assembleDebug`.
   Output: `app/build/outputs/apk/debug/app-debug.apk`
4. First launch: allow location. For the Now Playing card, tap it and enable notification access for Petal Drive.

Publishing a GitHub release builds the installable beta APK and attaches it to the release as `PetalDrive-0.1-Beta.apk`. It uses the debug signing configuration so the APK can be installed directly; this is for beta testing, not Play Store distribution.

## How navigation works

OpenStreetMap tiles are displayed with Leaflet. A compact destination text field and **Go** button sit at the top of the map; type a place and tap **Go** or the keyboard's Search action to look it up with OpenStreetMap's Nominatim geocoder. The starting point defaults to your current location. You can also long-press the search field and then the map to choose a destination point. After a destination is selected, OSRM requests a driving route and displays the route ETA and distance at the bottom of the map. Tap **Go** to start the route on the dashboard map, centered and zoomed in on your current location. While active, the app checks accurate GPS fixes for deviation from the route and requests a new OSRM route after two consecutive fixes more than 65 m off route; rerouting is rate-limited to once every 15 seconds. **End** stops following, cancels pending route updates, and clears the active route line; the destination remains available to start again. The map stays embedded in the dashboard. This is an estimated route preview with basic off-route rerouting, not turn-by-turn guidance, and it does not launch Petal Maps.

The dashboard shows battery percentage and, after you tap the signal indicator and grant Phone permission, your cellular network type and signal bars.

The app uses the public OpenStreetMap tile service, Nominatim geocoder, and OSRM demo router. Nominatim searches are explicit (not autocomplete) and rate-limited to one request per second. These public endpoints have usage policies and no availability guarantee; use hosted providers or your own services for production traffic. OpenStreetMap attribution is shown on the map. Leaflet is bundled under its BSD 2-Clause license in `app/src/main/assets/map/leaflet-license.txt`.

## Known rough edges

- Map tiles and route lookup are unavailable offline.
- Installed as launcher: Petal Drive also registers as a home app, so you can choose it as the default on an Android head unit.
