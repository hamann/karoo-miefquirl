# Miefquirl

Control a **Wahoo KICKR Headwind** fan from a **Hammerhead Karoo 3**, by
tapping a data field.

```
┌─────────────────┐
│                 │
│       40%       │   <- tap to switch off, tap again to come back here
│                 │
├────────┬────────┤
│   −    │    +   │   <- 10% a tap
└────────┴────────┘
```

No phone, no remote, no reaching down for the fan mid-effort. Add the **Fan**
field to a data page and it sizes itself to whatever space you give it.

*A Miefquirl is what German calls one of these: a fug-whisker, the thing that
stirs up stale air.*

---

## Install

On a **Karoo 3**, using the Hammerhead Companion app — this is Hammerhead's
[sideloading procedure](https://support.hammerhead.io/hc/en-us/articles/31576497036827-Companion-App-Sideloading),
and no cable is involved:

1. On your **phone**, open the
   [latest release](https://github.com/hamann/karoo-miefquirl/releases/latest).
2. Long-press the link to `miefquirl.apk` and **share it with the Hammerhead
   Companion app**.
3. Your Karoo shows an install prompt. Press **Install**.
4. Open **Miefquirl** from the main menu and grant the Bluetooth permissions.
   Nothing works until you do.
5. Add the **Fan** field to a data page from the data-field picker.

Switch the fan on and it connects by itself, usually within a few seconds. The
field reads `OFF` until it does.

### Updating

Long-tap the Miefquirl icon on the main menu and choose **Update**. The app
points at its own latest release, so it finds new versions without being told
where to look.

### If it does not connect

The field stays on `OFF` and tapping does nothing:

- **Permissions.** Open the app and see whether it says *Connected* or
  *Bluetooth unavailable or not permitted*.
- **Something else has the fan.** The Headwind only advertises itself when
  nothing is connected to it, so a phone or head unit still holding the link
  keeps it invisible. Close the other app — the fan takes up to a minute to
  start advertising again.
- **A renamed fan.** Discovery looks for a device whose name starts with
  `HEADWIND`. A renamed one will not be found.
- **Give it a moment.** After a few failed attempts it deliberately slows down
  to looking once every five minutes, so as not to chew through your battery
  hunting for a fan that is not there. Reopening the app starts the search
  again immediately.

---

## Status

Working, and used on a real Karoo 3 against a real Headwind. Version 0.1.0.

What has been tested on the device:

- Finding and connecting to the fan, and reconnecting by itself afterwards
- Stepping the speed, switching off, and toggling back to the previous speed
- The data field showing the fan's confirmed speed, updating in about 150ms
- A brief on-screen confirmation on each press

What has not:

- **Driving it from a shifter button.** The four actions are also published as
  karoo-ext *bonus actions*, which the Karoo is meant to be able to bind to a
  controller. On paper that means a SRAM AXS bonus button, mapped under
  Sensors → your AXS groupset → *Configure Controls*. Whether extension actions
  actually appear in that list is unconfirmed, and there was no AXS hardware
  here to find out. Shimano Di2 is not a candidate — Hammerhead removed Di2
  integration in 2022 and its buttons cannot be reassigned.
- The fan being **switched off at the wall mid-ride**.
- Anything other than a **Karoo 3** on Android 12.

## What it does not do

Manual control only. The Headwind has its own heart-rate and speed modes, but
those follow a sensor paired to *the fan*, not anything relayed from the Karoo,
and with nothing paired they simply hold it at zero — so they are not offered.
This extension does not map your heart rate or power onto fan speed.

---

## Building it yourself

See [docs/development.md](docs/development.md). The fan's BLE protocol is
documented separately in
[docs/headwind-protocol.md](docs/headwind-protocol.md), which stands on its own
if you are writing something else that talks to a Headwind.

## Licence

Apache-2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

The protocol is not my work. It was reverse-engineered, independently, by
[wearwind](https://github.com/garanj/wearwind),
[tailwind](https://github.com/tuna-f1sh/tailwind) and
[headwind_control](https://github.com/myanshin/headwind_control). Built against
[karoo-ext](https://github.com/hammerheadnav/karoo-ext).

Wahoo, KICKR and Headwind are trademarks of Wahoo Fitness; Hammerhead and Karoo
of Hammerhead/SRAM. This project is not affiliated with or endorsed by either.
