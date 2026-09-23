# karoo-miefquirl

Control a **Wahoo KICKR Headwind** fan from a **Hammerhead Karoo 3**.

Add the **Fan** data field to a data page and you get fan control on the page
itself:

```
┌─────────────────┐
│                 │
│       40%       │   <- tap to toggle off / back to this speed
│                 │
├────────┬────────┤
│   −    │    +   │   <- tap to step by 10
└────────┴────────┘
```

Step the fan with `−` and `+`, or tap the value to switch it off and back on at
the speed you were last using. No phone, no remote, no reaching for the fan
mid-effort.

The displayed value is whatever the fan has *confirmed*, not what was asked
for, so the field cannot claim a speed the fan never reached. The round trip is
about 150ms.

The same four actions — *Fan +*, *Fan −*, *Fan on/off*, *Fan off* — are also
exposed as karoo-ext *bonus actions*, so they can be bound to the buttons of a
paired controller (Di2, SRAM AXS, a BLE remote) under Settings → Controllers if
you have one.

> **On the tappable field.** The Karoo SDK has no input API for data fields —
> `ViewEvent` only flows extension → Karoo. What makes this work is that
> `ViewEmitter.updateView` takes `RemoteViews`, which the Karoo inflates in its
> own process, and RemoteViews carry `PendingIntent`s — the same mechanism that
> gives home-screen widgets working buttons. This is undocumented and not a
> guaranteed part of the SDK contract, so a future Karoo update could stop
> forwarding touches. If that happens the field still renders the value and the
> bonus actions still work.

---

## Install

