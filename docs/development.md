# Developing karoo-miefquirl

For working on the extension. If you just want to use it, the
[README](../README.md) is all you need.

The fan's protocol is written up separately in
[headwind-protocol.md](headwind-protocol.md) — identifiers, frame layouts,
timings, and the behaviours that shaped this code.

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

### Sideloading your own build

```sh
make install
```

USB is the reliable route. Wireless debugging on the Karoo advertises itself
over mDNS but, at least on this device, refuses connections on the port it
advertises; `adb tcpip 5555` from a USB session gives you the older wireless
mode, which does work. It resets on reboot.

Then, on the device, grant the Bluetooth permissions on first launch and add
the **Fan** field to a data page — steps 4 and 5 of
[Install](../README.md#install). The companion-app route described there is for
users; over adb you do not need it.

Two things to know when reinstalling repeatedly:

- Changing the `applicationId` or the extension `id` orphans any **Fan** field
  already on a data page — remove it and add it back.
- Killing the process while connected leaves the fan believing the link is
  still up. It takes about 40 seconds to notice and start advertising again.

---

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

---

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

---

## Why the data field is tappable at all

The Karoo SDK has no input API for data fields: `ViewEvent` only flows
extension → Karoo. What makes taps work is that `ViewEmitter.updateView` takes
`RemoteViews`, which the Karoo inflates in its own process, and RemoteViews
carry `PendingIntent`s — the same mechanism that gives home-screen widgets
working buttons. `FanActionReceiver` is the other end of those intents, and it
stays unexported because a PendingIntent fires with the creating app's identity.

This is undocumented and not part of the SDK contract. A future Karoo update
could stop forwarding touches to the field, in which case it degrades to a
read-only display and the bonus actions keep working.

The value shown is gated on the fan's acknowledgement rather than on the
request, and intent is tracked separately from confirmed state, so that rapid
taps accumulate and a stale cyclic state frame cannot roll a pending command
back. [headwind-protocol.md](headwind-protocol.md) has the packet capture that
forced that design.
