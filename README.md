# Advanced Powerwall Manager

A Hubitat Elevation app that consolidates Tesla Powerwall management into a single, event-driven application. Replaces five separate Rule Machine rules with solar-aware logic that adjusts the Powerwall charge target continuously from your tariff periods, the solar still to come, and measured house load.

**Current version: 4.5.0** · Written and tested against a **Tesla Powerwall 2** (DarwinsDen integration), **Solcast_dual**, a **Fronius** inverter, **OpenWeather Alerts** and **Weather Underground**, on an Australian time-of-use tariff.

---

## Prerequisites

### Hub

Hubitat Elevation on firmware that supports `createGlobalVar()` (2.3.x or later). On older firmware the app still works, but you will need to create the two hub variables manually — see [Hub Variables](#hub-variables).

### Tariff

A **time-of-use electricity tariff**. The entire premise of the app is that electricity costs different amounts at different times of day; on a flat-rate tariff there is nothing for it to optimise.

You describe the plan as tariff periods — see [Tariff Periods](#tariff-periods). A plan with a zero-cost window (Victoria's "midday saver" and equivalents) is supported as a period with a rate of 0.

### Tested configuration

This app has been written against, and only tested with, the following stack. Substitutions are possible — the app depends on attribute and command names, not on specific drivers — but nothing else has been verified.

| Role | Integration | Where to get it |
|------|-------------|-----------------|
| Powerwall | **Tesla Powerwall 2** integration by DarwinsDen | [`tesla-powerwall-manager.groovy`](tesla-powerwall-manager.groovy) + [`tesla-powerwall.groovy`](tesla-powerwall.groovy) |
| Solar forecast | **Solcast_dual** by Alan F ([upstream](https://github.com/youzer-name/Solcast_dual)) | [`Solcast_dual.groovy`](Solcast_dual.groovy) |
| Solar generation | **Fronius Solar Inverter** | [`Fronius_Solar_Inverter.groovy`](Fronius_Solar_Inverter.groovy) |
| Weather alerts | **OpenWeather Alerts** driver | Hubitat Package Manager |
| Weather station | **Weather Underground** driver | Hubitat Package Manager |

The first three are **included in this repository** — use these copies, since they are the versions the app has been tested against. The two weather drivers come from HPM.

If you are on a **Powerwall 3** or a different Powerwall driver, check the command contract below before assuming it will work.

### Powerwall — Tesla Powerwall 2 (DarwinsDen)

DarwinsDen's integration is two files, both [included here](tesla-powerwall-manager.groovy): the manager app signs in to your Tesla account and creates the Powerwall device, which runs the driver. Install both.

The app assumes **13.5 kWh usable capacity per unit** and a **99% charge ceiling in Backup-Only mode**, both of which are Powerwall 2 behaviour. The 99% ceiling is why the charge target is capped there — a target of 100% could never be satisfied and would leave the Powerwall charging indefinitely.

Substituting a different driver requires these to be present:

| Type | Name | Used for |
|------|------|----------|
| Attribute | `currentOpState` | Reading the current mode (`Self-Powered` / `Backup-Only`) |
| Attribute | `battery` | State of charge % |
| Attribute | `loadPower` | House consumption in watts — the measured load term in the target |
| Command | `setSelfPoweredMode()` | Returning to normal operation |
| Command | `setBackupOnlyMode()` | Charging from grid / holding charge |

> **Grid charging must be permitted on your Powerwall.** The app charges by placing the Powerwall in Backup-Only, which draws from the grid to hold its backup reserve. If grid charging is disabled in your Tesla app or by your installer, the app will set modes correctly but nothing will actually charge.

### Solar forecast — Solcast_dual (optional, recommended)

Requires a [Solcast](https://solcast.com) hobbyist account (free tier) with your rooftop site configured. Use the [copy in this repository](Solcast_dual.groovy) rather than upstream.

The driver is built for two arrays and requires **both** site resource IDs — if you have a single array, enter the same resource ID for site A and site B. The app reads the **combined, unsuffixed** attributes rather than the per-site `_a` / `_b` variants:

- `24_Hour_Estimate`
- `24_Hour_Estimate_Low`
- `24_Hour_Estimate_High`

These are **whole-of-day totals for the current day**, used exactly as published — no reconstruction, no adding back generation already banked. Tomorrow is a separate question, answered by the `48_Hour_Estimate` family, which this app does not read.

Each poll is a fresh prediction for the same day, not a live measurement. Solcast ingests satellite imagery, so an early-afternoon prediction of the afternoon is better informed than a breakfast-time one. The app stores **two** copies per day and uses them for different things — see [Two forecasts a day](#two-forecasts-a-day).

Without a forecast device the target cannot be computed and falls back to 99% — safe, but it will charge more than necessary.

### Solar generation — Fronius Solar Inverter (optional)

The [bundled driver](Fronius_Solar_Inverter.groovy) polls the inverter directly over your local network — no cloud account needed. Configure it with the inverter's IP address, port (default 80) and inverter number (typically 1). It provides two attributes the app uses: `energy` as **daily cumulative kWh, resetting at midnight** (the trend analysis), and `power` in watts (the live correction to remaining solar).

> **GEN24 inverters:** the driver's *GEN24 Compatibility Mode* disables daily and yearly energy tracking. With it enabled the trend analysis has no daily figure to work from and cannot run. If you are on a GEN24, expect the app to stay on the Mid estimate.

> **Watch out when assigning this device.** Both the Solcast and Fronius devices present `capability.energyMeter`, so both appear in the picker — and `Solcast_dual` also exposes an `energy` attribute. Selecting the forecast device here would silently break the trend analysis. Assign the Fronius inverter as *Solar Generation Device* and Solcast as *Solar Forecast Device*.

If a substituted meter turns out to be a lifetime counter rather than a daily one, the app detects the implausible projection, logs a warning and falls back to the Mid estimate rather than acting on bad data.

Without this device the trend analysis cannot run and the Mid estimate is always used.

### Weather — OpenWeather Alerts and Weather Underground

Both are installable through **Hubitat Package Manager**.

- **OpenWeather Alerts** — required. Supplies `alertDescrFull` (full alert text, matched against your region and keywords) and `forecastHigh` (today's forecast maximum, used by the hot-day and extreme-weather checks).
- **Weather Underground** — optional. Supplies `temperature` for daily maximum tracking, which feeds the extreme-heat fallback. Without it the app falls back to OpenWeather's `temperatureMaximum`.

### Grid presence sensor

A **virtual presence sensor** reflecting mains grid state: `present` when the grid is up, `not present` during an outage. Create one under **Devices → Add Virtual Device → Virtual Presence**.

The app only *reads* this sensor — something else has to drive it. Typically that is a Rule Machine rule watching the grid status reported by the Powerwall integration, or a network ping sensor targeting something outside your house. Without a working source the outage response will never fire, though nothing else is affected.

---

## Tariff Periods

You describe your electricity plan as it appears on your bill, and the app derives everything else from it. Between 1 and 8 periods, each with:

| Field | Meaning |
|-------|---------|
| **Type** | Super Off-Peak / Off-Peak / Shoulder / **Peak** |
| **Start / End** | May wrap midnight — 9pm–11am is an ordinary Australian band |
| **Days** | Blank for every day, or specific weekdays for a weekend rate |
| **Rate** | ¢/kWh. Enter **0** for a genuinely free period |
| **Charge here** | Whether to buy grid energy during this period |

**Peak periods are the deadline.** The battery must be full and the Powerwall back in Self-Powered before one begins. Charging never runs during Peak — except under a weather override.

There is no separate "charging mode". A free tariff is a period with rate 0; disabling charging entirely is every checkbox unticked.

### The rate is not just decoration

It does two real jobs beyond display:

- **Fill vs Smart.** A period at `0¢` is held in Backup-Only for its whole length, so the house runs on free grid while the battery fills and never discharges. A period above `0¢` charges only while below target, then hands back to Self-Powered.
- **Vacation Mode** suppresses charging where the rate is above zero, but still permits it at zero — free energy is worth taking whether or not anyone is home.

### Worked example

A Victorian midday-saver plan:

| # | Type | Window | Rate | Charge? |
|---|------|--------|------|---------|
| 1 | Super Off-Peak | 11:00–16:00 | 9.90¢ | ☑ |
| 2 | Off-Peak | 21:00–11:00 | 18.70¢ | ☐ |
| 3 | Peak | 16:00–21:00 | 46.75¢ | — |

Charging overnight at 18.7¢ is pointless when 9.9¢ arrives later and still before peak, so only period 1 is ticked. Storing at 9.9¢ to displace a 46.75¢ import saves **36.85¢/kWh**.

### Coverage check

The page validates per weekday and reports gaps, overlapping periods, a missing Peak period, and charging enabled on a Peak period. Day restrictions make coverage day-dependent, so each weekday is walked separately.

---

## The Charge Target

This is where all the intelligence lives. Everything else is a simple comparison against it.

The target is a **level the battery should be at right now**, not a fixed morning figure:

```
solar to battery = solar still expected before peak − house load until then
target kWh       = capacity − solar to battery
target %         = target kWh ÷ capacity, capped at 99
```

Early in the day plenty of solar is still coming, so the target sits low and grid charging stays out of the way. As the afternoon wears on the remaining solar shrinks and the target climbs, **reaching 99% by the time peak begins**.

That convergence is what guarantees a full battery at peak. It also avoids charging to full early, which on a low feed-in tariff would push the day's remaining solar out to export for almost nothing.

> **Why the previous model couldn't do this.** It credited the *whole day's* solar regardless of how much had already been spent, producing a single morning pre-charge figure. Once the battery passed that figure, charging stopped — so an underdelivering afternoon left you short at peak with no mechanism to notice.

### Both inputs are measured

**Solar remaining** starts from the most recent Solcast forecast, shaped by the real sunrise/sunset curve between now and the deadline, then corrected by live inverter output.

**House load** comes from the Powerwall's own `loadPower` meter — house consumption only, excluding the battery charge draw — accumulated into **per-hour averages for today**, and the prediction uses the mean across today's daylight hours so far.

A single rolling average cannot represent a load that swings between roughly 0.8 kW and 4.3 kW as a heat pump cycles: a fast average chases each compressor start, a slow one lags an hour behind reality. An hourly bucket is a genuine time-weighted mean including both the on and off portions of the duty cycle, and averaging several of them is stable without being stale.

The hours are combined with a **median, not a mean**. Pooling every sample makes the result sample-weighted, so a busy hour dominates: one morning heat-pump run left hour 9 averaging 3.94 kW against 1.08–2.15 kW for every other daylight hour, dragging the figure to 2.21 kW. The median of the hourly means gave 1.70 kW. Below three hours of data it falls back to the pooled mean.

### Projected hour by hour, not carried flat

Even a good median of *today so far* is the wrong statistic to project forward, because the morning and the afternoon are structurally different. With the heat pump running 07:00–09:00 at 3.3–4.0 kW, the median at 11:02 — the moment the charging decision is made — was 2.15 kW, and carrying that flat to 16:00 predicted 10.9 kWh. The afternoon actually drew about 1.2 kW, or 6.0 kWh. That overstated the target by nearly thirty points and bought about 3 kWh of grid energy the sun would have supplied for nothing.

So each day's hourly means are banked, up to 20 days, and the window to the deadline is walked **hour by hour** using the median of what that hour of the day typically draws:

| Hour | Observed | Median |
|---|---|---|
| 11:00 | 2.10 / 1.30 / 0.90 | 1.30 kW |
| 12:00 | 2.15 / 1.60 / 1.30 | 1.60 kW |
| 13:00 | 1.70 / 2.10 / 1.00 | 1.70 kW |
| 14:00 | 1.08 / 1.70 / 1.40 | 1.40 kW |
| 15:00 | 1.21 / 1.10 / 1.40 | 1.21 kW |

That turns the 11:02 projection into 7.2 kWh against a true 6.0, and the target into 55% instead of 83%.

Hours with no history fall back to today's median, so nothing changes until three days are banked. The House Load panel row says which is in force.

Today's own hours are used rather than a profile learned across days, because consumption tracks weather and occupancy rather than a repeating weekly shape. The buckets reset at midnight, so a misleading value cannot outlive the day that produced it. Overnight hours are excluded — they are not representative of the afternoon being extrapolated into. Until about half an hour of daylight samples exist it falls back to the seasonal model, and a wide gross-error guard catches a unit mix-up or a stuck meter.

Neither the old hardcoded `0.90` "solar before peak" factor nor the assumed 8-hour solar day survives — the curve supplies real figures for whatever deadline you configure.

---

## Solar Forecasting

### Two forecasts a day

Solcast is polled several times through the day and revises its view of the same day each time. The app keeps two copies of the Low/Mid/High band, because they answer different questions:

| | Captured | Used by |
|---|---|---|
| **Opening** | The first poll of the day, then frozen | The solar-noon trend check |
| **Latest** | Overwritten by every poll | The charge target, remaining solar, the performance ratio |

The opening band is frozen deliberately. The trend check asks *"is the array delivering what was predicted this morning?"*, and that comparison must use a prediction made **before** the morning it is judging. Solcast reads satellite cloud imagery, so a midday forecast already knows how the morning went — comparing against it would be circular and would report "tracking Mid" almost every day.

The latest band is what everything forward-looking projects from. The charge target only cares about solar still to come between now and peak, and a 13:58 prediction of that afternoon is better informed than an 08:58 one.

The difference between them is itself useful: **latest minus opening** says whether today is now expected to do better or worse than it looked at breakfast. It appears in the status panel next to each estimate, coloured by direction, in a throttled log line as each revision lands, and in the day summary as a total.

Before the day's first poll, the app refuses to fall back on the device attribute — it still holds yesterday's numbers — and the charge target holds its safe default instead.

### Which Solcast estimate — solar-noon trend analysis

Solcast publishes Low, Mid and High estimates. Each day starts on **Mid**. At **solar noon** — computed daily from sunrise and sunset, roughly 12:25 midwinter to 13:25 midsummer in Melbourne — the app projects actual generation to a full-day total and compares it against the morning's band.

```
projected day total = generation so far ÷ fraction of solar day elapsed
f(t) = (1 − cos(π × elapsed)) / 2
```

The fraction is exactly **0.50 at solar noon**, so the projection is simply generation × 2.

This comparison uses the **opening** band, for the reason given above.

What this actually detects is your array's performance against modelled irradiance. Solcast predicts the sun better than any local heuristic, but it cannot see panel soiling, new shading, inverter derating or a dropped string. A projection persistently below the opening band on clear days is a maintenance signal, not a weather one.

Selection is biased toward the conservative choice — under-charging costs a peak-rate import, over-charging only costs the feed-in spread — so the projection must travel `forecastUpgradeBias`% of the way toward a higher estimate before it is selected (default 60%).

### The solar day is computed, not assumed

Nearly everything here depends on knowing **what fraction of the day's generation has already happened**. Remaining solar reduces to `actual × Δf ÷ f_now`, so that fraction is doing all the work — and an idealised curve gets it badly wrong.

The obvious model is a half-sine between sunrise and sunset, symmetric about solar noon. Measured against a cloudless day at the reference site — **1.5 kW facing NE and 3.9 kW facing NW**, southern hemisphere, so nearly three quarters of the capacity is on the afternoon sun:

| Time | Half-sine says | Geometry says | Actually |
|---|---|---|---|
| 09:01 | 11.6% | 8.3% | **5.0%** |
| 10:04 | 22.0% | 17.7% | 14.6% |
| 11:06 | 34.5% | 29.9% | 28.4% |
| 12:09 | 48.3% | 44.3% | 44.3% |
| 13:43 | 68.9% | 66.4% | 68.6% |
| 15:16 | 86.0% | 85.3% | 87.1% |
| **Mean error** | **0.038** | **0.020** | — |
| **At solar noon** | 0.500 | **0.461** | 0.464 |

An overstated `f_now` **halves** the projected remaining solar, which is how a 25 kWh day gets grid-charged as though it will produce 14.

So the curve is answered in three layers, each falling back to the one below.

**1 · Geometry.** Configure your arrays under *Charge Level → Panel Array Geometry* — kW, compass azimuth and tilt per roof face — and the app computes when your generation arrives. Declination from the date, hour angle from solar noon taken as the midpoint of the hub's own sunrise and sunset (which absorbs the equation of time and longitude for free), clear-sky beam irradiance plus an isotropic diffuse term, integrated across the day.

Only the *shape* comes from this; magnitude still comes from Solcast. That makes the crude irradiance model cheap and the result barely sensitive to tilt — 15°, 22.5° and 30° all land within 0.003, so an approximate roof pitch is fine. It is correct on day one, immune to weather, and exact through the seasons because declination is a function of the date.

The settings page previews the curve as you enter it, so a mistyped azimuth shows up immediately instead of quietly biasing the target for weeks.

**2 · Idealised.** The half-sine, used only when no arrays are configured.

**3 · Measured correction.** What geometry cannot see: terrain shading, soiling, a dropped string. Each day is banked as the **difference** between what it actually did and what the computed curve predicted — a small signed number centred near zero, so day-to-day weather largely cancels in the median rather than being mistaken for the site's shape. That is the answer to the obvious objection that twenty days of history is twenty days of weather.

On the day above the correction runs about −0.03 through the morning and +0.02 in the afternoon: the ranges to the east, and nothing else. It needs five banked days before it applies, and days producing under 3 kWh are discarded.

The profile is indexed by **slice of the solar day, not clock hour** — hour-indexing would bake today's daylight length into the curve, and near an equinox that moves about two minutes a day.

> This also fixes the solar-noon trend check, which divides by that same fraction. A symmetric curve assumes exactly 0.50 at solar noon; this site's true figure is 0.46, and every projection was understated by the difference.

### Live correction from inverter output

The cumulative curve's derivative gives expected generation right now:

```
f'(e) = (π/2) · sin(πe)
expectedKw = fullDayKwh × (π/2) × sin(π·e) ÷ dayLengthHours
```

It integrates to exactly 1.0 across the day, so it is consistent with the forecast by construction. Measured output divided by this gives a performance ratio that scales the remaining-solar estimate in real time — catching a cloud bank that cumulative energy would take hours to reveal.

The ratio is **clamped to [0.2, 1.4]**, and only evaluated where expected output exceeds 0.5 kW so it cannot blow up near sunrise or sunset. The bounds are deliberately asymmetric: understating remaining solar only over-charges at the cheap rate, while overstating it enters peak short and imports at the expensive one.

Once the measured curve is in force this is a genuine weather signal. While the half-sine is still being used it also absorbs that curve's shape error, which is why it reads low all morning and climbs through midday on days with no weather in them at all.

Separately, if generation has effectively stopped while the curve still expects output, remaining solar is treated as **zero** — covering late cloud, an inverter dropout, or terrain shading the sun-angle model knows nothing about.

---

## Charging

One rule covers every tariff shape:

```
in a chargeable period, battery below target  →  Backup-Only
otherwise                                     →  Self-Powered
```

Charging begins at the **start** of a chargeable period whenever the battery is below target, and stops once the target is met.

The band is **asymmetric**, and it has to be. Charging starts at 2% below target — enough to absorb reporting jitter. But through the afternoon the target ratchets up 2–4 points every quarter hour as the remaining solar shrinks, so stopping the instant the battery reaches it guarantees the next evaluation finds the battery below the new target and starts again. That produced eight mode changes in ninety minutes on one observed day, none of which changed the outcome: the battery was climbing to 99% either way.

So the stop threshold overshoots by however far the target actually climbed over the previous quarter hour — measured, not assumed, so it adapts to the day and the season. On a day when the target is flat or falling the margin is zero and nothing changes. It is capped at 6 points, cannot push past 99%, and zeroes itself when the target has been still for 45 minutes, so the run into peak and the overnight hold are unaffected. Replayed against that day's real trace, seven mode changes become three.

There is no late-start calculation. Starting at the period open trades a modest cost on good solar days — house load bought from grid while charging — for certainty of a full battery at peak. Because the target is self-limiting, this is not "charge for the whole window": on a good solar day the target sits below the battery level and nothing happens at all.

**Closeout.** In the final minutes before Peak begins, the app forces Self-Powered every minute, bypassing the mode-change cooldown. The peak boundary is a hard deadline and reported device state can be stale, so the command repeats until it takes. If severe or extreme weather is active it delegates instead, so a weather-driven Backup-Only is not cleared on the way into peak.

**Weather override.** Severe weather or extreme heat overrides the tariff entirely: the target goes to maximum and charging runs in whatever period is active, **including Peak**. Economics stop applying when the priority is having a charged battery.

---

## Severe Weather Warnings

The app monitors the OpenWeather Alerts device for alerts matching your region and configured keywords (e.g. "severe", "damaging", "destructive"). Matching is fully case-insensitive. When an alert matches both a region **and** a keyword, the `SevereWeatherWarnings` hub variable is set to `true` and the charge target is raised to the configured level.

An extreme heat fallback applies the same warning even with no keyword match — if today's tracked maximum temperature meets or exceeds the configured threshold (default 35°C), the warning is raised anyway.

---

## Extreme Weather & Night Mode

Between configurable hours (default 15:04–08:58, spanning overnight), the app monitors for conditions warranting Backup-Only mode to preserve charge for overnight and early-morning use:

- Forecast high temperature ≥ threshold (default 35°C)
- Severe weather warning active

If neither condition is met the Powerwall returns to Self-Powered. The check is triggered on a daily schedule derived from the window start time, and whenever the forecast high or severe weather warning changes.

**Guard:** when a free charging window is active, this check will not force Self-Powered on conditions clearing — the free-mode handler is authoritative for that period. Without this the top-up stage could be cancelled mid-window on tariffs where peak begins at 4pm.

---

## Grid Outage Response

When the Power Grid virtual presence sensor reports "not present", the Powerwall is immediately switched to Self-Powered to preserve battery capacity. When the grid returns, the extreme weather window check is re-evaluated to determine whether Backup-Only should be reinstated.

---

## Mode Change Protection

All Powerwall mode changes are subject to a 5-minute minimum cooldown to prevent rapid toggling, which Powerwalls handle poorly.

Two paths bypass the cooldown deliberately, both because they represent hard deadlines where stale device state must not win:

- The **closeout handler**, returning to Self-Powered before peak pricing begins
- The **target-met branch**, which must keep sending the command until the Powerwall confirms it

---

## Required Devices & Hub Variables

### Devices

Quick reference for the device picker on the main page. Driver requirements and setup notes are in [Prerequisites](#prerequisites).

| App setting | Assign | Capability | Required | Degrades to if absent |
|-------------|--------|-----------|----------|-----------------------|
| Powerwall Device | Tesla Powerwall 2 | `battery` | Yes | — |
| OpenWeather Alerts Device | OpenWeather Alerts | `sensor` | Yes | — |
| Power Grid Virtual Presence Sensor | Virtual presence device | `presenceSensor` | Yes | — |
| Weather Station | Weather Underground | `temperatureMeasurement` | No | No daily max tracking; extreme-heat fallback uses OpenWeather instead |
| Solar Forecast Device | Solcast_dual | `energyMeter` | No | Target cannot be computed; falls back to 99% |
| Solar Generation Device | Fronius inverter | `energyMeter` | No | Trend analysis cannot run; Mid estimate always used |

> Both Solcast and Fronius present `capability.energyMeter` and both expose an `energy` attribute, so take care not to transpose the last two.

### Hub Variables

**These are created automatically** if they do not already exist. The app also registers itself as a user, so the hub will block their deletion while it is installed.

| Name | Type | Description |
|------|------|-------------|
| `PW_Charge_Target` | Number | Charge target % — written by this app, readable by other rules |
| `SevereWeatherWarnings` | Boolean | `true` when severe weather conditions are detected |

The main page shows live present/missing status for both.

---

## Installation

### 1. Drivers and integrations

Install these first — the app cannot be configured until the devices exist.

| Step | Where | What |
|------|-------|------|
| Powerwall driver | **Drivers Code → + New Driver** | [`tesla-powerwall.groovy`](tesla-powerwall.groovy) |
| Powerwall manager app | **Apps Code → + New App** | [`tesla-powerwall-manager.groovy`](tesla-powerwall-manager.groovy) — add it as a user app and sign in; it creates the Powerwall device |
| Solcast driver | **Drivers Code → + New Driver** | [`Solcast_dual.groovy`](Solcast_dual.groovy), then create a **Virtual Device** using it and enter your API key plus **both** site resource IDs |
| Fronius driver | **Drivers Code → + New Driver** | [`Fronius_Solar_Inverter.groovy`](Fronius_Solar_Inverter.groovy), then create a **Virtual Device** using it and enter the inverter's IP address |
| Weather drivers | **Hubitat Package Manager** | OpenWeather Alerts and Weather Underground |
| Grid presence | **Devices → Add Virtual Device** | Virtual Presence — see [Grid presence sensor](#grid-presence-sensor) |

### 2. The app

1. Go to **Apps Code → + New App** and paste in [`AdvancedPowerwallManager.groovy`](AdvancedPowerwallManager.groovy).
2. Go to **Apps → + Add User App** and select **Advanced Powerwall Manager**.
3. Assign the six devices, choose a charging mode, and work through the configuration pages.
4. Press **Done**. The hub variables are created on save.
5. Set the Solcast device's polling schedule — see below.

### 3. Verify

Open the app and check the **Status Panel**. Early signs something is misconfigured:

| Symptom | Likely cause |
|---------|--------------|
| Solar forecast rows show *stale* | Solcast has not polled yet today, or the API key is wrong |
| Trend Analysis shows *Not configured* | Solar Generation Device not assigned |
| Trend Analysis stuck on Mid all day | Fronius GEN24 Compatibility Mode is on, disabling daily energy |
| Trend Analysis shows *Awaiting sunrise/sunset data* | Hub location not set |
| Charge Target stuck at 0% | Annual average consumption not set, or no forecast device |
| Hub variables show ✗ missing | Creation failed — check logs and create them manually |

### Solcast polling schedule

The Solcast free tier allows a limited number of API calls per day, so the schedule is worth thinking about. Every poll is used: the first sets the trend reference, and each one after it updates the charge target.

Spend them where the forecast still has time to change the outcome — through the morning and across the charging window, ending before peak begins.

| Time | Purpose |
|------|---------|
| **08:58** | Opening band. Sets the trend reference for the day and the first real target |
| **09:58** | Early revision, in time for the start of a 09:00–11:00 charging window |
| **11:58** | Mid-window revision, with the morning's actual weather now in Solcast's model |
| **13:58** | Last useful revision before peak — the afternoon is what the target is buying against |

A poll after peak begins cannot change anything: the charging day has closed and the target is held until tomorrow's first poll.

---

## Configuration Reference

### Main Page

**Devices** — assign the six devices.

**Hub Variables** — live status display.

**Overrides**
- *Disable All Features* — master kill switch. The app registers no subscriptions, no schedules and takes no action, leaving the Powerwall in its current state. Use this to hand full control back to the Tesla app.
- *Vacation Mode* — suppresses charging in periods that cost money, while still permitting it in a free (0¢) period. Extreme weather protection stays active.

**Logging** — debug logging toggle.

### Tariff Periods

| Setting | Default | Notes |
|---------|---------|-------|
| Number of periods | 3 | 1–8 |
| Type | — | Super Off-Peak / Off-Peak / Shoulder / Peak |
| Start / End | — | May wrap midnight |
| Rate | — | ¢/kWh; `0` makes it a free period |
| Days | *(blank = all)* | For weekend rates |
| Charge here | Off | Not offered on Peak periods |
| Closeout minutes | 5 | Forced Self-Powered before Peak begins |
| Only in these hub modes | *(blank = all)* | Optional |

### Charge Level

| Setting | Default | Notes |
|---------|---------|-------|
| Severe weather charge target | 100% | Capped at 99 |
| Hot day threshold | 26.0°C | Forecast high forcing a full target |
| Hot day window | 11:58–15:00 | |
| Number of Powerwalls | 1 | Each 13.5 kWh |
| Annual average consumption | — | **Fallback only** — used when no live load meter is available |
| Forecast upgrade bias | 60% | How far the projection must travel to select a higher estimate |

### Severe Weather Warnings

| Setting | Default |
|---------|---------|
| Region / location names | `Melbourne, Central Ranges` |
| Alert keywords | `severe, damaging, destructive` |
| Extreme heat fallback | 35.0°C |

### Extreme Weather & Grid Outage

| Setting | Default | Notes |
|---------|---------|-------|
| Window start | 15:04 | Set to just after your Peak period begins |
| Window end | 08:58 | Next morning |
| Forecast high threshold | 35.0°C | |

> Set this a few minutes **after** your Peak period start. If Peak begins at 4pm, use 16:04. Left at 15:04 with a 4pm peak it would sit inside a charging period, and the extreme-weather check would fight the charging logic for control of the Powerwall's mode.

---

## Status Panel

The main page shows a live snapshot, refreshed each time you open the app.

| Field | Description |
|-------|-------------|
| Charging Mode | Current tariff period, whether it is charging, and the next peak |
| Powerwall Mode | Self-Powered / Backup-Only |
| Battery Level | Current state of charge |
| Charge Target | Current value of `PW_Charge_Target`, flagged when held outside the charging day |
| Solar Generation (today) | Actual generation so far |
| Forecast Low / Mid / High (today) | Latest poll, active one marked, with the movement since this morning where it differs |
| Trend Analysis | Projection so far against **this morning's** band, and the resulting selection |
| Solar Curve | Whether the measured curve or the idealised half-sine is in use, and how many days are banked |
| House Load | Current draw, today's daylight average, and the hour-by-hour profile |
| Target Calculation | Solar remaining minus load until peak, and the resulting hold level |
| Current Temperature | Live weather station reading |
| Today's Max Temperature | Highest recorded today (reset at midnight) |
| Severe Weather Warning | Active / None |
| Grid Status | Present / Not Present |

---

## Seasonal Consumption Model *(fallback only)*

House load is normally measured from the Powerwall's `loadPower`. When no load meter is available the app falls back to a seasonal estimate from the annual average you configure:

```
effective consumption = annual average × (1 + 0.25 × cos(2π × month / 12))
```

Month 0 = January (summer peak). The ±25% amplitude makes the summer peak about 67% higher than the winter trough, matching a typical Melbourne profile with summer air-conditioning load. The status panel's Target Calculation row shows whether load is `measured` or `estimated`, so you can tell which path is in use.

---

## Known Limitations

**Charging starts at the beginning of a chargeable period rather than as late as possible.** This is deliberate — it trades a modest cost on good solar days (house load bought from grid while charging) for certainty of a full battery at peak. The exposure is bounded, because the target is self-limiting: on a good solar day the target sits below the battery level and nothing charges at all.

**The measured curve needs five days before it does anything.** On a fresh install the half-sine is in force, and on a site whose real curve is asymmetric that biases the charge target high — safe, but it buys grid energy it did not need to. The Solar Curve panel row says when the measured curve takes over.

**The geometry assumes an unobstructed horizon.** It knows where the sun is, not what is in front of it. Terrain, trees and neighbouring roofs reach the curve only through the measured correction, which needs five days and then follows seasonal change a week or two behind.

**Day restrictions on midnight-wrapping periods** are tested against the day the period *started*. A Friday-only 9pm–11am period is therefore active into Saturday morning, which is almost certainly what you want but is worth knowing.

---

## Logging

Enable **Debug Logging** on the main page for per-check detail including forecast selection working, the solar-remaining correction, and load averaging. With debug off, the app still logs all mode changes, charge target changes and key trigger events at `info` level.

The app is deliberately quiet in normal operation. Steady-state lines ("charging in progress", "target met", "no period covers now") are throttled to once every few minutes rather than logged on every battery report, and the charge target logs at `info` only when it actually moves. Anything representing a *change* still logs immediately.

At the moment the charging day ends — which is when peak begins — a **day summary** is written at `info`:

```
── Day summary ─────────────────────────────────────────
  Entered peak at 99% (13.4 kWh stored)
  Charged from 21% at 11:05, target met 13:49
  Solar 10.92 kWh vs this morning's 12.47 (88%) · estimate used: mid
  Solcast revised down 1.3 kWh during the day (final mid 11.2)
  House load 1.24 kW average across daylight hours
  Load profile: 0:00 1.4  1:00 0.8  …  15:00 1.2 kW
  Stored energy worth up to $6.27 at the 46.75c peak rate
```

That one block is enough to judge a day without reading the rest of the log.

Useful log markers:

```
── 15-min check ──          Scheduled evaluation
── Startup evaluation ──    Runs on save/install
Opening forecast for ...     First poll of the day, sets the trend reference
Forecast revised up/down     A later poll moved today's expected total
Forecast selection:         Trend analysis working (debug)
Solar remaining:            Curve estimate × measured ratio (debug)
Charging:                   Period, target and decision (debug)
Powerwall → Backup-Only     Mode change, with reason
```

---

## Version History

Full per-version notes are in the header comment of `AdvancedPowerwallManager.groovy`. Recent highlights:

| Version | Change |
|---------|--------|
| 4.5.0 | House load projected hour by hour from banked history; opening forecast band waits for all three attributes |
| 4.4.1 | Asymmetric charging band — stop threshold overshoots the climbing target, ending afternoon mode cycling |
| 4.4.0 | Solar day curve computed from panel geometry; measurement demoted to a correction on top |
| 4.3.0 | Solar day curve learned from measured generation; median hourly load; forecast clamped to generation-so-far |
| 4.2.0 | Opening and latest forecast kept separately; target follows same-day revisions; `24_Hour_Estimate` treated as a calendar-day total |
| 4.1.3 | Charge target held outside the charging day; day summary; quieter logs; House Load panel row |
| 4.1.0 | House load measured as per-hour averages for today, replacing the rolling average |
| 4.0.0 | Tariff-driven rewrite; time-varying charge target; measured house load and live solar correction |
| 3.9.0 | Free Off-peak Charging mode with solar-soak top-up; three-way mode selector |
| 3.8.0 | Hub variables created automatically; deletion blocked while installed |
| 3.7.0 | Solar-noon trend analysis replaces the cloud/UV and high-forecast heuristics |
| 3.6.0 | Master kill switch; weather-aware closeout |
| 3.5.0 | Code review fixes — midnight reset scheduling, priority ladder consolidation |
| 3.4.0 | Smart top-up charging |
| 3.3.0 | 99% Backup-Only charge ceiling |
| 3.0.0 | Guaranteed start time *(superseded, then removed entirely in 4.0.0)* |
| 2.5.0 | Solar surplus model replaces peak-period model |
| 1.0.0 | Initial release — direct port of 5 Rule Machine rules |