Grab `miefquirl.apk` from the
[latest release](https://github.com/hamann/karoo-miefquirl/releases/latest) and
put it on the Karoo over adb:

```sh
adb connect <karoo-ip>:5555     # or just plug in over USB
adb install -r miefquirl.apk
```

Then, on the Karoo:

1. Open **Miefquirl** once and grant the Bluetooth permissions. Nothing works
   until you do — the extension cannot scan without them.
2. Enable the extension under **Settings → Extensions**.
3. Add the **Fan** field to a data page from the data-field picker.

Switch the fan on at the wall and it connects by itself, usually within a few
seconds. The field shows `OFF` until it does.

Updates are offered by the Karoo from then on: the app carries a `MANIFEST_URL`
pointing at the latest release, so there is no need to come back here.

### If it does not connect

The field keeps saying `OFF` and nothing happens when you tap:

- **Permissions.** Open the app and check it says *Connected* rather than
  *Bluetooth unavailable or not permitted*.
- **Something else has the fan.** The Headwind only advertises when nothing is
  connected to it, so a phone or head unit still holding the link keeps it
  invisible. Closing the other app is enough; the fan takes up to a minute to
  start advertising again.
- **Renamed fan.** Discovery matches on the advertised name starting with
  `HEADWIND`. If yours was renamed, it will not be found.
- **Give it time.** After a few failed attempts the extension backs off to
  looking once every five minutes, deliberately — see
  [the protocol notes](docs/headwind-protocol.md#scanning-and-what-it-costs).
  Reopening the app restarts the search immediately.

---

## How it is put together

```
android/
  headwind/          Plain Kotlin JVM library. No Android, no karoo-ext.
    Headwind.kt        4-byte BLE frame encode/decode, FanMode, Notification
    FanControl.kt      Fan state and what a button press means
    src/test/          29 tests, run on a normal JVM in about a second

  app/               The Android extension.
    MiefquirlExtension.kt  karoo-ext service, bonus actions, in-ride alerts
    HeadwindLink.kt        BLE scan/connect/write/notify
    FanSpeedDataType.kt    the data field
    MainActivity.kt        permissions + a desk-testing UI
```

The split is the useful part: `:headwind` holds everything with a decision in
it — the wire format, the speed stepping, the state machine — and has no
dependency on the Android framework or on `karoo-ext`. So `make test` needs no
emulator, no Android SDK, and no GitHub credentials, and runs in about a second.
`:app` is the part that has to talk to Android, and is kept as thin as it can be.

---

## Building

The protocol the fan speaks is written up separately in
[docs/headwind-protocol.md](docs/headwind-protocol.md) — service and
characteristic, frame layouts, timings, and the behaviours that shaped this
code.

Everything is in the flake — JDK 17, Gradle, and the Android SDK (platform 35,
build-tools 35.0.0, platform-tools/adb). Verified on `aarch64-darwin`.

```sh
nix develop
make test          # domain logic — no SDK or credentials needed
```

### One-time, for the APK: a GitHub token

`karoo-ext` is published only to GitHub Packages, and GitHub requires
authentication there **even for public packages**. The token needs the
`read:packages` scope specifically — a `repo`-scoped token returns 401.

The credential belongs to your GitHub account rather than to this project, and
the same one serves any project that pulls from GitHub Packages. So mint it at
<https://github.com/settings/tokens> and put it in `~/.gradle/gradle.properties`,
which Gradle merges into every build:

```properties
gpr.user=your-github-username
gpr.key=ghp_xxxxxxxxxxxxxxxxxxxx
```

```sh
chmod 600 ~/.gradle/gradle.properties
```

If you would rather keep it per-project, `android/local.properties` (gitignored)
is read too. CI should use the `GITHUB_ACTOR` / `GITHUB_TOKEN` environment
variables instead.

```sh
make apk           # build the debug APK
make install       # sideload onto a Karoo reachable over adb
```

### Sideloading your own build

```sh
make install
```

USB is the reliable route. Wireless debugging on the Karoo advertises itself
over mDNS but, at least on this device, refuses connections on the port it
advertises; `adb tcpip 5555` from a USB session gives you the older wireless
mode, which does work. It resets on reboot.

Then follow [Install](#install) on the device itself. Two things to know when
reinstalling repeatedly:

- Changing the `applicationId` or the extension `id` orphans any **Fan** field
  already on a data page — remove it and add it back.
- Killing the process while connected leaves the fan believing the link is
  still up. It takes about 40 seconds to notice and start advertising again.

---

## Working on the control logic

`:headwind` is pure and has no Android in it, so iterate there:

```sh
cd android && gradle :headwind:test
```

```kotlin
FanControl.plan(Fan(), FanAction.UP).writes
// [[04 04 00 00], [02 14 00 00]]   -> manual mode, then 20%

Headwind.decode(byteArrayOf(0xFD.toByte(), 0x01, 60, 0x04))
// Notification.State(mode = MANUAL, speed = 60)
```

Speed moves in steps of 10, so ten presses walk 0 → 100. Change
`FanControl.STEP` for a different grid — the tests derive the expected walk from
it, so they follow along.

---

## Status

Working end to end on a Karoo 3 (`k24`, Android 12 / API 32) against a real
KICKR Headwind:

- `:headwind` — 29 tests passing.
- Scan finds the fan by advertised name, connects, discovers the service and
  subscribes to notifications.
- Up / down / toggle step the fan and hold: the displayed value comes from the
  fan's own acknowledgement, not from what was asked for, so the field cannot
  show a speed the fan never reached. Round trip is about 150ms.
- No warnings or errors in a full session log.
- The **Fan data field** renders on a data page; `−`, `+` and tap-the-value-to-
  toggle all drive the fan.
- Each press raises a brief in-ride alert showing the new speed.
- The extension reconnects to the fan by itself after its process restarts.

Not yet exercised:

- The four bonus actions fired from a **paired controller** — the plumbing is
  the same `press()` call the settings screen uses, but `onBonusAction` itself
  has not been triggered by the Karoo System.
- Reconnect behaviour after the *fan* is powered off (only an extension restart
  has been tested).
- Release builds (`isMinifyEnabled` is off; see `app/build.gradle.kts`).

> Changing a data type between numeric and graphical leaves the old element on
> any page that already had it, frozen at its last value and painted under the
> new view. Remove the field from the page and add it back after such a change.

## Releasing

Tagging a version builds a signed APK and the `manifest.json` the Karoo reads
to offer updates, and attaches both to a GitHub release:

```sh
git tag v0.1.0 && git push origin v0.1.0
```

`MANIFEST_URL` in the manifest points at the *latest* release's assets, so it
keeps working without being edited. `tools/package-release.sh` reads the
version back out of the built APK rather than restating it, so the manifest and
the APK cannot disagree, and it refuses to package an unsigned APK — one of
those installs for nobody.

Signing material lives encrypted in `secrets.yaml`, committed to the repo and
sealed with sops to two age recipients: you, and a dedicated `github_actions`
key that exists only as the `SOPS_AGE_KEY` repository secret. That makes
`secrets.yaml` the single source of truth — rotating the key is a re-encrypt
and a commit, with nothing to re-push anywhere — and revoking CI is a matter of
dropping that recipient from `.sops.yaml` and re-encrypting.

First time:

```sh
nix develop
tools/init-signing.sh          # creates miefquirl.jks, seals it into secrets.yaml
age-keygen -o ci-age.key       # the key CI will use
gh secret set SOPS_AGE_KEY < ci-age.key
```

`init-signing.sh` generates the password itself and never prints it, so it
stays out of your shell history. The raw `.jks` and `ci-age.key` are gitignored;
the encrypted copy is the one that matters.

To build a signed release locally:

```sh
source tools/load-signing.sh
(cd android && gradle assembleRelease)
```

**Losing `secrets.yaml` and the age key together means no existing install can
ever be updated** — Android refuses an APK signed with a different key. The
encrypted file in git is what stops that being a single-machine risk.

CI needs no personal access token: the workflow grants `packages: read` so the
built-in `GITHUB_TOKEN` can fetch karoo-ext.

## Licence

Apache-2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

## Scope

Manual control only, by choice. The Headwind has its own heart-rate and speed
modes (`02` and `03`), and `FanMode` already names them, but nothing drives
them — this extension does not map Karoo ride data onto fan speed. `FanControl`
is where that would go, and `observe` already treats the fan's own modes as
"not ours" so the two would not fight.

## Credits

The protocol is not my work. It was reverse-engineered, independently, by:

- [garanj/wearwind](https://github.com/garanj/wearwind) — Wear OS, Kotlin
- [tuna-f1sh/tailwind](https://github.com/tuna-f1sh/tailwind) — nRF52 remote,
  Rust; the clearest description of the frame format
- [myanshin/headwind_control](https://github.com/myanshin/headwind_control) —
  Android, Kotlin

Built against [hammerheadnav/karoo-ext](https://github.com/hammerheadnav/karoo-ext).

Wahoo, KICKR and Headwind are trademarks of Wahoo Fitness; Hammerhead and Karoo
of Hammerhead/SRAM. This project is not affiliated with or endorsed by either.
