# Supported bikes

OpenCfMoto talks to any CFMoto dash that can show a **MotoPlay / EasyConnect pairing QR**.

**You do not need a T‑BOX.** T‑BOX is for the official CFMOTO RIDE cloud / subscription features.
OpenCfMoto only needs the dash Wi‑Fi (or Wi‑Fi Direct) QR — **US and international** markets both work.

**Quick test:** on the bike, open the phone-connection / MotoPlay screen. If you see a QR code,
OpenCfMoto can try to connect. No QR → this app cannot join that dash.

Community reports welcome in [Discord](https://discord.gg/xRt5yZy2U) so we can keep this list current.

---

## Confirmed with OpenCfMoto

Riders have projected Android Auto with these (US + international as noted):

| Model | Notes |
| --- | --- |
| **450NK** | Confirmed on a real bike: correct display fit; non-touch focus navigation works with the handlebar/menu double press |
| **800MT** (MT‑X / Explore / Explore GT) | Landscape touch (CFDL26) |
| **1000 MT‑X** | Portrait CFDL26; handlebar-primary by default |
| **800NK** (US CRCP / sdk 0.9.23.x) | Non‑touch; dual PXC heartbeat |
| **800NK Advanced** | Near-square touch (~720×712); use Screen margins for the MotoPlay pull-down |
| **450SR** (+ SR‑S / TC class) | Non‑touch CFDL16; handlebar + on-screen pad |
| **450CL‑C** / **CL‑C450** | Often Wi‑Fi Direct (P2P) — Setup → Wi‑Fi **Auto** or **P2P** |
| **150SC** (scooter) | Community-confirmed; same QR / EasyConnect path |

---

## Full list — known MotoPlay / EasyConnect dashes

If your model appears below **or** shows a pairing QR, try OpenCfMoto. Trim / year / region variants
(e.g. “Sport”, “TC”, “Explore GT”) usually share the same dash protocol when the QR is present.

### Naked (NK)

- 125NK
- 450NK
- 675NK
- 800NK
- 800NK Advanced
- 800NK Sport
- 800NK (US CRCP)

### Sport (SR)

- 300SR
- 450SR
- 450SR‑S
- 450SR TC
- 500SR VOOM
- 675SR
- 675SR‑R

### Touring / Adventure (MT)

- 450MT
- 700MT
- 700MT Adventure
- 800MT‑X
- 800MT Explore
- 800MT Explore GT
- 1000 MT‑X

### Cruiser (CL)

- 450CL‑C
- CL‑C450

### Scooter

- 150SC

### ATV / SSV (TFT dash)

- CFORCE 800 (TFT, typically 2024+)
- CFORCE 850 Touring
- CFORCE 1000 (TFT, typically 2024+)
- CFORCE 1000 Touring

### Other / regional

- U10 Pro (where the dash offers MotoPlay / EasyConnect QR)

---

## Usually no EasyConnect QR (won’t work with OpenCfMoto)

These are commonly listed **without** a MotoPlay phone-projection QR. If your unit somehow has the
QR anyway, try it and tell us.

- 800MT Sport
- 800MT Touring
- 450SR World Champion Edition
- 700CL‑X (Adventure / Heritage / Sport)
- PAPIO (and similar mini bikes without the EasyConnect projection screen)

---

## How to use this list in the app

Setup → **Supported bikes** shows the same list. Profiles you can force in Setup:

**Auto** · **Legacy** (CFDL16) · **800NK** · **800MT** · **1000 MT‑X** · **800NK Adv** · **CL‑C450**

Touch dashes → use the screen (and **Dash view**). Non‑touch / focus-mode → **Controls** + Bluetooth
handlebars.

### 450NK Apps mode

The `andpower/open-cfmoto-450nk` edition adds a parked-only **Apps** button. It launches a selected
installed app through whole-screen projection. The bike display remains non-touch. Normal Bluetooth
media keys can provide play/pause/track control when the selected app supports them; arbitrary
touch-only screens cannot be navigated from the handlebar. DRM-protected video may be black.
