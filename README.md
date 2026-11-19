# Manual Preconditioning (HMG-API mod)

Start and stop battery conditioning by hand on a Gen5W E-GMP car, with no navigation
route: from an app-menu tile, by holding the TUNE knob, or from a star key. Made for the
**Hyundai Ioniq 6 (CEEV) on 260903**, and offered on the other E-GMP head units HMG-API
maps (Ioniq 5 `NEEV`/`NEEV23`/`NEEV23SE`, EV6 `CVEV`).

> **Not yet run on a car.** The app compiles against Android 4.4 (API 19) and the mod
> validates against HMG-API's rules, but nobody has pressed Start in an Ioniq 6 with it.
> You use it at your own risk. Not affiliated with Hyundai, Kia, HMG-API or Launchpad.

## How it works

It sends nothing to the car itself. It asks [HMG-API](https://github.com/Open-HMG/HMG-API)'s
reviewed `conditioning` command, the same one AwsomeFox's `egmp-aa-ev` uses for its automatic
starts. HMG-API then does the car side as Launchpad does:

- **The start:** it sends route values of 9 km / 18 min, then the "routed to a station"
  event (`IHEVService` `setBattCondRouteDis`, `setBattCondRemainTime`, `setBattCondEvStation`).
  It finds these on each car by method name, from its hook map. The Ioniq 6 USA 260903 map
  has all of them and was verified on a car.
- **The checks:** HMG-API refuses a start when:
  - the car isn't READY;
  - Battery Conditioning is off in the EV settings;
  - the car is already conditioning on its own;
  - the pack is warm (20 °C / 68 °F on the Ioniq 6). This one can be overridden: **Start anyway**.
- **The timeout:** HMG-API stops the request if the car's indicator isn't on within 2 minutes
  (5 on the EV6).
- **Ending:** conditioning stops when the drive ends or when this mod is turned off.

A manual **Stop** wins over automatic starts. After one, `egmp-aa-ev` won't precondition
again until the next manual start (HMG-API's rule).

## Controls

| Where | What it does |
|---|---|
| **Precondition** tile in the app menu | The screen. It shows the state, the coldest pack temperature, READY, the Battery Conditioning setting, the indicator and charge, with **Start** and **Stop**. |
| Hold **TUNE** (about 1 s) | Opens the screen by default. Setting *Holding the TUNE knob* can make it start/stop instead, or turn it off. A short press is still the car's. |
| Hold the **steering wheel ☆** | Off by default. It can open the screen or start/stop. |
| The car's **star key menu** | Two new entries: "Battery conditioning: start / stop" and "Battery conditioning: open". Pick one there for a short press of the ☆ key. |

With start/stop on a button, the answer comes as the car's own notice. If the pack is warm,
the notice says so, and the same press again within 15 s sends the start anyway. After a start,
a notice says when the car confirms (usually within about a minute), times out, or ends it.
Setting *Notices* turns those off.

**The first Start:** HMG-API puts a question on screen asking whether this mod may use
`conditioning`. Allow it, then start again.

## Before you rely on it

On one 2022 Ioniq 5 on 260903, the car reports Battery Conditioning as not enabled even
with Winter Mode on, so every start is refused. Check yours first: open the screen with the
car in READY. "Battery Conditioning (EV settings)" should say **On**. Or check over adb:

```sh
hmgapid vehicle hv.ready hv.conditioning hv.battery_temp_min hv.conditioning_active
hmgapid vehicle-do conditioning start      # as the owner: no mod needed
hmgapid vehicle conditioning.state         # waiting, then active within about a minute
hmgapid vehicle-do conditioning stop
```

`hv.conditioning` must read `2`. `hv.battery_temp_min` should be under 20 °C. A warm pack is
the most common reason the car doesn't heat.

## Getting the package

The APK is built by HMG-API's own build, with Google's Android SDK, on GitHub Actions:

1. Push. The **CI** workflow (HMG-API's reusable mod workflow) builds the APK, runs
   `manual-precondition/test.sh`, and uploads **mod-test-packages**:
   - `manual-precondition-0.1.0.zip`: the HMG-API package, for a USB stick's `HMGAPI/updates/`
     or adb;
   - `manual-precondition-0.1.0-nemesis.zip`: unzip it onto an empty stick for NEMESIS.

   These are signed with a throwaway key. Remove the mod before installing a later test build.
2. For releases you can update in place, make an author key once:
   `java -cp "$HMGAPI_SDK/lib/hmgapi-engine.jar" hmgapi.engine.Signing keygen author.key`.
   Store its contents as the repository secret `HMGAPI_AUTHOR_KEY`, then publish a GitHub
   release tagged `v0.1.0`. The **Release** workflow signs the packages and attaches them.

On your own machine, with the HMG-API mod SDK in `$HMGAPI_SDK` and the Android SDK in
`ANDROID_HOME`:

```sh
sh $HMGAPI_SDK/bin/build-mod.sh manual-precondition out/build/manual-precondition
HMGAPI_MOD_BUILD=$PWD/out/build/manual-precondition sh manual-precondition/test.sh
sh $HMGAPI_SDK/bin/mkmodpkg.sh manual-precondition author.key
```

`hmgapi-sdk.json` and the workflow ref in `ci.yml` pin the same HMG-API commit. Move both
together, to `{"version": "1.1.0", "sha256": "…"}` and `@v1.1.0` once that release is out.

## Layout

```
manual-precondition/
  mod.json            the tile, star key entries, TUNE/☆ bindings, readings, the conditioning command
  settings.json       TUNE hold, ☆ hold, notices, temperature units (the HMG-API app's settings page)
  icons/              the tile in the stock style (as HMG-API's tools/mkicon.sh makes it)
  test.sh             off-car test (the CI runs it)
  app/                the APK: AndroidManifest.xml, res/, java/dev/manualprecond/
    MainActivity      the screen
    ToggleReceiver    start/stop from a button or the star key menu
    WatchService      the notices after a start
    Precond           the HMG-API calls
```

## Things to check in the car

- **TUNE on the Ioniq 6.** HMG-API binds `tune_enter`, the car's `KEYCODE_TUNE_ENTER`.
  Launchpad's porting kit recorded the CEEV's TUNE hold on its main input device. That's
  expected to be the same key, but confirm it: hold TUNE and the screen should open. If it
  doesn't, `hmgapid service-log hmgapi` says what the input reader saw.
- **Is activation reported?** On 260903, see whether `active` arrives and the car's battery
  icon shows conditioning.
- **The start/stop broadcast** (`dev.manualprecond.TOGGLE`) is open to other apps on the head
  unit, because the star key menu sends it without a target. HMG-API's checks apply to every
  start whoever sends it, and HMG-API logs each command with the mod that sent it.

## Credits

- [HMG-API](https://github.com/Open-HMG/HMG-API): the `conditioning` command, its checks and
  the hook maps.
- [gen5w-preconditioning (Launchpad)](https://github.com/paytondevs/gen5w-preconditioning):
  the command profile, the warm thresholds and the timeouts that HMG-API's command follows.
- [egmp-aa-ev-hmg-api](https://github.com/AwsomeFox/egmp-aa-ev-hmg-api): the automatic,
  route-based counterpart.

License: AGPL-3.0 (it bundles HMG-API's client).
