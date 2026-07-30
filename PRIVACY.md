# OpenCfMoto — Privacy & Permissions

_Last updated: 2026-07-30_

OpenCfMoto is a **local-first** app. It connects your phone to a CFMoto MotoPlay
dashboard (T-Box) over the bike's own Wi-Fi and projects Android Auto or your
screen to the dash. It has **no user accounts and no ads**. Bike profiles, trip
logs, and full diagnostic logs stay on your phone unless *you* choose to share
them.

This document explains what the app can access, why, and where data goes.

---

## Summary

- **No account, no sign-in.** The app never asks who you are.
- **Anonymous telemetry is off by default in the 450NK edition.** This build is
  compiled without an active telemetry endpoint, so it does not upload install,
  crash, or connection reports to the project.
- **Your ride data stays on the device** (bike profiles, trip GPS logs).
- Other traffic that leaves the phone (not to us as “who you are”):
  - **Map tiles** for the trip map are fetched from **OpenStreetMap** servers.
  - **Android Auto** content (Maps/Waze/media) is handled by Google's Android
    Auto app under Google's own terms — OpenCfMoto only relays its video to the
    dash.

---

## Permissions

### Requested while using the app

| Permission | When | Why | What it does *not* mean |
| --- | --- | --- | --- |
| **Camera** | When you scan a bike's pairing QR code | Reads the T-Box QR shown on the dash | Not needed for normal streaming; frames are not recorded or uploaded |
| **Location (fine)** | With Wi-Fi actions, and while recording a trip | Android requires location permission for Wi-Fi scanning/join; **and** the trip computer reads GPS to record your ride | See "Trip logging" below — this is the one feature that *does* record location, locally and only when enabled |
| **Nearby Wi-Fi / network state** | When connecting to a saved or new bike | Finds and requests the bike's Wi-Fi access point and talks to the local T-Box | Not used to access unrelated networks or the internet on your behalf |
| **Bluetooth (scan/connect)** | On the Setup screen's Bluetooth helper | Reports whether the phone is paired to the bike so the handlebar media/call buttons work | Declared `neverForLocation`; not used to derive your location |
| **Notifications** | When starting mirroring / Android Auto (Android 13+) | Shows the required foreground-service status with controls to stop the session | Not telemetry; notifications stay on the phone |

### System confirmations and optional access

| Access | When | Why |
| --- | --- | --- |
| **Screen-capture approval** | Every time you start mirroring or Apps mode | Android requires you to approve capturing the display; the app cannot approve this silently |
| **Display over other apps** *(optional)* | Only if you enable seamless auto-resume | Lets the foreground service relaunch Android Auto after a long stop with the screen off. If not granted, you get a tap-to-resume notification instead |

### Technical permissions granted by Android

Internet and network-state access, Wi-Fi state/change, Wi-Fi multicast (for
NSD/mDNS discovery of the dash), foreground-service types (media projection,
connected device, location), and a wake lock. These keep the local T-Box
connection and the active projection alive. The manifest also declares package
visibility for Android Auto, Google Play services, and supported Apps-mode
launchers so the app can detect installed components. This does not grant access
to your accounts or private app data.

---

## Data the app stores on your phone

| Data | Where | Notes |
| --- | --- | --- |
| **Bike profiles** — SSID, Wi-Fi password, QR metadata, display name | App-private storage (`SharedPreferences`) | Stored in the app's sandbox on the device. It is **not** transmitted anywhere by the app. It is currently stored in plaintext within app-private storage (encryption at rest is a planned improvement); anyone with a rooted device or a full device backup could read it. |
| **Trip logs** — GPS route points, distance, duration, max/avg speed, timestamps | App-private storage | Created only when *Log trips automatically* is on, or when you start a manual recording. Viewable under **Trip ▸ Saved trips**, shown on a map, and deletable by long-press. Never uploaded by the app. |
| **Diagnostic log** | In-memory / app-private storage | A rolling technical log for troubleshooting. Shared **only** when you tap **Logs ▸ Share** and pick a destination yourself. |

### Trip logging (location history)

Unlike a pure connection app, OpenCfMoto includes a GPS **trip computer** that
**does record a location history** — your route and speed — while a trip is
being logged. This is a deliberate feature, it is **on-device only**, and you
control it:

- It records when *Log trips automatically* is enabled (Setup ▸ Startup &
  recovery) during a projection session, or when you press Start on the Trip
  screen.
- Trips are stored locally and can be deleted individually.
- The route data is never sent to the project or any analytics service.

---

## Anonymous telemetry code

The upstream OpenCfMoto 2.0.6 code includes an optional anonymous telemetry
client. In this independent 450NK build:

- the preference defaults to **Off**;
- the compiled telemetry endpoint is empty;
- no installation, crash, GPS, Wi-Fi, account, camera, or TFT data is uploaded.

The compatibility switch may still be visible in Setup because the screen is
shared with upstream. Leaving it off is recommended. With no endpoint compiled
into this edition, enabling the switch cannot transmit data to the upstream
service.

## Data that leaves the phone (other)

- **OpenStreetMap tiles:** Opening a trip's map downloads map imagery from
  OpenStreetMap tile servers for the area of your route. Those requests reach
  OSM's infrastructure and are subject to OpenStreetMap's own policies. Map data
  is © OpenStreetMap contributors (ODbL).
- **Android Auto:** Navigation and media run inside Google's Android Auto app;
  OpenCfMoto only receives and relays its video/audio to the dash. Anything
  Android Auto or its apps send is governed by Google's and those apps' terms.
- **Apps mode:** The selected application runs normally on the phone. OpenCfMoto
  captures the display frames approved through Android's MediaProjection prompt
  and sends them only to the bike's local EasyConn/MotoPlay connection. Apps may
  make their own network requests under their own privacy policies.
- The **bike's Wi-Fi** is a local display transport and may have no internet.
  The app keeps it separate from your normal connectivity where Android allows.

---

## If a permission is denied

The app still opens. Only the related feature is unavailable: without **Camera**,
enter the bike Wi-Fi manually or reuse a saved bike; without **Nearby
Wi-Fi/Location**, the dash can't be discovered or joined; without
**Notifications**, projection can't run as a managed foreground session; without
**screen-capture approval**, mirroring and Apps mode can't start; without
**Location while logging**, trips won't record a route. Optional overlay
seamless-resume simply stays off unless granted.

---

## Contact

OpenCfMoto is an independent, unofficial project. Questions about privacy or
licensing: <https://github.com/andpower/open-cfmoto-450nk>.

CFMOTO, MotoPlay, EasyConn, Android Auto, and Google are trademarks of their
respective owners; this project is not affiliated with or endorsed by them.
