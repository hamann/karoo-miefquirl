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
the speed you were last using. The value is sized from `ViewConfig.textSize`, so
it matches the rest of the page at whatever grid size you give the field. No
phone, no remote, no reaching for the fan mid-effort.

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

The whole toolchain comes from `flake.nix`.

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

## The Headwind protocol

Undocumented and reverse-engineered by other people. The constants below agree
across three independent implementations — see [Credits](#credits).

| | |
|---|---|
| Service | `a026ee0c-0a7d-4ab3-97fa-f1500f9feb8b` |
| Characteristic | `a026e038-0a7d-4ab3-97fa-f1500f9feb8b` (read / write / notify) |
| Advertised name | starts with `HEADWIND` |

Every frame is exactly 4 bytes. Writes go out **without response**.

**Commands**

| Frame | Meaning |
|---|---|
| `02 ss 00 00` | set fan output to `ss` percent (0–100) |
| `04 mm 00 00` | set mode `mm` |

**Modes**

| Code | Mode |
|---|---|
| `01` | off |
| `02` | heart rate |
| `03` | speed |
| `04` | manual |
| `05` | sleep |

**Notifications** on the same characteristic

| Frame | Meaning |
|---|---|
| `FD __ ss mm` | device state: output `ss`, mode `mm` |
| `FE 02 __ ss` | acknowledgement of a speed write |
| `FE 04 __ mm` | acknowledgement of a mode write |

Three behaviours worth knowing, all handled in `FanControl.kt` and
`HeadwindLink.kt`:

- **The fan ignores a speed write unless it is already in manual mode.** So a
  `04 04` frame has to precede the first `02 ss`.
- **`FD` frames are a fixed cyclic broadcast, not a true notification.** They
  arrive about once a second whatever you do, and lag behind a command you just
  issued.
- **The `FE` acks are prompt and are the real completion signal.** A captured
  tap-to-acknowledgement sequence:

  ```
  +0ms    tap "+"
  +46ms   FD  State(OFF, 0)        <- stale, predates our write
  +144ms  FE  ModeAck(MANUAL)      <- ack of 04 04
  +150ms  FE  SpeedAck(20)         <- ack of 02 14
  +2027ms FD  State(MANUAL, 20)    <- cyclic finally catches up
  ```

  Note the stale `FD` arriving *between* the write and its acknowledgement. Two
  consequences: the displayed value and the in-ride alert are gated on the `FE`
  ack rather than on the first frame back, and the intent state is not
  resynchronised from an `FD` frame while a press is still in flight —
  otherwise that stale frame rolls the pending command back.

The fan also does not advertise its control service, so scanning filters on the
advertised *name* rather than on the service UUID.

### Scanning and battery

The missing service UUID has a cost: a `ScanFilter` can never match, so the
scan has to be unfiltered and every nearby beacon wakes the process. Left
unbounded at `SCAN_MODE_LOW_LATENCY` that would run the radio flat out for a
whole ride with the fan sitting at home — which is most rides.

So discovery runs in bounded windows instead:

| | |
|---|---|
| Scan mode | `SCAN_MODE_BALANCED` — about a quarter of the radio time |
| Window | 12 seconds |
| Gap after a miss | 5s, 15s, 30s, 60s, then 300s |

It settles at a twelve-second look every five minutes. A connection that drops
restarts discovery with a *fresh* backoff, since a fan that just vanished is
worth looking for promptly, unlike one that was never there. The numbers also
stay under Android's limit of five scan starts per thirty seconds, which
repeated short scans would otherwise trip.

### The other modes

This extension only ever uses `off` and `manual`, but the fan accepts all five.
Probed on a Headwind with no sensor paired to it:

| Mode | Response | Then |
|---|---|---|
| `02` heart rate | `FE` ack | `FD` state, **output 0** |
| `03` speed | `FE` ack | `FD` state, **output 0** |
| `04` manual | `FE` ack | `FD` state, output 25 — a remembered speed |
| `01` off | `FE` ack | `FD` state, output 0 |
| `05` sleep | `fe 04 03` — three bytes | **state broadcasts stop** |

Heart-rate and speed mode make the fan follow a sensor paired to *the fan*,
not one relayed from the head unit. With nothing paired, both simply hold the
fan at zero, which is why neither is exposed here.

Two frame shapes turned up that are not four bytes long, so `decode` reports
them as unknown and `HeadwindLink` logs their bytes:

```
fd 02 ff 01 ff 02 ff 04 ff 08 ff 10    on entering heart-rate or speed mode
fe 04 03                               response to the sleep command
```

The first is five pairs tagged `01 02 04 08 10` with `ff` values — plausibly an
unset five-zone threshold table. The second may be a rejection rather than an
acknowledgement. Neither is acted on.

Note also that entering manual mode *alone* brought the fan back at 25%, a
speed it had remembered by itself. That is why `connectWrites` always sends both
a mode frame and a speed frame rather than trusting the fan's own memory.

---

## Building

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

### Sideloading

The Karoo 3 exposes adb over USB, or over wifi once you have enabled it:

```sh
adb connect <karoo-ip>:5555
make install
```

Then on the Karoo: open **Miefquirl** once to grant Bluetooth permissions, and
enable the extension under **Settings → Extensions**. Add *Fan* to a data page
from the data-field picker. To drive the fan while riding, bind the actions to a
paired controller's buttons under **Settings → Controllers**.

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
