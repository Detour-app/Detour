# Documentation

Everything that isn't the [README](../README.md) (what the app does) or
[CONTRIBUTING](../CONTRIBUTING.md) (how to build and change it).

## Design and behaviour

| Document | Read it when |
| --- | --- |
| [BACKEND_SPEC.md](BACKEND_SPEC.md) | Changing the server. Behaviour and rules, no code. Backend comments cite its sections as `spec §11`, so its numbering is stable. |
| [CIRCLES_AND_CONVOYS.md](CIRCLES_AND_CONVOYS.md) | Touching groups or the live socket. Includes the wire format of every relay frame. Cited by section number from both apps. |
| [IOS_PORT.md](IOS_PORT.md) | Working on iOS, or wondering why a behaviour differs between platforms. Also lists what is not ported — sign-in above all. |

## Platforms and hardware

| Document | Read it when |
| --- | --- |
| [ANDROID_AUTO.md](ANDROID_AUTO.md) | Working on the car screen — including why a sideloaded build never appears in a real head unit. |
| [WAVESHARE_DISPLAY_SETUP.md](WAVESHARE_DISPLAY_SETUP.md) | Building the handlebar BLE display, or implementing its side of the protocol. |
| [DEBUG_INTENTS.md](DEBUG_INTENTS.md) | Exercising behaviour that would otherwise mean going for a drive. |

## Shipping

| Document | Read it when |
| --- | --- |
| [RELEASING.md](RELEASING.md) | Cutting a release, or a Play upload was rejected. Versioning, signing secrets, the two Play tracks. |
| [STORE_LISTING.md](STORE_LISTING.md) | Filling in the Play Console listing and App content answers. |
| [PLAY_LOCATION_DECLARATION.md](PLAY_LOCATION_DECLARATION.md) | Declaring background location and foreground-service types, and recording the videos Play requires. |
| [privacy.html](privacy.html) | The published privacy policy. Change it whenever what leaves the device changes. |

## Running a server

Those live next to what they describe:

| Document | Covers |
| --- | --- |
| [../backend/README.md](../backend/README.md) | The service: running it, testing it, the conventions to know before editing |
| [../backend/INSTALL.md](../backend/INSTALL.md) | Standing it up somewhere real, and what every configuration key means |
| [../docker/dev/README.md](../docker/dev/README.md) | The local stack: ports, the realm, dev credentials |
| [../docker/dev/config/keycloak/REALM.md](../docker/dev/config/keycloak/REALM.md) | Why the realm is configured the way it is |
| [../docker/prod/README.md](../docker/prod/README.md) | The production stack, and the realm you must create yourself |
| [../docker/prod/CLOUDFLARE.md](../docker/prod/CLOUDFLARE.md) | Exposing it through a tunnel — and the one hostname that must never sit behind Access |
| [../bruno/README.md](../bruno/README.md) | Poking at every endpoint by hand |
| [../server/homeassistant/README.md](../server/homeassistant/README.md) | Rides, totals and badges as Home Assistant entities |

## Elsewhere in the repo

- [../SECURITY.md](../SECURITY.md) — what is in scope, and how to report privately.
- [../FUTURE.md](../FUTURE.md) — what is not built, and one plan that was dropped on purpose.
- `play/`, `screenshots/` — assets for the store listing and the README.
