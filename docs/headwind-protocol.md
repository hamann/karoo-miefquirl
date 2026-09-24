# The Wahoo KICKR Headwind BLE protocol

Undocumented by Wahoo. What follows is partly other people's reverse
engineering and partly what fell out of building
[karoo-miefquirl](../README.md) against a real fan.

It is written down here because it is useful on its own: anything talking to a
Headwind needs it, not just this project.

The identifiers and frame layouts were established independently by
[wearwind](https://github.com/garanj/wearwind),
[tailwind](https://github.com/tuna-f1sh/tailwind) and
[headwind_control](https://github.com/myanshin/headwind_control), which agree
with each other. The timings, the extra frame shapes and the mode behaviour
below were measured here.

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

Behaviours worth knowing before writing anything against this:

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

The fan advertises **no service UUIDs at all** — measured, not assumed:

```
found HEADWIND A86B at E5:E0:C8:E0:CA:6A; advertised services=none
```

So discovery has to match on the advertised *name*, and a `ScanFilter` is out:
`setServiceUuid` can never match, and `setDeviceName` wants an exact string
rather than a prefix. A fan that has been renamed cannot be found at all on a
first run.

## Scanning, and what it costs

The empty advertisement has a cost: a `ScanFilter` can never match, so the scan
has to be unfiltered and every nearby beacon wakes the process rather than
being rejected in the Bluetooth controller. Left
unbounded at `SCAN_MODE_LOW_LATENCY` that would run the radio flat out for a
whole ride with the fan sitting at home — which is most rides.

So discovery has to be bounded. What karoo-miefquirl settles on:

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

## The other modes

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
