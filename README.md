# Advanced Powerwall Manager

A Hubitat Elevation app that consolidates Tesla Powerwall management into a single, event-driven application. Replaces five separate Rule Machine rules with solar-aware logic that adjusts the Powerwall charge target based on the day's solar forecast, measured generation, and seasonal household consumption.

**Current version: 3.9.0**

---

## Charging Modes

The app runs in one of three modes, selected from a dropdown on the main page. Only one is ever active, and each keeps its own independent settings so switching between them is a single dropdown change with no reconfiguration.

| Mode | Use when | Behaviour |
|------|----------|-----------|
| **Off-peak Charging** | Traditional time-of-use tariff | Smart, cost-optimised. Solar forecasting, trend analysis, late-start timing. |
| **Free Off-peak Charging** | Tariff with a zero-cost midday window (Victoria's "midday saver" and equivalents) | Fill the battery during the free window, top up during solar soak. |
| **None** | Temporarily pausing charging | No charging. Severe weather, extreme weather and grid outage handling stay active. |

Pages belonging to the inactive mode are tagged `(inactive)` rather than hidden, so you can configure a mode before switching to it.

---

## How It Works — Off-peak Charging

### Charge Target Calculation

A solar-aware charge target runs every 15 minutes within a configurable daily window. The target is written to the `PW_Charge_Target` hub variable and used by the off-peak charging window.

**The goal:** have the battery as full as possible at the start of peak tariff, so the peak period is covered by stored energy rather than grid imports.

```
effective consumption  = annual average × seasonal factor
solar before peak      = selected Solcast forecast × 90%
daytime house load     = effective consumption × (8 hrs ÷ 24)
solar surplus          = max(0,  solar before peak  −  daytime load)
solar → battery        = min(surplus,  total capacity)
pre-charge target kWh  = total capacity  −  solar → battery
charge target %        = pre-charge target ÷ total capacity × 100
```

- **Poor solar day** (e.g. 10 kWh forecast): house uses all the generation, surplus = 0, battery gets nothing from solar → **maximum pre-charge** from cheap off-peak electricity.
- **Good solar day** (e.g. 25+ kWh forecast): surplus fills the battery → **low or zero pre-charge** needed.
- **Seasonal variation** is applied automatically using a sinusoidal Southern Hemisphere curve (±25%, peak in January, trough in July). You enter one annual average figure and the app adjusts it monthly.

**The target is capped at 99%.** In Backup-Only mode a Powerwall will not charge beyond 99%, so a target of 100% could never be satisfied and would leave the Powerwall charging indefinitely. The cap is applied before the value is written to the hub variable, so every downstream reference sees 99%.

### Priority ladder

Evaluated in order; the first match wins.

| Priority | Condition | Result |
|----------|-----------|--------|
| — | Charging mode is **Free** | 99% — unconditional, ladder skipped entirely |
| — | Charging mode is **None** | 0% |
| 1 | Hub mode is `Vacation` | 0% |
| 2 | Severe weather warning active | Configurable % (default 100 → capped 99) |
| 3 | Vacation Mode override toggle | 0% |
| 4 | Hot day forecast during hot-day window | 100% → capped 99 |
| 5 | Normal | Solar surplus model above |

---

### Forecast Selection — Solar-Noon Trend Analysis

Solcast publishes three estimates for the day: Low, Mid (P50) and High. Rather than always using the middle one, the app measures how the day is actually tracking and picks accordingly.

Each day starts on the **Mid** estimate. At **solar noon** — computed daily from sunrise and sunset, roughly 12:25 midwinter to 13:25 midsummer in Melbourne — the app projects actual generation so far to a full-day total and compares it against the morning's forecast band.

```
projected day total = generation so far ÷ fraction of solar day elapsed
```

The fraction comes from the normalised integral of a half-sine between sunrise and sunset:

```
f(t) = (1 − cos(π × elapsed)) / 2        elapsed = (t − sunrise) / (sunset − sunrise)
```

This yields 0.0 at sunrise, exactly **0.50 at solar noon**, and 1.0 at sunset — so at the decision point the projection is simply **generation × 2**. Because it is anchored to real sun times it self-adjusts for season and daylight saving.

**Why the baseline must predate the morning.** The band it compares against is snapshotted from the *first* forecast poll of the day (~08:58), not the current one. Solcast ingests satellite cloud imagery, so a midday forecast already reflects the morning's actual weather — comparing generation-so-far against it would be circular and would conclude "tracking Mid" almost every day.

**What this actually detects** is your array's performance against modelled irradiance. Solcast predicts the sun better than any local heuristic could, but it cannot see panel soiling, new shading from a growing tree, inverter derating in heat, or a dropped string. A projection persistently below baseline on clear days is a maintenance signal, not a weather one.

**Conservative bias.** Selection is deliberately asymmetric. Under-charging costs a peak-rate import; over-charging only costs the off-peak/feed-in spread. The projection must therefore travel `forecastUpgradeBias`% of the way toward the next-higher estimate before that estimate is selected (default 60%; 50% would be a neutral nearest-match).

**Stale forecast guard.** Solcast polls on a schedule, so between midnight and the first poll of the day the attributes still hold *yesterday's* values. Charging decisions are suppressed until a fresh forecast arrives, which also covers a failed poll.

---

### Off-Peak Pre-Charging

The app uses a **smart late-start** approach within a configurable daytime window. Rather than charging immediately, it calculates the latest possible start so the Powerwall finishes just before the window closes, maximising solar self-consumption.

```
kWh needed   = (target% − current%) × total capacity
hours needed = kWh needed ÷ charge rate
latest start = window end − hours needed
```

If the Powerwall is already charging, its live power reading is used instead of the configured rate.

**Early-start hedge.** The forecast selection is not made until solar noon, which creates a problem: if the day then turns out to be tracking the Low estimate, the target jumps and much of the window is already gone. So before solar noon the app sizes the worst case — the target it would need under the *Low* estimate — and if that charge would not fit in the window remaining after solar noon, it begins at the window start rather than waiting for information it cannot act on.

This is self-tuning across the year. The post-solar-noon window is around 2h35m midwinter but only about 1h35m midsummer, so early starts correctly become more common in summer. The hedge applies **only before solar noon**; once the selection is made the real target governs.

**Closeout protection.** In the final N minutes before window end (default 5), the app forcibly switches back to Self-Powered every minute, bypassing the normal cooldown. This hard deadline ensures the Powerwall is never left in Backup-Only when peak pricing begins, even if device state events arrive late. If severe or extreme weather conditions are active, the closeout delegates to the extreme weather logic instead, so a weather-driven Backup-Only state is not cleared.

**Top-up suppression.** Once the target has been reached and the Powerwall returns to Self-Powered, any later top-up session within the same window suppresses the early-start hedge and uses only the late-start calculation, so charging waits as long as possible rather than repeatedly importing while solar is available.

---

## How It Works — Free Off-peak Charging

For tariffs with a zero-cost midday window. When grid energy is free there is no optimisation problem left, so this mode ignores the solar forecast entirely and simply fills the battery.

### The Victorian "midday saver" structure

Sorting the periods by price rather than by name is instructive:

| Period | Window | Rate |
|--------|--------|------|
| Free | 11am–2pm | **$0.0000** |
| Solar Soak | 2pm–4pm | **$0.1976** |
| Off-Peak | 9pm–11am | $0.2478 |
| Peak | 4pm–9pm | $0.4982 |

Solar Soak is around 20% cheaper than the period actually called "Off-Peak", and it sits immediately before peak — which makes it the cheapest possible place to top up.

### Two stages

**Free window** — hold Backup-Only for the entire window, unconditionally. Backup-Only means the house runs on free grid power while the battery charges and never discharges.

> Dropping to Self-Powered on reaching target — which is correct for paid off-peak — would be exactly backwards here. It would spend free hours draining the battery.

**Solar-soak top-up** — hold Backup-Only only while the battery is under 99%. If the free window left the battery short, topping up here costs $0.1976 to avoid importing at $0.4982. Even after ~90% round-trip losses that is roughly **$0.28/kWh saved**. Charging stops as soon as the battery is full, after which the house runs off the free energy already stored. This stage defaults on and can be disabled if you want strictly zero-cost charging.

At the end of the last active window the Powerwall returns to Self-Powered for peak.

### Deliberate differences from standard mode

- **Vacation Mode is ignored.** Free power is worth taking whether or not anyone is home. The closeout is likewise not suppressed by Vacation Mode, since that would leave the Powerwall in Backup-Only into the peak period.
- **The charge target is unconditionally 99%.** Every branch of the ladder would land there anyway — severe weather and hot-day both request 100 (capped), and the vacation branches are bypassed by design.
- **The charge evaluation window is bypassed.** It exists only to schedule the solar surplus calculation, and there is no calculation left.
- **Solar forecasting and trend analysis keep running** for display only. The trend signal remains a useful site-performance diagnostic regardless of how energy is bought.
- **Grid outage wins.** If the grid is absent the mode stands down, so it cannot fight the grid-outage handler — and there is no free energy to import during an outage.

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

| Device | Capability | Required | Purpose |
|--------|-----------|----------|---------|
| Tesla Powerwall | `battery` | Yes | Mode control and battery level |
| OpenWeather Alerts | `sensor` | Yes | Weather alerts and forecast high |
| Power Grid Presence Sensor | `presenceSensor` | Yes | Grid outage detection (virtual sensor) |
| Weather Station | `temperatureMeasurement` | No | Daily max temperature tracking |
| Solcast Solar Forecast | `energyMeter` | No | 24-hour forecast — uses `24_Hour_Estimate`, `_Low` and `_High` |
| Solar Generation Meter | `energyMeter` | No | Actual daily generation (`energy`, kWh) — required for trend analysis |

Without the Solar Generation Meter the trend analysis cannot run and the Mid estimate is always used.

### Hub Variables

**These are created automatically** if they do not already exist. The app also registers itself as a user, so the hub will block their deletion while it is installed.

| Name | Type | Description |
|------|------|-------------|
| `PW_Charge_Target` | Number | Charge target % — written by this app, readable by other rules |
| `SevereWeatherWarnings` | Boolean | `true` when severe weather conditions are detected |

The main page shows live present/missing status for both.

---

## Installation

1. In Hubitat, go to **Apps Code → + New App** and paste in `AdvancedPowerwallManager.groovy`.
2. Go to **Apps → + Add User App** and select **Advanced Powerwall Manager**.
3. Assign devices, choose a charging mode, and work through the configuration pages.
4. Press **Done**. The hub variables are created on save.

### Solcast polling schedule

The forecast device polls on its own schedule, constrained by the free API tier. The trend analysis depends on getting a baseline before the morning and a fresh reading near solar noon, so poll placement matters more than poll count:

| Time | Purpose |
|------|---------|
| 08:58 | Baseline snapshot — the day's opening prediction |
| 09:45 | Fresh data before the early-start decision |
| 12:15 | Covers winter and shoulder solar noon |
| 13:15 | Covers summer solar noon |

Whichever of the last two precedes solar noon is the one in play, so every season gets a forecast no more than about 10 minutes old at the decision point.

---

## Configuration Reference

### Main Page

**Charging Mode** — the three-way selector described above.

**Devices** — assign the six devices.

**Hub Variables** — live status display.

**Overrides**
- *Disable All Features* — master kill switch. The app registers no subscriptions, no schedules and takes no action, leaving the Powerwall in its current state. Use this to hand full control back to the Tesla app.
- *Vacation Mode* — disables off-peak charging and hot-day pre-charging while keeping extreme weather protection active. Ignored in Free Off-peak mode.

**Logging** — debug logging toggle.

### Charge Level *(Off-peak mode only)*

| Setting | Default | Notes |
|---------|---------|-------|
| Evaluation window | 05:59–14:05 | When `PW_Charge_Target` is recalculated |
| Severe weather charge target | 100% | Capped at 99 |
| Hot day threshold | 26.0°C | Forecast high forcing a full target |
| Hot day window | 11:58–15:00 | |
| Number of Powerwalls | 1 | Each 13.5 kWh |
| Annual average consumption | — | kWh/day; seasonal curve applied automatically |
| Forecast upgrade bias | 60% | How far the projection must travel to select a higher estimate |

### Off-Peak Charging *(Off-peak mode only)*

| Setting | Default | Notes |
|---------|---------|-------|
| Earliest charging start | 09:00 | Also the early-start hedge time |
| Window end | 15:00 | Target finish, before peak |
| Closeout minutes | 5 | Forced Self-Powered handoff |
| Assumed charge rate | 3.0 kW | Real-world average; live reading used when charging |
| Mode restriction | *(blank)* | Optional hub-mode filter |

### Free Off-Peak Charging *(Free mode only)*

| Setting | Default | Notes |
|---------|---------|-------|
| Free window start | 11:00 | |
| Free window end | 14:00 | |
| Top up during solar soak | On | |
| Top-up end | 16:00 | Peak period start |
| Closeout minutes | 5 | |
| Only on these days | *(blank = all)* | Victoria currently runs 7 days |
| Only in these hub modes | *(blank = all)* | |

### Severe Weather Warnings

| Setting | Default |
|---------|---------|
| Region / location names | `Melbourne, Central Ranges` |
| Alert keywords | `severe, damaging, destructive` |
| Extreme heat fallback | 35.0°C |

### Extreme Weather & Grid Outage

| Setting | Default | Notes |
|---------|---------|-------|
| Window start | 15:04 | Set after the later of your two modes' window ends |
| Window end | 08:58 | Next morning |
| Forecast high threshold | 35.0°C | |

> **If you use Free Off-peak mode**, set the window start to **16:04**. At 15:04 it sits inside the solar-soak top-up window. 16:04 is correct for both modes — under standard mode it simply leaves 15:00–16:04 unmanaged, where the Powerwall stays Self-Powered from closeout anyway.

---

## Status Panel

The main page shows a live snapshot, refreshed each time you open the app.

| Field | Description |
|-------|-------------|
| Charging Mode | Active mode, and in Free mode which stage is running |
| Powerwall Mode | Self-Powered / Backup-Only |
| Battery Level | Current state of charge |
| Charge Target | Current value of `PW_Charge_Target` |
| Solar Generation (today) | Actual generation so far |
| Solar Forecast Low / Mid / High | All three Solcast estimates, with the active one marked |
| Trend Analysis | Live projection against the morning baseline, and the resulting selection |
| Target Calculation | Full working of the surplus model, or the free-mode explanation |
| Current Temperature | Live weather station reading |
| Today's Max Temperature | Highest recorded today (reset at midnight) |
| Severe Weather Warning | Active / None |
| Grid Status | Present / Not Present |

---

## Seasonal Consumption Model

```
effective consumption = annual average × (1 + 0.25 × cos(2π × month / 12))
```

Month 0 = January (summer peak). The ±25% amplitude makes the summer peak about 67% higher than the winter trough, matching a typical Melbourne profile with summer air-conditioning load. If your seasonal swing differs significantly the targets will still be directionally correct — the model's main value is avoiding over-pre-charging on sunny summer days and under-pre-charging on cloudy winter days.

---

## Known Limitations

**The `0.90` "solar before peak" constant is hardcoded and season-blind.** It assumes 90% of the day's generation lands before peak starts. The true figure varies considerably:

| Peak start | Midsummer | Midwinter |
|------------|-----------|-----------|
| 3pm | ~66% | ~88% |
| 4pm | ~76% | ~96% |

So the constant is tuned for winter and optimistic in summer, meaning the app under-charges on long summer days. The same applies to the hardcoded 8-hour solar day used for daytime house load. Both are derivable from the sunrise/sunset curve already used by the trend analysis, and `computeSolarModelTarget()` exists as the seam for that change. This only affects **Off-peak Charging** mode; Free mode does not use the solar model.

---

## Logging

Enable **Debug Logging** on the main page for per-check detail including forecast selection working, the early-start hedge calculation, and stage transitions. With debug off, the app still logs all mode changes, charge target changes and key trigger events at `info` level.

Useful log markers:

```
── 15-min check ──          Scheduled evaluation
── Startup evaluation ──    Runs on save/install
Forecast baseline captured  Morning snapshot taken
Forecast selection:         Trend analysis working (debug)
Early-start hedge:          Worst-case sizing (debug)
Powerwall → Backup-Only     Mode change, with reason
```

---

## Version History

Full per-version notes are in the header comment of `AdvancedPowerwallManager.groovy`. Recent highlights:

| Version | Change |
|---------|--------|
| 3.9.0 | Free Off-peak Charging mode with solar-soak top-up; three-way mode selector |
| 3.8.0 | Hub variables created automatically; deletion blocked while installed |
| 3.7.0 | Solar-noon trend analysis replaces the cloud/UV and high-forecast heuristics |
| 3.6.0 | Master kill switch; weather-aware closeout |
| 3.5.0 | Code review fixes — midnight reset scheduling, priority ladder consolidation |
| 3.4.0 | Smart top-up charging |
| 3.3.0 | 99% Backup-Only charge ceiling |
| 3.0.0 | Guaranteed start time *(superseded by the early-start hedge in 3.7.0)* |
| 2.5.0 | Solar surplus model replaces peak-period model |
| 1.0.0 | Initial release — direct port of 5 Rule Machine rules |
