/**
 *  Advanced Powerwall Manager
 *
 *  Consolidates five Hubitat Rule Machine rules into a single app:
 *    1. Set Charge Level          – solar forecast tiers + overrides
 *    2. Charge Battery Off Peak   – smart late-start charging window
 *    3. Severe Weather Warnings   – case-insensitive alert matching by region/keyword
 *    4. Extreme Weather           – Backup-Only mode outside solar hours
 *    5. Grid Outage               – Self-Powered on grid departure
 *
 *  Required Hub Variables (created automatically on save if missing):
 *    PW_Charge_Target      Number  – charge target % written by this app
 *    SevereWeatherWarnings Boolean – true when severe weather detected
 *
 *  Version history:
 *    3.9.0  Free Off-peak Charging mode, for tariffs with a zero-cost midday window
 *           (Victoria's "midday saver" and equivalents). New Charging Mode selector on the
 *           main page with three options — Off-peak Charging (the existing behaviour),
 *           Free Off-peak Charging, and None — and a dedicated freeOffPeakPage. The two
 *           charging modes keep entirely separate time settings so switching between them
 *           is a single dropdown change with no reconfiguration, and the inactive mode's
 *           pages are tagged rather than hidden so either can be set up before switching.
 *
 *           Free mode runs two stages. In the FREE window it holds Backup-Only
 *           unconditionally — the house runs on free grid power while the battery charges
 *           and never discharges. Dropping to Self-Powered on reaching target, which is
 *           correct for paid off-peak, would spend free hours draining the battery. In the
 *           SOLAR-SOAK top-up window it holds Backup-Only only while below 99%: on these
 *           tariffs solar soak is typically the cheapest paid window of the day and sits
 *           immediately before peak, making it the cheapest available insurance against a
 *           peak-rate import (roughly $0.28/kWh saved on current Victorian rates after
 *           round-trip losses). Top-up defaults on and can be disabled.
 *
 *           Free mode short-circuits the whole charge-target ladder to an unconditional 99%:
 *           severe weather and hot-day both request 100 (capped to 99) anyway, and the two
 *           vacation branches are deliberately bypassed because free power is worth taking
 *           whether or not anyone is home. The charge evaluation window is bypassed too —
 *           it exists only to schedule the solar surplus calculation. Solar forecasting and
 *           the solar-noon trend analysis keep running for display, since the trend signal
 *           is a useful site-performance diagnostic regardless of how energy is bought.
 *
 *           Guards added: extremeWeatherCheck() no longer forces Self-Powered when
 *           conditions clear during a free charging window (the top-up stage can run past
 *           extremeWindowStart on tariffs where peak begins at 4pm, so this would otherwise
 *           cancel charging mid-window); free mode skips when the grid is absent, so it
 *           cannot fight the grid-outage handler; the closeout handler now reads whichever
 *           window end belongs to the active mode, and is no longer suppressed by Vacation
 *           Mode in free mode — which would have left the Powerwall in Backup-Only into peak.
 *
 *           Also fixed: the extreme-weather trigger was hardcoded to 15:05 while the window
 *           start is a setting, so moving extremeWindowStart later (16:04 on a midday-saver
 *           tariff) meant nothing fired when the window opened. It is now derived from the
 *           setting. Status panel gains a Charging Mode row showing the active free-mode
 *           stage, and Target Calculation explains free mode instead of showing solar
 *           arithmetic that does not apply.
 *    3.8.1  Removed the Cloud Cover and UV Index rows from the status panel. They were
 *           retained as informational after 3.7.0 dropped the cloud/UV heuristic, but
 *           nothing reads them any more — the solar-noon trend analysis measures actual
 *           generation directly. Also removed the now-orphaned weatherName variable. The
 *           OpenWeather device is still required (alertDescrFull, forecastHigh) and the
 *           weather station is still used for temperature tracking.
 *    3.8.0  Hub variables are now created automatically if they do not exist, so the app
 *           is installable without any manual setup. PW_Charge_Target (Number, seeded 0)
 *           and SevereWeatherWarnings (Boolean, seeded false) are created during
 *           initialize() before any handler reads them; the app also registers itself via
 *           addInUseGlobalVar() so the hub blocks their deletion while it is installed, and
 *           implements the renameVariable() callback to warn if one is renamed (the names
 *           are hardcoded, so a rename would otherwise break the app silently). The Hub
 *           Variables section now shows live present/missing status and current values
 *           instead of static setup instructions. Creation failures are logged as errors
 *           and flagged in state.globalVarsReady rather than failing initialize().
 *           Default offPeakStart corrected 10:00 → 09:00 to match the actual tariff window.
 *    3.7.0  Trend-based forecast selection replaces the cloud/UV and high-forecast
 *           heuristics. Each day now starts on the middle Solcast estimate. At SOLAR NOON
 *           (computed daily from sunrise/sunset — roughly 12:25 midwinter to 13:25
 *           midsummer, not clock noon) the app projects actual generation to a full-day
 *           total and compares it against the morning's forecast band, switching to Low or
 *           High if the day is clearly tracking one of them.
 *
 *           Projection: generation so far ÷ fraction of the solar day elapsed, where the
 *           fraction is the normalised integral of a half-sine between sunrise and sunset,
 *           f(t) = (1 − cos(π × elapsed)) / 2. At solar noon f = 0.50 exactly, so the
 *           projection reduces to generation × 2.
 *
 *           The baseline band is snapshotted from the FIRST forecast poll of the day
 *           (~08:58) and must predate the morning: Solcast ingests satellite cloud imagery,
 *           so a midday forecast already reflects the morning's weather and comparing
 *           against it would be circular — the selector would return "mid" almost every day.
 *           What the comparison actually measures is the site's performance against modelled
 *           irradiance (soiling, new shading, inverter derating, dropped string) rather than
 *           the weather, which Solcast already models better than any local heuristic.
 *
 *           Selection is biased toward the conservative choice via the new forecastUpgradeBias
 *           input (default 60%): the projection must travel that far toward the next-higher
 *           estimate before it is selected, because under-charging costs a peak-rate import
 *           while over-charging only costs the off-peak/feed-in spread.
 *
 *           Off-peak charging gains an early-start hedge replacing forceChargeStartTime.
 *           Because the selection is not made until solar noon, the app sizes the worst case
 *           (target under the Low estimate) and begins at the window start if that charge
 *           would not fit in the window remaining after solar noon — otherwise it waits for
 *           better information. Self-tuning across the year: the post-solar-noon window is
 *           ~2h35m midwinter but only ~1h35m midsummer, so early starts correctly become
 *           more common in summer. Applies only before solar noon; once the selection is
 *           made the real target governs. Default offPeakStart moved 12:00 → 09:00.
 *
 *           Also added: stale-forecast guard (the estimates hold yesterday's values until
 *           the first poll of the day, so charging decisions are suppressed until then);
 *           sanity guard rejecting an implausible projection in case the generation meter
 *           turns out to be a lifetime counter rather than resetting at midnight;
 *           subscription to 24_Hour_Estimate_High, which was never subscribed; Trend Analysis
 *           row in the status panel showing the live projection against the baseline.
 *
 *           Removed: isPoorSolarConditions(), isHighSolarConditions(), lowForecastEnabled,
 *           lowForecastCloudThreshold, lowForecastUvThreshold, highForecastCheckTime,
 *           forceChargeStartTime, state.highSolarTriggered, state.lastHighSolarConditions,
 *           and the "Solcast Low Estimate Override" and "High Forecast Override" sections.
 *           Cloud cover and UV index remain in the status panel as information only.
 *    3.6.0  Master kill switch: new "Disable All Features" toggle in the Overrides
 *           section. When enabled, initialize() returns immediately after the
 *           unsubscribe/unschedule in updated(), so the app registers no event
 *           subscriptions, no schedules, and runs no startup evaluation — it takes no
 *           action and leaves the Powerwall in its current state (full control handed
 *           back to the Tesla app). A red banner on the status panel shows when active;
 *           toggling the switch off and saving restores normal operation. Also fixed:
 *           off-peak closeout and charge-target-met handlers no longer force Self-Powered
 *           when severe/extreme weather warrants Backup-Only — both now delegate to
 *           extremeWeatherCheck() via the new isExtremeConditionActive() helper so the
 *           peak-period transition cannot override a weather-driven Backup-Only state
 *    3.5.0  Code review fixes: midnight reset (resetDailyMaxTemp) is now scheduled
 *           unconditionally so daily flags (highSolarTriggered, chargeTargetReachedToday)
 *           always clear at midnight even when no weather station is configured;
 *           severeWeatherHandler log now correctly reports "startup" vs "alertDescrFull
 *           updated" as the trigger source; solarGenerationHandler no longer relies on
 *           the redundant lastHighSolarConditions state variable — transition detection
 *           uses highSolarTriggered directly; vacation override toggle consolidated into
 *           a single early-exit at Priority 3 in calculateChargeTarget(), removing the
 *           scattered guards around the hot-day and solar surplus blocks; status panel
 *           reads state.highSolarTriggered directly instead of calling
 *           isHighSolarConditions(), preventing accidental state mutation on UI render;
 *           effectiveTarget alias removed from offPeakChargeCheck() — chargeTarget used
 *           consistently throughout; priorities renumbered 1–5 accordingly
 *    3.4.0  Smart top-up charging: once the charge target has been reached and the
 *           Powerwall returns to Self-Powered, subsequent top-up sessions within the
 *           same off-peak window suppress the guaranteed start time and use only the
 *           late-start calculation — charging waits until the latest possible moment
 *           to avoid unnecessary grid imports while solar is available; flag resets
 *           at midnight alongside other daily state; top-up mode is noted in logs
 *    3.3.0  Powerwall Backup-Only mode charge ceiling: effective charge target is
 *           capped at 99% in offPeakChargeCheck() since the Powerwall cannot exceed
 *           99% in Backup-Only mode; prevents the target-met condition from never
 *           being satisfied when the configured target is 100%; log shows the cap
 *           when applied; kWh calculation and all timing logic use the capped value;
 *           removed cloud cover and UV index event subscriptions — poor solar
 *           conditions are now evaluated only when the solar forecast updates
 *    3.2.0  Added enable/disable toggle for the Solcast Low Estimate Override; when
 *           off, isPoorSolarConditions() returns false immediately and the standard
 *           forecast is always used regardless of cloud cover or UV index; toggle
 *           defaults to enabled; fixed misleading debug log label (ultravioletIndex
 *           renamed to uvIndex to match the actual attribute being read)
 *    3.1.0  Bug fix: Powerwall could remain in Backup-Only after reaching the charge
 *           target when the device state attribute was stale and the 5-minute cooldown
 *           blocked the retry; the target-met branch in offPeakChargeCheck now calls
 *           setSelfPoweredMode() directly (bypassing the cooldown), consistent with
 *           the existing closeout handler which uses the same pattern
 *    3.0.0  Guaranteed start time for off-peak charging: new optional time input
 *           (default 12:00) ensures charging always begins by that time if the
 *           battery is below target, regardless of the late-start calculation;
 *           effective start = min(guaranteed start, calculated latest start) so
 *           whichever is earlier wins; log distinguishes guaranteed vs late-start
 *           triggers; leave blank to rely solely on the late-start calculation
 *    2.9.0  High forecast override: when actual solar generation (from a new optional
 *           Solar Generation Device, 'energy' attribute) exceeds the middle Solcast
 *           forecast at a configurable check time (default 14:00), the app switches
 *           to 24_Hour_Estimate_High for the rest of the day; flag resets at midnight;
 *           new solarGenerationHandler triggers recalculation on each energy update;
 *           status panel adds Solar Forecast High and Solar Generation rows; Target
 *           Calculation label shows (High) when active; getSolarForecast() priority
 *           order: Low (poor solar) → High (generation exceeds forecast) → Middle
 *    2.8.0  Status panel: added Source column showing the contributing device name
 *           (or "Hub Variable" / "Calculated") for each row; added Cloud Cover and
 *           UV Index rows sourced from OpenWeather and the weather station; table
 *           header row added with blue tint matching sub-page section headings
 *    2.7.0  UI readability and navigation improvements: status table uses alternating
 *           row colours; Configuration section href descriptions now show live
 *           configured values (window times, capacity, rate, locations, threshold)
 *           instead of static text; Target Calculation in the status panel now uses
 *           getSolarForecast() so the displayed breakdown always matches the estimate
 *           the app actually used, and appends "(Low)" when the Low estimate is
 *           active; all input titles in severeWeatherPage and extremeWeatherPage
 *           now consistently bold-formatted to match other config pages
 *    2.6.0  Solcast Low estimate switching: when OpenWeather cloudToday exceeds the
 *           configured cloud threshold (default 70%) AND weather station
 *           ultravioletIndex is at or below the UV threshold (default 3),
 *           getSolarForecast() uses 24_Hour_Estimate_Low instead of the standard
 *           estimate; both thresholds are configurable; status panel shows which
 *           estimate is active; subscriptions added for cloudToday and
 *           ultravioletIndex with automatic charge target recalculation on change
 *    2.5.0  Charge target rebase: solar surplus model replaces peak-period model.
 *           Goal is maximum battery at peak start. Pre-charge = capacity minus
 *           what solar will put into the battery after covering daytime house
 *           load. Poor solar day (house uses all generation): 100% pre-charge.
 *           Good solar day (large surplus): low/zero pre-charge. Removes the
 *           peakPeriodEnd input; 8-hour solar day assumed for AU.
 *    2.4.0  Charge target rebase: goal is now to cover the peak tariff period
 *           (offPeakEnd → peakPeriodEnd, default 15:00–21:00) rather than the
 *           full day's consumption gap; target = peak load − afternoon solar
 *           (10% of forecast); solar before peak start charges the battery
 *           naturally and is not subtracted from the target
 *    2.3.0  Off-peak closeout hardened: charge check no longer initiates Backup-Only
 *           inside the closeout window; closeout handler now forces Self-Powered
 *           unconditionally every minute (bypasses cooldown) so stale device state
 *           cannot leave the Powerwall in Backup-Only past the peak period start
 *    2.2.0  Solar-aware gap calculation replaces solar forecast tiers entirely;
 *           charge target = (daily consumption − solar forecast) ÷ battery capacity;
 *           seasonal variation handled automatically via sinusoidal Southern
 *           Hemisphere model (±25%, summer peak) applied to a single annual
 *           average consumption figure; off-peak charging simplified to read
 *           PW_Charge_Target as set by the unified calculation
 *    2.1.0  Solar-aware pre-charge: user specifies number of Powerwalls and
 *           expected daily household consumption; app calculates exactly how
 *           much pre-charge is needed to cover the gap between solar forecast
 *           and consumption rather than relying on manually configured tiers.
 *           Falls back to PW_Charge_Target when solar-aware inputs are not set.
 *    2.0.0  Minimum 5-minute cooldown between Powerwall mode changes to prevent
 *           rapid toggling; all mode changes routed through guard helpers
 *           (setPowerwallSelfPowered / setPowerwallBackupOnly) which enforce
 *           the cooldown and log suppressed commands with remaining wait time
 *    1.9.0  Removed padding % setting; default assumed charge rate updated to
 *           3.0 kW (real-world average accounting for solar and house load);
 *           charging sessions no longer interrupted once started – late-start
 *           logic only determines when to begin, not whether to continue
 *    1.8.0  Smart late-start off-peak charging – calculates required charge time
 *           (kWh needed ÷ rate + padding) and delays start until the latest
 *           possible time within the 12–3 PM window; uses live Powerwall power
 *           reading when already charging for a more accurate remaining estimate
 *    1.7.0  Vacation Mode override switch – disables off-peak charging,
 *           hot-day pre-charge, and solar forecast tier charging while keeping
 *           extreme weather Backup-Only active
 *    1.6.0  Live status section added to main page (Powerwall mode, battery,
 *           charge target, solar forecast, temperatures, weather warning, grid)
 *    1.5.0  General info logging added throughout; cascading re-evaluations logged
 *    1.4.0  Severe weather matching rewritten – single region + keyword fields,
 *           fully case-insensitive; replaces brute-force multi-capitalisation variables
 *    1.3.0  Daily max temperature tracked internally from weather station;
 *           dedicated temperature sensor input removed
 *    1.2.0  Solar forecast sourced from Solcast driver (24_Hour_Estimate attribute)
 *           instead of Solar_Forecast hub variable
 *    1.1.0  Event-driven architecture; subscriptions to all source device states
 *           (Powerwall currentOpState + battery, OpenWeather alertDescrFull +
 *           forecastHigh, solar forecast, weather station temperature, grid presence,
 *           hub mode) replace most scheduled polling
 *    1.0.0  Initial release – direct port of 5 Rule Machine rules
 */

definition(
    name:        "Advanced Powerwall Manager",
    namespace:   "community",
    author:      "",
    description: "Manages Tesla Powerwall charging based on solar forecast, weather alerts, and grid status",
    category:    "Energy Management",
    iconUrl:     "",
    iconX2Url:   "",
    singleInstance: true,
    menu: "Apps", // Valid values are “Integrations”, “Automations”, and “Apps”
)

preferences {
    page(name: "mainPage")
    page(name: "chargeLevelPage")
    page(name: "offPeakPage")
    page(name: "freeOffPeakPage")
    page(name: "severeWeatherPage")
    page(name: "extremeWeatherPage")
}

// ─────────────────────────────────────────────────────────────────────────────
// Pages
// ─────────────────────────────────────────────────────────────────────────────

def mainPage() {
    dynamicPage(name: "mainPage", title: "Advanced Powerwall Manager", install: true, uninstall: true) {

        section("<b>Current Status</b>") {
            if (masterDisable) {
                paragraph "<div style='background:#fdecea; border:1px solid #f5c6cb; border-radius:4px; " +
                          "padding:8px 12px; color:#a12622;'><b>⛔ All features disabled.</b> " +
                          "The app is not managing the Powerwall. Turn off <b>Disable All Features</b> " +
                          "in the Overrides section to resume.</div>"
            }
            // Powerwall
            def pwState     = powerwallDevice ? (getAttr(powerwallDevice, "currentOpState") ?: "–") : "Not configured"
            def pwBattery   = powerwallDevice ? (getAttr(powerwallDevice, "battery") != null ? "${getAttr(powerwallDevice, 'battery')}%" : "–") : "–"
            def pwTarget    = getGlobalVar("PW_Charge_Target")?.value
            def pwTargetStr = pwTarget != null ? "${pwTarget}%" : "–"

            // Forecast estimate selection — computed once for consistency across all rows.
            // selectForecastEstimate() is a pure function, so calling it during a page render
            // cannot mutate app state.
            boolean forecastStale = solarForecastDevice ? isForecastStale() : false
            def selection = (solarForecastDevice && solarGenerationDevice) ? selectForecastEstimate() : "mid"
            def solarStr     = "–"
            def solarHighStr = "–"
            def solarLowStr  = "–"
            if (solarForecastDevice) {
                def svLow  = solarForecastDevice.currentValue("24_Hour_Estimate_Low")
                def sv     = solarForecastDevice.currentValue("24_Hour_Estimate")
                def svHigh = solarForecastDevice.currentValue("24_Hour_Estimate_High")
                def staleTag = forecastStale ? " ⚠ stale (awaiting today's poll)" : ""
                solarStr     = sv     != null ? "${sv} kWh${selection == 'mid'  ? ' ← active' : ''}${staleTag}" : "Awaiting update"
                solarHighStr = svHigh != null ? "${svHigh} kWh${selection == 'high' ? ' ← active' : ''}"        : "Awaiting update"
                solarLowStr  = svLow  != null ? "${svLow} kWh${selection == 'low'  ? ' ← active' : ''}"         : "Awaiting update"
            } else {
                solarStr     = "Not configured"
                solarHighStr = "Not configured"
                solarLowStr  = "Not configured"
            }

            // Solar generation (actual today) and the solar-noon trend projection
            def genRaw     = solarGenerationDevice ? solarGenerationDevice.currentValue("energy") : null
            def genStr     = genRaw != null ? "${genRaw} kWh" : (solarGenerationDevice ? "–" : "Not configured")
            def genDevName = solarGenerationDevice?.displayName ?: "–"

            def solarNoonDate = getSolarNoon()
            def dayFraction   = getSolarDayFraction()
            def trendStr      = "–"
            if (!solarGenerationDevice || !solarForecastDevice) {
                trendStr = "Not configured"
            } else if (solarNoonDate == null) {
                trendStr = "Awaiting sunrise/sunset data"
            } else if (new Date().before(solarNoonDate)) {
                trendStr = "Using Mid until solar noon (${solarNoonDate.format('HH:mm')})"
            } else if (genRaw != null && dayFraction != null && dayFraction >= 0.15d) {
                double implied = genRaw.toDouble() / dayFraction
                def snapLow    = state.forecastSnapshotLow
                def snapMid    = state.forecastSnapshotMid
                def snapHigh   = state.forecastSnapshotHigh
                trendStr = "${genRaw} kWh at ${Math.round(dayFraction * 100)}% of solar day → projected " +
                           "<b>${implied.round(1)} kWh</b> vs baseline [${snapLow ?: '–'} / ${snapMid ?: '–'} / ${snapHigh ?: '–'}] " +
                           "→ <b>${selection.toUpperCase()}</b>"
            } else {
                trendStr = "Awaiting generation data"
            }

            // Charging mode — and, in free mode, which stage is currently active
            def modeStr = "Off-peak Charging"
            if (chargingMode == "none") {
                modeStr = "None – no automatic charging"
            } else if (chargingMode == "free") {
                def nowD = new Date()
                def stage = "outside window"
                if (!isFreeOffPeakDay(nowD)) {
                    stage = "not an active day"
                } else if (freeOffPeakStart && freeOffPeakEnd &&
                           timeOfDayIsBetween(toDate(freeOffPeakStart), toDate(freeOffPeakEnd), nowD, location.timeZone)) {
                    stage = "<b>free window active</b>"
                } else if (freeTopUpEnabled != false && freeOffPeakEnd && freeTopUpEnd &&
                           timeOfDayIsBetween(toDate(freeOffPeakEnd), toDate(freeTopUpEnd), nowD, location.timeZone)) {
                    stage = "<b>solar-soak top-up active</b>"
                }
                modeStr = "Free Off-peak Charging – ${stage}"
            }

            // Charge target detail — uses getSolarForecast() so the breakdown always matches what the app actually used
            def chargeCalcStr     = "–"
            def statusConsumption = getDailyConsumption()
            if (chargingMode == "free") {
                chargeCalcStr = "Free mode: charge to 99% during the free window" +
                                ((freeTopUpEnabled != false && freeTopUpEnd)
                                    ? ", then top up during solar soak while below 99%" : "") +
                                " — solar forecast not used"
            } else if (chargingMode == "none") {
                chargeCalcStr = "No automatic charging"
            } else if (annualAvgConsumptionKwh && numPowerwalls) {
                def month     = Calendar.getInstance(location.timeZone).get(Calendar.MONTH)
                def monthName = ["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"][month]
                double totalCap  = (numPowerwalls as Integer) * 13.5d
                def    svForCalc = getSolarForecast()
                if (statusConsumption != null && svForCalc != null) {
                    double cons               = statusConsumption.toDouble()
                    double solarDaytime       = (svForCalc * 0.90d).round(1)
                    double consumptionDaytime = (cons * (8.0d / 24.0d)).round(1)
                    double surplus            = Math.max(0.0d, solarDaytime - consumptionDaytime).round(1)
                    double solarToBattery     = Math.min(totalCap, surplus).round(1)
                    double targetKwh          = Math.max(0.0d, totalCap - solarToBattery).round(1)
                    def    estimateLabel      = " (${selection.capitalize()})"
                    chargeCalcStr = "${monthName}: ${solarDaytime} kWh solar${estimateLabel} − ${consumptionDaytime} kWh load = ${surplus} kWh surplus → ${solarToBattery} kWh to battery → pre-charge ${targetKwh} / ${totalCap} kWh"
                } else if (statusConsumption != null) {
                    chargeCalcStr = "Awaiting solar forecast"
                } else {
                    chargeCalcStr = "Awaiting data"
                }
            } else {
                chargeCalcStr = "Not configured"
            }

            // Temperature
            def currentTempStr = "–"
            def dailyMaxStr    = "–"
            if (weatherStation) {
                def ct = getAttr(weatherStation, "temperature")
                currentTempStr = ct != null ? "${ct}°C" : "–"
                dailyMaxStr    = state.dailyMaxTemp != null ? "${state.dailyMaxTemp}°C" : "–"
            } else {
                currentTempStr = "Not configured"
            }

            // Severe weather & grid
            def severeStr = getSevereWeatherWarnings() ? "⚠️ ACTIVE" : "None"
            def gridStr   = powerGridPresence ? (getAttr(powerGridPresence, "presence") == "present" ? "Present" : "⚠️ NOT PRESENT") : "Not configured"

            // Source device names for the 3rd column
            def pwDevName    = powerwallDevice?.displayName     ?: "–"
            def stationName  = weatherStation?.displayName      ?: "–"
            def solarDevName = solarForecastDevice?.displayName ?: "–"
            def gridDevName  = powerGridPresence?.displayName   ?: "–"

            // Table styles
            def r0 = "background:#f5f5f5;"
            def r1 = "background:#ffffff;"
            def th = "background:#c5d9ea; padding:4px 8px; text-align:left;"
            def tl = "padding:4px 8px; width:35%;"
            def td = "padding:4px 8px;"
            def ts = "padding:4px 8px; color:#666; font-size:0.9em; width:25%;"
            paragraph "<table style='width:100%; border-collapse:collapse;'>" +
                "<tr><th style='${th}'>Field</th><th style='${th}'>Value</th><th style='${th}'>Source</th></tr>" +
                "<tr style='${r0}'><td style='${tl}'><b>Charging Mode</b></td>             <td style='${td}'>${modeStr}</td>        <td style='${ts}'>Setting</td></tr>"         +
                "<tr style='${r1}'><td style='${tl}'><b>Powerwall Mode</b></td>            <td style='${td}'>${pwState}</td>        <td style='${ts}'>${pwDevName}</td></tr>"    +
                "<tr style='${r0}'><td style='${tl}'><b>Battery Level</b></td>             <td style='${td}'>${pwBattery}</td>      <td style='${ts}'>${pwDevName}</td></tr>"    +
                "<tr style='${r1}'><td style='${tl}'><b>Charge Target</b></td>             <td style='${td}'>${pwTargetStr}</td>    <td style='${ts}'>Calculated</td></tr>"      +
                "<tr style='${r0}'><td style='${tl}'><b>Solar Generation (today)</b></td>  <td style='${td}'>${genStr}</td>         <td style='${ts}'>${genDevName}</td></tr>"   +
                "<tr style='${r1}'><td style='${tl}'><b>Solar Forecast Low (24hr)</b></td> <td style='${td}'>${solarLowStr}</td>    <td style='${ts}'>${solarDevName}</td></tr>" +
                "<tr style='${r0}'><td style='${tl}'><b>Solar Forecast Mid (24hr)</b></td> <td style='${td}'>${solarStr}</td>       <td style='${ts}'>${solarDevName}</td></tr>" +
                "<tr style='${r1}'><td style='${tl}'><b>Solar Forecast High (24hr)</b></td><td style='${td}'>${solarHighStr}</td>   <td style='${ts}'>${solarDevName}</td></tr>" +
                "<tr style='${r0}'><td style='${tl}'><b>Trend Analysis</b></td>            <td style='${td}'>${trendStr}</td>       <td style='${ts}'>Calculated</td></tr>"      +
                "<tr style='${r1}'><td style='${tl}'><b>Target Calculation</b></td>        <td style='${td}'>${chargeCalcStr}</td>  <td style='${ts}'>Calculated</td></tr>"      +
                "<tr style='${r0}'><td style='${tl}'><b>Current Temperature</b></td>       <td style='${td}'>${currentTempStr}</td> <td style='${ts}'>${stationName}</td></tr>"  +
                "<tr style='${r1}'><td style='${tl}'><b>Today's Max Temperature</b></td>   <td style='${td}'>${dailyMaxStr}</td>    <td style='${ts}'>${stationName}</td></tr>"  +
                "<tr style='${r0}'><td style='${tl}'><b>Severe Weather Warning</b></td>    <td style='${td}'>${severeStr}</td>      <td style='${ts}'>Calculated</td></tr>"      +
                "<tr style='${r1}'><td style='${tl}'><b>Grid Status</b></td>               <td style='${td}'>${gridStr}</td>        <td style='${ts}'>${gridDevName}</td></tr>"  +
                "</table>"
        }

        section("<b>Charging Mode</b>") {
            input "chargingMode", "enum",
                  title: "<b>Active charging mode</b>",
                  options: ["standard": "Off-peak Charging – smart, cost-optimised grid charging",
                            "free":     "Free Off-peak Charging – free window plus solar-soak top-up",
                            "none":     "None – no automatic charging"],
                  defaultValue: "standard", required: true, submitOnChange: true
            def modeNote = (chargingMode == "free")
                ? "Charging to full during the free window, then topping up during solar soak if still " +
                  "below target. The <b>Charge Level</b> and <b>Off-peak Charging</b> pages are inactive — " +
                  "solar forecasting and the late-start calculation only matter when grid energy costs money."
                : (chargingMode == "none")
                ? "No automatic charging. Severe weather, extreme weather and grid outage handling all " +
                  "remain active — use <b>Disable All Features</b> in Overrides to stop those too."
                : "Smart cost-optimised charging: solar forecast selection, solar-noon trend analysis and " +
                  "late-start timing. The <b>Free Off-peak Charging</b> page is inactive."
            paragraph modeNote
        }

        section("<b>Devices</b>") {
            input "powerwallDevice", "capability.battery",
                  title: "Powerwall Device", required: true, multiple: false
            input "openWeatherDevice", "capability.sensor",
                  title: "OpenWeather Alerts Device", required: true, multiple: false
            input "powerGridPresence", "capability.presenceSensor",
                  title: "Power Grid Virtual Presence Sensor", required: true, multiple: false
            input "weatherStation", "capability.temperatureMeasurement",
                  title: "Weather Station (temperature sensor – daily max tracked internally)",
                  required: false, multiple: false
            input "solarForecastDevice", "capability.energyMeter",
                  title: "Solar Forecast Device (Solcast driver – uses '24_Hour_Estimate' attribute)",
                  required: false, multiple: false
            input "solarGenerationDevice", "capability.energyMeter",
                  title: "Solar Generation Device (daily actual generation – uses 'energy' attribute in kWh; " +
                         "required for solar-noon trend analysis, which selects the Low/Mid/High forecast)",
                  required: false, multiple: false
        }

        section("<b>Hub Variables</b>") {
            def okStyle  = "color:#1a7f37;"
            def badStyle = "color:#a12622;"
            def varRow   = { String name, String type ->
                def v = getGlobalVar(name)
                "<b>${name}</b> &nbsp;${type} &nbsp;" +
                (v != null ? "<span style='${okStyle}'>✓ present (currently ${v.value})</span>"
                           : "<span style='${badStyle}'>✗ missing – will be created when you press Done</span>")
            }
            paragraph "The app creates these automatically if they do not exist, and registers " +
                      "itself as a user so they cannot be deleted while it is installed " +
                      "(Settings → Hub Variables):<br><br>" +
                      varRow("PW_Charge_Target", "Number") + "<br>" +
                      varRow("SevereWeatherWarnings", "Boolean")
        }

        section("<b>Configuration</b>") {
            def chargeLevelDesc = (chargeWindowStart && chargeWindowEnd)
                ? "Window: ${toDate(chargeWindowStart).format('HH:mm')}–${toDate(chargeWindowEnd).format('HH:mm')}" +
                  (numPowerwalls           ? " | ${numPowerwalls} × 13.5 kWh"             : "") +
                  (annualAvgConsumptionKwh ? " | Avg: ${annualAvgConsumptionKwh} kWh/day" : "")
                : "Solar-aware gap calculation, hot-day override, vacation handling"
            def offPeakDesc = (offPeakStart && offPeakEnd)
                ? "Window: ${toDate(offPeakStart).format('HH:mm')}–${toDate(offPeakEnd).format('HH:mm')}" +
                  (maxChargeRateKw ? " | Rate: ${maxChargeRateKw} kW" : "")
                : "Daytime solar charging window and mode restrictions"
            def severeDesc = weatherLocations
                ? "Locations: ${weatherLocations}"
                : "Region and keyword-based alert monitoring"
            def extremeDesc = (extremeWindowStart && extremeWindowEnd)
                ? "Window: ${toDate(extremeWindowStart).format('HH:mm')}–${toDate(extremeWindowEnd).format('HH:mm')}" +
                  (extremeTempThreshold ? " | Threshold: ${extremeTempThreshold}°C" : "")
                : "Backup-Only triggers and grid failure response"
            def freeDesc = (freeOffPeakStart && freeOffPeakEnd)
                ? "Free: ${toDate(freeOffPeakStart).format('HH:mm')}–${toDate(freeOffPeakEnd).format('HH:mm')}" +
                  ((freeTopUpEnabled != false && freeTopUpEnd)
                      ? " | Top-up to ${toDate(freeTopUpEnd).format('HH:mm')}" : "")
                : "Free window charging plus optional solar-soak top-up"

            // Pages that only apply to the inactive mode are tagged rather than hidden — you
            // need to be able to configure a mode before switching to it
            def inactive     = " &nbsp;<i>(inactive)</i>"
            boolean freeMode = (chargingMode == "free")
            boolean noneMode = (chargingMode == "none")
            href "chargeLevelPage",    title: "Charge Level" + ((freeMode || noneMode) ? inactive : ""),
                                       description: chargeLevelDesc
            href "offPeakPage",        title: "Off-Peak Charging" + ((freeMode || noneMode) ? inactive : ""),
                                       description: offPeakDesc
            href "freeOffPeakPage",    title: "Free Off-Peak Charging" + (freeMode ? "" : inactive),
                                       description: freeDesc
            href "severeWeatherPage",  title: "Severe Weather Warnings",       description: severeDesc
            href "extremeWeatherPage", title: "Extreme Weather & Grid Outage", description: extremeDesc
        }

        section("<b>Overrides</b>") {
            input "masterDisable", "bool",
                  title: "<b>Disable All Features</b>",
                  description: "Master kill switch. When on, the app stops managing the Powerwall entirely — no charge target updates, no mode changes, no weather or grid response. The Powerwall is left in its current state. Use this to hand full control back to the Tesla app.",
                  defaultValue: false
            input "vacationDisableOffPeak", "bool",
                  title: "<b>Vacation Mode</b> – Disable Off-Peak Charging",
                  description: "Disables off-peak charging and hot-day pre-charging. Extreme weather Backup-Only mode still active.",
                  defaultValue: false
        }

        section("<b>Logging</b>") {
            input "logEnable", "bool", title: "<b>Enable debug logging</b>", defaultValue: false
        }
    }
}

// ── Charge Level ──────────────────────────────────────────────────────────────

def chargeLevelPage() {
    dynamicPage(name: "chargeLevelPage", title: "Charge Level Settings") {
        def sA = "background-color:#e8f0f8; padding:2px 6px; border-radius:3px;"
        section("<span style='${sA}'><b>Evaluation Window</b></span>") {
            paragraph "PW_Charge_Target is recalculated every 15 minutes within this window."
            input "chargeWindowStart", "time", title: "<b>Start</b>", defaultValue: "05:59", required: true
            input "chargeWindowEnd",   "time", title: "<b>End</b>",   defaultValue: "14:05", required: true
        }

        section("<span style='${sA}'><b>Priority Overrides</b></span>") {
            paragraph "These are evaluated before the solar-aware calculation."
            paragraph "<b>Vacation mode</b> always sets charge target to 0."
            input "severeWeatherCharge", "number",
                  title: "<b>Charge target when Severe Weather Warning is active</b> (%)",
                  defaultValue: 100, required: true, range: "0..100"
            input "hotDayThreshold", "decimal",
                  title: "<b>Forecast high temperature to force 100% target</b> (°C)",
                  defaultValue: 26.0, required: true
            input "hotDayWindowStart", "time",
                  title: "<b>Hot-day window start</b>", defaultValue: "11:58", required: true
            input "hotDayWindowEnd",   "time",
                  title: "<b>Hot-day window end</b>",   defaultValue: "15:00", required: true
        }

        section("<span style='${sA}'><b>Solar-Aware Charge Target</b></span>") {
            paragraph "The goal is to have the battery as full as possible at the start of peak tariff. " +
                      "Solar during the day charges the battery first; off-peak grid charging fills whatever solar won't provide:<br><br>" +
                      "<b>solar before peak  = 24hr forecast × 90%</b><br>" +
                      "<b>daytime house load = daily consumption × (8hrs ÷ 24)</b><br>" +
                      "<b>solar → battery   = max(0, solar before peak − daytime load), capped at capacity</b><br>" +
                      "<b>pre-charge target = battery capacity − solar → battery</b><br><br>" +
                      "Poor forecast (solar ≤ daytime load): house uses all generation, battery gets nothing → <b>100% pre-charge</b>.<br>" +
                      "Good forecast (large surplus): solar fills the battery → <b>low or zero pre-charge</b>.<br><br>" +
                      "Consumption is seasonally adjusted via a built-in Southern Hemisphere curve (±25%)."
            input "numPowerwalls", "number",
                  title: "<b>Number of Powerwalls</b> (each 13.5 kWh — sets total battery capacity)",
                  required: true, defaultValue: 1, range: "1..10"
            input "annualAvgConsumptionKwh", "decimal",
                  title: "<b>Annual average daily household consumption</b> (kWh/day)",
                  required: true
        }

        section("<span style='${sA}'><b>Forecast Estimate Selection</b></span>") {
            def noonNow = getSolarNoon()
            paragraph "The app starts each day on the <b>middle</b> Solcast estimate. At <b>solar noon</b> " +
                      "— when exactly half the day's generation should be complete — it projects actual " +
                      "generation to a full-day total and compares that against the morning's forecast band, " +
                      "switching to Low or High if the day is clearly tracking one of them.<br><br>" +
                      "<b>projected day total = generation so far ÷ fraction of solar day elapsed</b><br>" +
                      "(at solar noon that fraction is exactly 0.50, so the projection is simply generation × 2)<br><br>" +
                      "The baseline band is captured from the first forecast poll of the day (~08:58). It has to " +
                      "predate the morning: Solcast uses satellite cloud imagery, so a midday forecast already " +
                      "reflects the morning's weather and comparing against it would be circular.<br><br>" +
                      "What this actually detects is <b>your array's performance against modelled irradiance</b> — " +
                      "Solcast predicts the sun better than any local heuristic, but it cannot see panel soiling, " +
                      "new shading, inverter derating or a dropped string." +
                      (noonNow ? "<br><br>Solar noon today: <b>${noonNow.format('HH:mm')}</b>" : "") +
                      "<br><br>Requires the <b>Solar Generation Device</b> to be set in the Devices section; " +
                      "without it the middle estimate is always used."
            input "forecastUpgradeBias", "number",
                  title: "<b>Upgrade bias</b> (%) – how far the projection must travel toward the next-higher " +
                         "estimate before it is selected",
                  defaultValue: 60, required: true, range: "50..90"
            paragraph "<small>50% is a neutral nearest-match. Higher values make the app slower to trust the " +
                      "High estimate, which is the safer direction: under-charging costs a peak-rate import, " +
                      "while over-charging only costs the off-peak/feed-in spread.</small>"
        }
    }
}

// ── Free Off-Peak Charging ────────────────────────────────────────────────────

def freeOffPeakPage() {
    dynamicPage(name: "freeOffPeakPage", title: "Free Off-Peak Charging Settings") {
        def sA = "background-color:#e8f0f8; padding:2px 6px; border-radius:3px;"

        section("<span style='${sA}'><b>How This Mode Works</b></span>") {
            paragraph "For tariffs with a zero-cost midday window (Victoria's 'midday saver' and " +
                      "equivalents). When grid energy is free there is no optimisation problem left, so this " +
                      "mode ignores the solar forecast entirely and simply fills the battery.<br><br>" +
                      "<b>Free window</b> – hold Backup-Only for the whole window regardless of battery level. " +
                      "Backup-Only means the house runs on free grid power while the battery charges and never " +
                      "discharges. Dropping to Self-Powered on reaching target — correct for paid off-peak — " +
                      "would waste free hours draining the battery.<br><br>" +
                      "<b>Solar-soak top-up</b> – on these tariffs the solar-soak period is usually the " +
                      "<i>cheapest paid window of the day</i>, and it sits immediately before peak. If the free " +
                      "window left the battery short, topping up here is the cheapest possible insurance " +
                      "against importing at peak rates. Charging stops as soon as the battery is full.<br><br>" +
                      "At the end of the last active window the Powerwall returns to Self-Powered for peak."
        }

        section("<span style='${sA}'><b>Free Window</b></span>") {
            paragraph "The zero-cost period. Charging runs for the entire window, unconditionally."
            input "freeOffPeakStart", "time",
                  title: "<b>Free window start</b>", defaultValue: "11:00", required: true
            input "freeOffPeakEnd",   "time",
                  title: "<b>Free window end</b>",   defaultValue: "14:00", required: true
        }

        section("<span style='${sA}'><b>Solar-Soak Top-Up</b></span>") {
            paragraph "Runs from the end of the free window until the time below, and only while the battery " +
                      "is under 99% (the Backup-Only ceiling). Leave enabled unless you want strictly " +
                      "zero-cost charging and are willing to enter peak with a part-full battery."
            input "freeTopUpEnabled", "bool",
                  title: "<b>Top up during solar soak</b>", defaultValue: true
            input "freeTopUpEnd", "time",
                  title: "<b>Top-up end</b> (peak period start)", defaultValue: "16:00", required: false
        }

        section("<span style='${sA}'><b>Window End Handling</b></span>") {
            input "freeOffPeakCloseoutMinutes", "number",
                  title: "<b>Force Self-Powered</b> this many minutes before the last window ends",
                  defaultValue: 5, required: true, range: "1..30"
        }

        section("<span style='${sA}'><b>Restrictions</b></span>") {
            paragraph "Both optional. Victoria's scheme currently runs seven days a week, but other states " +
                      "and future retailer variations may not."
            input "freeOffPeakDays", "enum",
                  title: "<b>Only on these days</b> (leave blank for every day)",
                  options: ["Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday"],
                  multiple: true, required: false
            input "freeOffPeakModes", "mode",
                  title: "<b>Only in these hub modes</b> (leave blank for all modes)",
                  multiple: true, required: false
            paragraph "<small>Unlike standard off-peak charging, this mode ignores Vacation Mode — free power " +
                      "is worth taking whether or not anyone is home.</small>"
        }
    }
}

// ── Off-Peak Charging ─────────────────────────────────────────────────────────

def offPeakPage() {
    dynamicPage(name: "offPeakPage", title: "Off-Peak Charging Settings") {
        def sA = "background-color:#e8f0f8; padding:2px 6px; border-radius:3px;"
        section("<span style='${sA}'><b>Charging Window</b></span>") {
            paragraph "The app calculates how long charging will take and starts as late as possible " +
                      "so the Powerwall finishes just before the window ends. " +
                      "If there is not enough time from the window start, charging begins immediately.<br><br>" +
                      "<b>Early-start hedge:</b> the forecast estimate is not selected until solar noon, so " +
                      "before then the app sizes the worst case — the target it would need if the day turns " +
                      "out to be tracking the <b>Low</b> estimate. If that charge would not fit in the window " +
                      "remaining after solar noon, charging begins at the window start instead of waiting for " +
                      "information that would arrive too late to act on. This adapts automatically across the " +
                      "year: the post-solar-noon window is around 2h35m midwinter but only about 1h35m " +
                      "midsummer, so early starts correctly become more common in summer."
            input "offPeakStart", "time",
                  title: "<b>Earliest charging start</b> (also the early-start hedge time)",
                  defaultValue: "09:00", required: true
            input "offPeakEnd",   "time", title: "<b>Window end</b> (target finish time)", defaultValue: "15:00", required: true
            input "offPeakCloseoutMinutes", "number",
                  title: "<b>Force Self-Powered</b> this many minutes before window end",
                  defaultValue: 5, required: true, range: "1..30"
        }

        section("<span style='${sA}'><b>Powerwall Charge Rate</b></span>") {
            paragraph "Used to calculate how long charging will take. " +
                      "If the Powerwall is already charging, its live power reading is used instead."
            input "maxChargeRateKw", "decimal",
                  title: "<b>Assumed charge rate</b> (kW) – real-world average accounting for solar and house load", defaultValue: 3.0, required: true
        }

        section("<span style='${sA}'><b>Mode Restriction</b></span>") {
            input "offPeakModes", "mode",
                  title: "<b>Only run in these modes</b> (leave blank for all modes)",
                  multiple: true, required: false
        }
    }
}

// ── Severe Weather ────────────────────────────────────────────────────────────

def severeWeatherPage() {
    dynamicPage(name: "severeWeatherPage", title: "Severe Weather Warnings") {
        def sA = "background-color:#e8f0f8; padding:2px 6px; border-radius:3px;"
        section("<span style='${sA}'><b>Your Region</b></span>") {
            paragraph "Enter the region name(s) that appear in weather alerts for your area. " +
                      "Matching is <b>case-insensitive</b> — one entry covers all capitalisation variants."
            input "weatherLocations", "text",
                  title: "<b>Region / location names</b> (comma-separated)",
                  defaultValue: "Melbourne, Central Ranges", required: true
        }

        section("<span style='${sA}'><b>Alert Keywords</b></span>") {
            paragraph "An alert that matches your region AND contains any of these keywords " +
                      "will set SevereWeatherWarnings to true. Case-insensitive."
            input "weatherKeywords", "text",
                  title: "<b>Keywords</b> (comma-separated)",
                  defaultValue: "severe, damaging, destructive", required: true
        }

        section("<span style='${sA}'><b>Extreme Heat Fallback</b></span>") {
            paragraph "If no alert keyword match is found but today's forecast maximum " +
                      "temperature meets or exceeds this value, SevereWeatherWarnings is still set to true."
            input "extremeHeatThreshold", "decimal",
                  title: "<b>Extreme heat temperature</b> (°C)", defaultValue: 35.0, required: true
        }
    }
}

// ── Extreme Weather & Grid Outage ─────────────────────────────────────────────

def extremeWeatherPage() {
    dynamicPage(name: "extremeWeatherPage", title: "Extreme Weather & Grid Outage") {
        def sA = "background-color:#e8f0f8; padding:2px 6px; border-radius:3px;"
        section("<span style='${sA}'><b>Backup Mode Window</b></span>") {
            paragraph "Outside solar hours, the Powerwall switches to Backup-Only when " +
                      "the forecasted high or SevereWeatherWarnings warrants it; " +
                      "otherwise it returns to Self-Powered."
            input "extremeWindowStart", "time",
                  title: "<b>Window Start</b> (after solar ends)", defaultValue: "15:04", required: true
            input "extremeWindowEnd",   "time",
                  title: "<b>Window End</b> (next morning)",       defaultValue: "08:58", required: true
            input "extremeTempThreshold", "decimal",
                  title: "<b>Forecast high → Backup-Only mode</b> (°C)", defaultValue: 35.0, required: true
        }

        section("<span style='${sA}'><b>Grid Outage</b></span>") {
            paragraph "When the Power Grid Virtual Presence sensor reports 'not present' (grid departed), " +
                      "the Powerwall is switched to Self-Powered mode immediately to preserve the battery."
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Lifecycle
// ─────────────────────────────────────────────────────────────────────────────

def installed() {
    log.info "Advanced Powerwall Manager: installed"
    initialize()
}

def updated() {
    log.info "Advanced Powerwall Manager: updated"
    unsubscribe()
    unschedule()
    initialize()
}

def uninstalled() {
    unsubscribe()
    unschedule()
}

def initialize() {
    // ── Master kill switch ────────────────────────────────────────────────────
    // updated()/installed() already called unsubscribe() + unschedule() before this,
    // so returning here leaves the app with no subscriptions, no schedules, and no
    // startup evaluation — it takes no further action and the Powerwall is left as-is.
    if (masterDisable) {
        log.warn "Advanced Powerwall Manager: ALL FEATURES DISABLED – no subscriptions, schedules, or actions registered"
        return
    }

    // ── Hub variables ─────────────────────────────────────────────────────────
    // Created automatically if missing. Must run before any handler reads or writes them.
    ensureRequiredGlobalVars()

    // ── Powerwall ─────────────────────────────────────────────────────────────
    subscribe(powerwallDevice, "currentOpState", "powerwallStateHandler")
    subscribe(powerwallDevice, "battery",        "batteryLevelHandler")

    // ── Solar forecast ────────────────────────────────────────────────────────
    if (solarForecastDevice) {
        subscribe(solarForecastDevice, "24_Hour_Estimate",      "solarForecastHandler")
        subscribe(solarForecastDevice, "24_Hour_Estimate_Low",  "solarForecastHandler")
        subscribe(solarForecastDevice, "24_Hour_Estimate_High", "solarForecastHandler")
        // Seed the baseline if today's forecast has already landed (e.g. app saved mid-morning);
        // no-op before the first poll of the day or if a baseline is already stored
        captureForecastSnapshot()
    }

    // ── Solar generation (drives the solar-noon trend analysis) ───────────────
    if (solarGenerationDevice) {
        subscribe(solarGenerationDevice, "energy", "solarGenerationHandler")
    }

    // ── OpenWeather ───────────────────────────────────────────────────────────
    subscribe(openWeatherDevice, "alertDescrFull", "severeWeatherHandler")   // severe weather
    subscribe(openWeatherDevice, "forecastHigh",   "forecastHighHandler")    // hot-day + extreme weather

    // ── Weather station: daily max temp tracking ──────────────────────────────
    // Midnight reset is always scheduled — it clears the forecast baseline and daily flags
    // (chargeTargetReachedToday, selectedEstimate) that are used regardless of whether a
    // weather station is configured. Scheduling it unconditionally prevents that state from
    // getting permanently stuck if weatherStation is removed after initial setup.
    schedule("0 0 0 * * ?", "resetDailyMaxTemp")
    if (weatherStation) {
        subscribe(weatherStation, "temperature", "temperatureHandler")
        def current = weatherStation.currentValue("temperature")?.toDouble()
        if (current != null) {
            if (state.dailyMaxTemp == null || current > state.dailyMaxTemp) {
                state.dailyMaxTemp = current
                log.info "Seeded dailyMaxTemp = ${current}°C"
            }
        }
    }

    // ── Grid presence ─────────────────────────────────────────────────────────
    subscribe(powerGridPresence, "presence", "gridPresenceHandler")

    // ── Mode changes (vacation mode affects charge target) ────────────────────
    subscribe(location, "mode", "modeChangeHandler")

    // ── Scheduled safety nets ─────────────────────────────────────────────────
    // Primary logic is event-driven above; these catch any missed events
    runEvery15Minutes("chargeCheckHandler")
    runEvery1Minute("offPeakCloseoutHandler")

    // Fire one minute after the extreme-weather window opens, derived from the setting rather
    // than hardcoded to 15:05 — otherwise moving extremeWindowStart later (e.g. 16:04 on a
    // midday-saver tariff where peak begins at 4pm) means nothing fires when the window opens
    def ewFire = new Date(toDate(extremeWindowStart).time + 60000L)
    schedule("0 ${ewFire.format('m')} ${ewFire.format('H')} * * ?", "extremeWeatherHandler")

    log.info "Initialized – mode: ${chargingMode ?: 'standard'}, " +
             "chargeWindow: ${chargeWindowStart}–${chargeWindowEnd}, " +
             (chargingMode == "free" ? "free: ${freeOffPeakStart}–${freeOffPeakEnd}, "
                                     : "offPeak: ${offPeakStart}–${offPeakEnd}, ") +
             "extremeWeather trigger: ${ewFire.format('HH:mm')}, " +
             "locations: [${weatherLocations}], keywords: [${weatherKeywords}]"

    // Evaluate all conditions immediately so the app is in the correct state on startup
    // rather than waiting up to 15 minutes for the first scheduled trigger
    log.info "── Startup evaluation ──────────────────────────────────"
    severeWeatherHandler(null)   // sets SevereWeatherWarnings first — chargeCheckHandler reads it
    chargeCheckHandler()         // sets charge target and evaluates off-peak charging
    extremeWeatherHandler()      // checks extreme weather window and adjusts Powerwall mode
}

// ─────────────────────────────────────────────────────────────────────────────
// Rule 1 + 2: Charge Level & Off-Peak (every 15 minutes)
// ─────────────────────────────────────────────────────────────────────────────

def chargeCheckHandler(evt = null) {
    log.info "── 15-min check ──────────────────────────────────────"
    def now = new Date()

    // ── Forecast estimate selection (trend analysis from solar noon onward) ───
    if (solarForecastDevice && solarGenerationDevice) {
        def previousSelection = state.selectedEstimate
        def selection         = selectForecastEstimate()
        state.selectedEstimate = selection
        if (previousSelection != null && previousSelection != selection) {
            log.info "Forecast estimate changed: ${previousSelection} → ${selection}"
        }
    }

    // ── Rule 1: Update PW_Charge_Target ───────────────────────────────────────
    // The charge evaluation window exists only to schedule the solar surplus calculation, so
    // it is bypassed in free and none modes where the target is a fixed value.
    boolean fixedTarget = (chargingMode == "free" || chargingMode == "none")
    if (fixedTarget || timeOfDayIsBetween(toDate(chargeWindowStart), toDate(chargeWindowEnd), now, location.timeZone)) {
        def previous = getGlobalVar("PW_Charge_Target")?.value?.toInteger()
        // Powerwall cannot exceed 99% in Backup-Only mode — cap before storing so all
        // downstream references (hub variable, status panel, off-peak check) see 99%
        def target   = Math.min(calculateChargeTarget(), 99)
        if (target != previous) {
            log.info "Charge target changed: ${previous}% → ${target}%"
        } else {
            logDebug "Charge target unchanged at ${target}%"
        }
        setGlobalVar("PW_Charge_Target", target)
    } else {
        logDebug "Rule 1: outside charge window, skipping"
    }

    // ── Rule 2 (15-min part): charging management, per selected mode ─────────
    if (chargingMode == "free") {
        freeOffPeakCheck(now)
    } else if (chargingMode == "none") {
        logDebug "Charging mode 'None' – no charging management"
    } else {
        offPeakChargeCheck(now)
    }
}

def calculateChargeTarget() {
    // Free off-peak mode short-circuits the entire priority ladder. Every branch below would
    // land on 99 anyway: severe weather and hot-day both request 100 (capped to 99), and the
    // vacation branches are deliberately bypassed because free power is worth taking whether
    // or not anyone is home. The solar surplus model is irrelevant when energy costs nothing.
    if (chargingMode == "free") {
        logDebug "Charge target: free off-peak mode → 99% (unconditional)"
        return 99
    }

    // No automatic charging — weather and grid handling stay active, only charging stops
    if (chargingMode == "none") {
        logDebug "Charge target: charging mode 'None' → 0%"
        return 0
    }

    // Priority 1: Vacation mode
    if (location.mode == "Vacation") {
        log.info "Charge target: Vacation mode active → 0%"
        return 0
    }

    // Priority 2: Active severe weather warning
    if (getSevereWeatherWarnings()) {
        def t = (severeWeatherCharge ?: 100) as Integer
        log.info "Charge target: Severe weather warning active → ${t}%"
        return t
    }

    // Priority 3: Vacation override toggle — exit early so neither the hot-day check
    // nor the solar surplus model runs (Priority 1 already catches hub Vacation mode;
    // this toggle lets vacation mode be set independently of the hub mode)
    if (vacationDisableOffPeak) {
        log.info "Charge target: vacation override active → 0%"
        return 0
    }

    // Priority 4: Hot-day forecast during hot-day window
    def now = new Date()
    if (timeOfDayIsBetween(toDate(hotDayWindowStart), toDate(hotDayWindowEnd), now, location.timeZone)) {
        def forecastHigh = getAttr(openWeatherDevice, "forecastHigh")?.toDouble()
        def threshold    = (hotDayThreshold ?: 26.0).toDouble()
        if (forecastHigh != null && forecastHigh >= threshold) {
            log.info "Charge target: hot day override (forecast ${forecastHigh}°C ≥ ${threshold}°C) → 100%"
            return 100
        }
    }

    // Priority 5: Solar surplus model
    // Goal: fill the battery with whatever solar won't provide during the day.
    // Poor solar day  → house uses all generation, battery gets nothing → 100% pre-charge.
    // Good solar day  → solar surplus charges the battery → low/zero pre-charge needed.
    def dailyConsumption = getDailyConsumption()
    if (dailyConsumption == null) {
        log.warn "Charge target: annual consumption not configured → 0%"
        return 0
    }

    double solarForecast = (getSolarForecast() ?: 0.0d).toDouble()
    def    target        = computeSolarModelTarget(solarForecast, dailyConsumption)

    double totalCapacityKwh   = (numPowerwalls as Integer) * 13.5d
    double solarDaytime       = solarForecast * 0.90d
    double consumptionDaytime = dailyConsumption.toDouble() * (8.0d / 24.0d)
    double solarToBattery     = Math.min(totalCapacityKwh, Math.max(0.0d, solarDaytime - consumptionDaytime))

    log.info "Charge target: solar=${solarForecast} kWh (${solarDaytime.round(1)} kWh before peak), " +
             "daytime load=${consumptionDaytime.round(1)} kWh, " +
             "surplus→battery=${solarToBattery.round(1)} kWh / ${totalCapacityKwh} kWh capacity → ${target}%"
    return target
}

/**
 * The solar surplus model, parameterised by forecast so it can be evaluated against any of
 * the three Solcast estimates. calculateChargeTarget() runs it on the selected estimate;
 * the off-peak early-start hedge runs it on the Low estimate to size the worst case.
 *
 * Returns the pre-charge target as a percentage (0–100).
 */
private Integer computeSolarModelTarget(double solarForecastKwh, Double dailyConsumptionKwh) {
    if (dailyConsumptionKwh == null || !numPowerwalls) return 0

    double totalCapacityKwh   = (numPowerwalls as Integer) * 13.5d

    // ~90% of solar generation occurs before peak start (sun is declining after 3pm)
    double solarDaytime       = solarForecastKwh * 0.90d

    // House load during solar generation hours (assume 8-hour solar day for AU)
    double consumptionDaytime = dailyConsumptionKwh.toDouble() * (8.0d / 24.0d)

    // Solar surplus after covering daytime house load — available to charge the battery
    double solarSurplus       = Math.max(0.0d, solarDaytime - consumptionDaytime)
    double solarToBattery     = Math.min(totalCapacityKwh, solarSurplus)

    // Pre-charge = fill the remainder that solar won't provide
    double targetKwh          = Math.max(0.0d, totalCapacityKwh - solarToBattery)
    return Math.min(100, ((targetKwh / totalCapacityKwh) * 100.0d).toInteger())
}

// ─────────────────────────────────────────────────────────────────────────────
// Rule 2 (alternative): Free Off-Peak Charging
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Free off-peak charging: two stages, no optimisation.
 *
 *   Free window      – hold Backup-Only unconditionally. The house runs on free grid power
 *                      while the battery charges and never discharges. Dropping to
 *                      Self-Powered on reaching target (correct for paid off-peak) would
 *                      spend free hours draining the battery.
 *   Solar-soak top-up – hold Backup-Only only while below 99%. On these tariffs solar soak is
 *                      typically the cheapest paid window of the day and sits immediately
 *                      before peak, making it the cheapest insurance against a peak import.
 *
 * Vacation Mode is deliberately not honoured here — free power is worth taking regardless.
 */
def freeOffPeakCheck(Date now = new Date()) {
    if (chargingMode != "free") return

    // Defaults are only applied once the page has been opened, so a user who selects free
    // mode without visiting its settings page would otherwise hit a null time value here
    if (!freeOffPeakStart || !freeOffPeakEnd) {
        log.warn "Free off-peak: window not configured – open the Free Off-Peak Charging page and press Done"
        return
    }

    if (!isFreeOffPeakDay(now)) {
        logDebug "Free off-peak: skipped (not an active day)"
        return
    }
    if (freeOffPeakModes && !freeOffPeakModes.contains(location.mode)) {
        logDebug "Free off-peak: skipped (mode '${location.mode}' not in allowed modes)"
        return
    }
    // Grid outage wins: gridPresenceHandler puts the Powerwall into Self-Powered to preserve
    // the battery, and there is no free grid energy to import during an outage anyway
    if (powerGridPresence && getAttr(powerGridPresence, "presence") != "present") {
        log.info "Free off-peak: skipped (grid not present)"
        return
    }

    def opState = getAttr(powerwallDevice, "currentOpState")
    def battery = getAttr(powerwallDevice, "battery")?.toDouble()

    // ── Stage 1: free window — charge unconditionally ─────────────────────────
    if (timeOfDayIsBetween(toDate(freeOffPeakStart), toDate(freeOffPeakEnd), now, location.timeZone)) {
        if (opState != "Backup-Only") {
            setPowerwallBackupOnly("free window – zero-cost grid energy (battery ${battery}%)")
        } else {
            logDebug "Free off-peak: in free window, already Backup-Only (battery ${battery}%)"
        }
        return
    }

    // ── Stage 2: solar-soak top-up — charge only while below target ───────────
    if (freeTopUpEnabled == false || !freeTopUpEnd) return
    if (!timeOfDayIsBetween(toDate(freeOffPeakEnd), toDate(freeTopUpEnd), now, location.timeZone)) return

    if (battery == null) { log.warn "Free off-peak top-up: battery level unavailable"; return }

    if (battery >= 99) {
        if (opState != "Self-Powered") {
            setPowerwallSelfPowered("solar-soak top-up complete – battery ${battery}%")
        } else {
            logDebug "Free off-peak top-up: battery full (${battery}%), already Self-Powered"
        }
        return
    }

    if (opState != "Backup-Only") {
        setPowerwallBackupOnly("solar-soak top-up – cheapest paid window before peak (${battery}% → 99%)")
    } else {
        logDebug "Free off-peak top-up: charging in progress (${battery}% → 99%)"
    }
}

/** True if free off-peak charging runs today (blank day list means every day). */
private boolean isFreeOffPeakDay(Date now = new Date()) {
    if (!freeOffPeakDays) return true
    return freeOffPeakDays.contains(now.format("EEEE", location.timeZone))
}

/**
 * End of the last active charging window for the selected mode — the point the closeout
 * handler must return the Powerwall to Self-Powered by. In free mode that is the top-up end
 * when top-up is enabled, otherwise the free window end.
 */
private Date getActiveWindowEnd() {
    if (chargingMode == "free" && freeOffPeakEnd) {
        return (freeTopUpEnabled != false && freeTopUpEnd) ? toDate(freeTopUpEnd) : toDate(freeOffPeakEnd)
    }
    return offPeakEnd ? toDate(offPeakEnd) : null
}

/**
 * True while free off-peak charging owns the Powerwall's mode (free window through top-up end).
 * Used to stop extremeWeatherCheck() from forcing Self-Powered mid-window when weather
 * conditions clear — the free-mode handler is authoritative during this period.
 */
private boolean isFreeChargingWindow(Date now = new Date()) {
    if (chargingMode != "free") return false
    if (!freeOffPeakStart || !freeOffPeakEnd) return false
    if (!isFreeOffPeakDay(now)) return false
    def windowEnd = getActiveWindowEnd()
    if (windowEnd == null) return false
    return timeOfDayIsBetween(toDate(freeOffPeakStart), windowEnd, now, location.timeZone)
}

// ─────────────────────────────────────────────────────────────────────────────
// Rule 2: Off-Peak Charging
// ─────────────────────────────────────────────────────────────────────────────

def offPeakChargeCheck(Date now = new Date()) {
    if (vacationDisableOffPeak) {
        log.info "Off-peak check: skipped (vacation override active)"
        return
    }
    if (!timeOfDayIsBetween(toDate(offPeakStart), toDate(offPeakEnd), now, location.timeZone)) return
    if (!isOffPeakModeActive()) {
        log.info "Off-peak check: skipped (mode '${location.mode}' not in allowed modes)"
        return
    }
    // The forecast device polls on a schedule (first call ~08:58). Before that the estimates
    // still hold yesterday's values, so any target derived from them is meaningless — never
    // commit to grid import on stale data.
    if (solarForecastDevice && isForecastStale()) {
        log.warn "Off-peak check: skipped (solar forecast stale – awaiting today's first update)"
        return
    }

    def opState      = getAttr(powerwallDevice, "currentOpState")
    def batteryLevel = getAttr(powerwallDevice, "battery")?.toDouble()

    if (batteryLevel == null) { log.warn "Off-peak: battery level unavailable"; return }

    // chargeTarget is already capped at 99% when written to PW_Charge_Target by chargeCheckHandler
    def chargeTarget = getGlobalVar("PW_Charge_Target")?.value?.toInteger() ?: 0
    log.info "Off-peak check: opState=${opState}, battery=${batteryLevel}%, target=${chargeTarget}%"

    // Target already met (or no charge needed)
    if (batteryLevel >= chargeTarget) {
        if (chargeTarget == 0) {
            logDebug "Off-peak: target is 0% (solar forecast covers consumption), no pre-charge needed"
        } else if (isExtremeConditionActive()) {
            // Extreme/severe weather warrants Backup-Only — don't switch to Self-Powered just
            // because the charge target was met; delegate to extremeWeatherCheck() instead.
            log.info "Off-peak: charge target met (${batteryLevel}% ≥ ${chargeTarget}%) but extreme/severe weather active – delegating mode decision to extremeWeatherCheck()"
            extremeWeatherCheck()
        } else if (opState != "Self-Powered") {
            // Normal case: target met, no weather conditions — force Self-Powered.
            // Bypasses the 5-minute cooldown: device state can be stale so we must keep
            // sending the command until it is confirmed.
            log.info "Off-peak: charge target met (${batteryLevel}% ≥ ${chargeTarget}%) – forcing Self-Powered (bypassing cooldown)"
            powerwallDevice.setSelfPoweredMode()
            state.lastModeChangeMs = now()
        } else {
            logDebug "Off-peak: target met, already in Self-Powered"
        }
        state.chargeTargetReachedToday = true   // top-up sessions will use late-start only
        return
    }

    // Calculate latest start time to finish charging by window end
    def capacityKwh = numPowerwalls ? ((numPowerwalls as Integer) * 13.5) : 13.5
    def maxRateKw   = (maxChargeRateKw ?: 3.0).toDouble()

    // Use live power reading if already charging, otherwise use configured rate
    def liveRateKw = null
    if (opState == "Backup-Only") {
        def livePowerW = getAttr(powerwallDevice, "power")?.toDouble()
        if (livePowerW != null && livePowerW > 100) liveRateKw = livePowerW / 1000.0
    }
    def effectiveRateKw = liveRateKw ?: maxRateKw

    double kwhNeeded     = ((chargeTarget - batteryLevel) / 100.0d) * capacityKwh
    double hoursNeeded   = kwhNeeded / effectiveRateKw.toDouble()
    def    msNeeded      = (hoursNeeded * 3600d * 1000d).toLong()
    def    latestStartMs   = toDate(offPeakEnd).time - msNeeded
    def    latestStartDate = new Date(latestStartMs)

    // Once the target has been reached today, suppress the early start for any top-up
    // sessions — only the late-start calculation applies, so charging waits as long as possible
    boolean targetPreviouslyReached = state.chargeTargetReachedToday as Boolean

    // ── Early-start hedge ─────────────────────────────────────────────────────────────
    // The forecast selection is not made until solar noon. If the day then turns out to be
    // tracking the Low estimate, the target jumps and we need time to act on it — but by
    // then a large part of the window is gone. So size the worst case now (target computed
    // from the Low estimate) and, if that would not fit in the window remaining after solar
    // noon, begin at the window start rather than waiting for information we cannot use.
    //
    // This is self-tuning: the post-solar-noon window shrinks from ~2h35m midwinter to
    // ~1h35m midsummer, so early starts become correctly more common in summer.
    //
    // Only applies BEFORE solar noon. Once the selection has been made the real target is
    // authoritative, and continuing to act on the stale worst case would force an early start
    // on a day the trend analysis has since shown to be tracking High.
    boolean earlyStartNeeded = false
    def     solarNoonDate    = getSolarNoon()
    if (!targetPreviouslyReached && solarNoonDate != null && now.before(solarNoonDate)) {
        def worstCaseLowKwh = state.forecastSnapshotLow as Double
        if (worstCaseLowKwh != null) {
            def worstTarget = Math.min(computeSolarModelTarget(worstCaseLowKwh, getDailyConsumption()), 99)
            if (worstTarget > batteryLevel) {
                double worstKwh   = ((worstTarget - batteryLevel) / 100.0d) * capacityKwh
                double worstHours = worstKwh / effectiveRateKw.toDouble()
                double hoursAfterSolarNoon = (toDate(offPeakEnd).time - solarNoonDate.time) / 3600000.0d
                earlyStartNeeded  = worstHours > hoursAfterSolarNoon
                logDebug "Early-start hedge: worst case (Low estimate) ${worstTarget}% needs " +
                         "${worstHours.round(2)}h, ${hoursAfterSolarNoon.round(2)}h available after " +
                         "solar noon (${solarNoonDate.format('HH:mm')}) → earlyStart=${earlyStartNeeded}"
            }
        }
    }

    def windowStartMs      = toDate(offPeakStart).time
    def effectiveStartMs   = earlyStartNeeded ? Math.min(windowStartMs, latestStartMs) : latestStartMs
    def effectiveStartDate = new Date(effectiveStartMs)
    boolean usingEarlyStart = earlyStartNeeded && effectiveStartMs == windowStartMs && windowStartMs < latestStartMs

    log.info "Off-peak: need ${kwhNeeded.round(2)} kWh at ${effectiveRateKw} kW " +
             "= ${(hoursNeeded * 60.0d).round(0).toInteger()} min, " +
             "latest start: ${latestStartDate.format('HH:mm')}" +
             (usingEarlyStart ? " → overridden by early-start hedge: ${effectiveStartDate.format('HH:mm')}" : "") +
             (targetPreviouslyReached ? " (top-up mode – waiting for latest start)" : "")

    // Never start a new charge session inside the closeout window — the closeout
    // handler owns this period and must return the Powerwall to Self-Powered
    def closeoutMs = (offPeakCloseoutMinutes ?: 5) * 60 * 1000L
    if (now.time >= toDate(offPeakEnd).time - closeoutMs) {
        if (opState == "Backup-Only") {
            log.info "Off-peak: within closeout window and still charging – closeout handler will force Self-Powered"
        } else {
            log.info "Off-peak: within closeout window – not starting new charge session (${batteryLevel}% → ${chargeTarget}%)"
        }
        return
    }

    if (now.time >= effectiveStartMs) {
        if (opState != "Backup-Only") {
            def reason = usingEarlyStart
                ? "early-start hedge – worst case won't fit after solar noon – ${batteryLevel}% → ${chargeTarget}%"
                : "past latest start time – ${batteryLevel}% → ${chargeTarget}%"
            setPowerwallBackupOnly(reason)
        } else {
            log.info "Off-peak: charging in progress (${batteryLevel}% → ${chargeTarget}%)"
        }
    } else {
        def minsUntil = ((effectiveStartMs - now.time) / 60000).toLong()
        if (opState == "Backup-Only") {
            log.info "Off-peak: charging in progress, letting it run to target (${batteryLevel}% → ${chargeTarget}%)"
        } else {
            log.info "Off-peak: waiting ${minsUntil} min until start (${effectiveStartDate.format('HH:mm')})"
        }
    }
}

def offPeakCloseoutHandler(evt = null) {
    if (chargingMode == "none") return
    boolean freeMode = (chargingMode == "free")

    // Vacation Mode does not suppress free charging, so it must not suppress its closeout
    // either — otherwise the Powerwall would be left in Backup-Only into the peak period
    if (!freeMode && vacationDisableOffPeak) return
    if (freeMode && !isFreeOffPeakDay()) return

    // 1-minute check: force Self-Powered during the closeout period before the active
    // window ends. In free mode that window end is the top-up end (or the free window end
    // when top-up is disabled), which may differ from the standard off-peak window end.
    def now          = new Date()
    def windowEnd    = getActiveWindowEnd()
    if (windowEnd == null) return
    def closeoutMins = freeMode ? (freeOffPeakCloseoutMinutes ?: 5) : (offPeakCloseoutMinutes ?: 5)
    def closeoutMs   = closeoutMins * 60 * 1000L
    def closeoutDate = new Date(windowEnd.time - closeoutMs)

    if (!timeOfDayIsBetween(closeoutDate, windowEnd, now, location.timeZone)) return
    if (freeMode) {
        if (freeOffPeakModes && !freeOffPeakModes.contains(location.mode)) return
    } else if (!isOffPeakModeActive()) {
        return
    }

    // If extreme weather or severe weather conditions are active, the correct post-peak
    // mode is Backup-Only — delegate to extremeWeatherCheck() rather than forcing
    // Self-Powered and inadvertently clearing a weather-driven Backup-Only state.
    if (isExtremeConditionActive()) {
        log.info "Off-peak closeout: extreme/severe weather active – delegating mode decision to extremeWeatherCheck() instead of forcing Self-Powered"
        extremeWeatherCheck()
        return
    }

    // Normal case: force Self-Powered at the hard deadline — the peak period is about
    // to start and device state can be stale. Bypasses the mode-change cooldown intentionally.
    def opState = getAttr(powerwallDevice, "currentOpState")
    log.info "Off-peak closeout: forcing Self-Powered (reported state: ${opState})"
    powerwallDevice.setSelfPoweredMode()
    state.lastModeChangeMs = now()
}

// ─────────────────────────────────────────────────────────────────────────────
// Solar Forecast
// ─────────────────────────────────────────────────────────────────────────────

def solarForecastHandler(evt) {
    def previous = state.lastSolarForecast as Double
    def current  = evt.doubleValue
    state.lastSolarForecast = current
    if (previous != null) {
        log.info "── Solar forecast updated (${evt.name}): ${previous} kWh → ${current} kWh → recalculating charge target ──"
    } else {
        log.info "── Solar forecast updated (${evt.name}): ${current} kWh → recalculating charge target ──"
    }
    // Capture the day's opening band on the first fresh forecast (the ~08:58 poll). No-op on
    // every later poll, so the baseline stays pre-morning and the trend check stays honest.
    captureForecastSnapshot()
    chargeCheckHandler()
}

def solarGenerationHandler(evt) {
    logDebug "Solar generation updated: ${evt.value} kWh"
    // Recalculate only when the projection actually changes which estimate is selected —
    // the energy attribute updates far more often than the selection changes.
    def previous = state.selectedEstimate
    def current  = selectForecastEstimate()
    if (previous != null && previous != current) {
        log.info "── Solar generation ${evt.value} kWh → forecast selection ${previous} → ${current} → recalculating charge target ──"
        chargeCheckHandler()
    }
}

def forecastHighHandler(evt) {
    log.info "── Forecast high updated: ${evt.doubleValue}°C → re-evaluating charge target and extreme weather ──"
    chargeCheckHandler()
    if (isExtremeWeatherWindow()) extremeWeatherCheck()
}

def modeChangeHandler(evt) {
    log.info "── Mode changed: ${evt.value} → recalculating charge target ──"
    chargeCheckHandler()
}

def powerwallStateHandler(evt) {
    log.info "── Powerwall operating state changed: ${evt.value} ──"
}

def batteryLevelHandler(evt) {
    logDebug "Battery level: ${evt.value}%"
    offPeakChargeCheck()
}

// ─────────────────────────────────────────────────────────────────────────────
// Rule 3: Severe Weather Warnings
// ─────────────────────────────────────────────────────────────────────────────

def severeWeatherHandler(evt) {
    def trigger = evt ? "alertDescrFull updated" : "startup"
    log.info "── Severe weather check (triggered by: ${trigger}) ──"

    def alertText  = getAttr(openWeatherDevice, "alertDescrFull") ?: ""
    def lowerAlert = alertText.toLowerCase()

    def locations = parseCSV(weatherLocations)
    def keywords  = parseCSV(weatherKeywords)

    boolean locationMatch = locations.any { lowerAlert.contains(it.toLowerCase()) }
    boolean keywordMatch  = keywords.any  { lowerAlert.contains(it.toLowerCase()) }

    log.info "Severe weather: locationMatch=${locationMatch}, keywordMatch=${keywordMatch}, alert='${alertText}'"

    boolean newState
    if (locationMatch && keywordMatch) {
        log.info "Severe weather: alert matched region and keyword → SevereWeatherWarnings = true"
        newState = true
    } else {
        // Fallback: extreme heat threshold
        def maxTemp   = getCurrentMaxTemp()
        def threshold = (extremeHeatThreshold ?: 35.0).toDouble()
        if (maxTemp != null && maxTemp >= threshold) {
            log.info "Severe weather: no alert match, but extreme heat (${maxTemp}°C ≥ ${threshold}°C) → SevereWeatherWarnings = true"
            newState = true
        } else {
            log.info "Severe weather: no alert match and heat below threshold (${maxTemp}°C < ${threshold}°C) → SevereWeatherWarnings = false"
            newState = false
        }
    }

    def previous = getSevereWeatherWarnings()
    if (previous != newState) {
        setGlobalVar("SevereWeatherWarnings", newState)
        log.info "SevereWeatherWarnings changed: ${previous} → ${newState} → triggering extreme weather re-evaluation"
        extremeWeatherCheck()
    } else {
        log.info "SevereWeatherWarnings unchanged (${newState}), no cascade"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Rule 4: Extreme Weather (3:05 PM daily + on SevereWeatherWarnings change + on temp change)
// ─────────────────────────────────────────────────────────────────────────────

def extremeWeatherHandler(evt = null) {
    def trigger = evt ? "${evt.displayName} reported ${evt.name}: ${evt.value}" : "daily schedule"
    log.info "── Extreme weather check (triggered by: ${trigger}) ──"
    if (!isExtremeWeatherWindow()) {
        log.info "Outside monitoring window, skipping"
        return
    }
    extremeWeatherCheck()
}

def extremeWeatherCheck() {
    def forecastHigh  = getAttr(openWeatherDevice, "forecastHigh")?.toDouble()
    def isSevere      = getSevereWeatherWarnings()
    def tempThreshold = (extremeTempThreshold ?: 35.0).toDouble()
    def opState       = getAttr(powerwallDevice, "currentOpState")

    log.info "Extreme weather check: forecastHigh=${forecastHigh}°C, severeWarning=${isSevere}, threshold=${tempThreshold}°C, currentMode=${opState}"

    if ((forecastHigh != null && forecastHigh >= tempThreshold) || isSevere) {
        if (opState != "Backup-Only") {
            setPowerwallBackupOnly("extreme condition – forecastHigh=${forecastHigh}°C, severe=${isSevere}")
        } else {
            log.info "Extreme weather: condition met, already in Backup-Only mode"
        }
    } else {
        // Free off-peak charging owns the Powerwall's mode for its whole window (free stage
        // through top-up end). Without this guard the scheduled extreme-weather check would
        // force Self-Powered mid-window and cancel charging — the top-up stage in particular
        // can run past extremeWindowStart on tariffs where peak begins at 4pm.
        if (isFreeChargingWindow()) {
            log.info "Extreme weather: conditions clear, but free charging window is active – " +
                     "leaving mode to the free off-peak handler"
            return
        }
        if (opState != "Self-Powered") {
            setPowerwallSelfPowered("extreme weather conditions cleared")
        } else {
            log.info "Extreme weather: conditions clear, already in Self-Powered mode"
        }
    }
}

/**
 * Returns true when severe weather or extreme heat conditions are active and
 * the Powerwall should therefore be in Backup-Only mode.
 * Used by the off-peak handlers to avoid overriding a weather-driven Backup-Only
 * state when the charging window ends or the charge target is met.
 */
private boolean isExtremeConditionActive() {
    if (getSevereWeatherWarnings()) return true
    def forecastHigh  = getAttr(openWeatherDevice, "forecastHigh")?.toDouble()
    def tempThreshold = (extremeTempThreshold ?: 35.0).toDouble()
    return forecastHigh != null && forecastHigh >= tempThreshold
}

// ─────────────────────────────────────────────────────────────────────────────
// Rule 5: Grid Outage + Rule 4 grid-restore trigger
// ─────────────────────────────────────────────────────────────────────────────

def gridPresenceHandler(evt) {
    log.info "── Grid presence changed: ${evt.value} ──"

    if (evt.value == "not present") {
        def opState = getAttr(powerwallDevice, "currentOpState")
        if (opState != "Self-Powered") {
            setPowerwallSelfPowered("grid outage – preserving battery (was ${opState})")
        } else {
            log.info "Grid outage: already in Self-Powered mode, no action needed"
        }
    } else if (evt.value == "present") {
        if (isExtremeWeatherWindow()) {
            log.info "Grid restored: within extreme weather window → triggering extreme weather re-evaluation"
            extremeWeatherCheck()
        } else {
            log.info "Grid restored: outside extreme weather window, no mode change"
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Switch Powerwall to Self-Powered, respecting the 5-minute minimum between mode changes. */
private boolean setPowerwallSelfPowered(String reason) {
    if (!canChangePowerwallMode()) return false
    log.info "Powerwall → Self-Powered (${reason})"
    powerwallDevice.setSelfPoweredMode()
    state.lastModeChangeMs = now()
    return true
}

/** Switch Powerwall to Backup-Only, respecting the 5-minute minimum between mode changes. */
private boolean setPowerwallBackupOnly(String reason) {
    if (!canChangePowerwallMode()) return false
    log.info "Powerwall → Backup-Only (${reason})"
    powerwallDevice.setBackupOnlyMode()
    state.lastModeChangeMs = now()
    return true
}

/** Returns true if enough time has passed since the last mode change (5-minute minimum). */
private boolean canChangePowerwallMode() {
    def last = state.lastModeChangeMs as Long
    if (last == null) return true
    def elapsed = now() - last
    def minMs   = 5 * 60 * 1000L
    if (elapsed < minMs) {
        def secsRemaining = ((minMs - elapsed) / 1000).toLong()
        log.info "Powerwall mode change suppressed – ${secsRemaining}s remaining before next change allowed"
        return false
    }
    return true
}

/** Convert a "time" input value to a Date object for today in the hub's time zone. */
private Date toDate(timeValue) {
    return timeToday(timeValue as String, location.timeZone)
}

/** Get a device attribute, logging a warning if the device is null. */
private getAttr(device, String attr) {
    if (!device) { log.warn "Device is null when reading '${attr}'"; return null }
    return device.currentValue(attr)
}

/**
 * Returns today's solar-day forecast in kWh, using whichever Solcast estimate the day is
 * actually tracking (see selectForecastEstimate()).
 */
private Double getSolarForecast() {
    if (!solarForecastDevice) return null

    def selection = selectForecastEstimate()
    def attr = (selection == "low")  ? "24_Hour_Estimate_Low"
             : (selection == "high") ? "24_Hour_Estimate_High"
             :                         "24_Hour_Estimate"

    def v = solarForecastDevice.currentValue(attr)
    if (v != null) {
        logDebug "Solar forecast: using ${selection.toUpperCase()} estimate = ${v} kWh"
        return v.toDouble()
    }

    log.warn "Solar forecast: '${attr}' unavailable – falling back to middle estimate"
    def m = solarForecastDevice.currentValue("24_Hour_Estimate")
    if (m != null) return m.toDouble()
    log.warn "Solar forecast: '24_Hour_Estimate' returned null"
    return null
}

/**
 * Solar noon – the midpoint between sunrise and sunset, and by definition the moment when
 * exactly half of the day's solar generation should be complete. Drifts across the year
 * (roughly 12:25 midwinter to 13:25 midsummer in Melbourne), so it is computed daily
 * rather than configured.
 */
private Date getSolarNoon() {
    def sun = getSunriseAndSunset()
    if (sun?.sunrise == null || sun?.sunset == null) return null
    return new Date((((sun.sunrise.time + sun.sunset.time) / 2) as BigDecimal).longValue())
}

/**
 * Fraction of the day's total solar generation expected to be complete by the given time.
 *
 * Generation approximates a half-sine between sunrise and sunset, so the cumulative fraction
 * is the normalised integral of that curve:
 *
 *   f(t) = (1 − cos(π × elapsed)) / 2     where elapsed = (t − sunrise) / (sunset − sunrise)
 *
 * Yields 0.0 at sunrise, exactly 0.50 at solar noon, and 1.0 at sunset. Anchored to real sun
 * times, so it self-adjusts for season and daylight saving.
 */
private Double getSolarDayFraction(Date at = new Date()) {
    def sun = getSunriseAndSunset()
    if (sun?.sunrise == null || sun?.sunset == null) return null
    def dayMs = sun.sunset.time - sun.sunrise.time
    if (dayMs <= 0) return null

    double elapsed = (at.time - sun.sunrise.time) / (double) dayMs
    if (elapsed <= 0.0d) return 0.0d
    if (elapsed >= 1.0d) return 1.0d
    return (1.0d - Math.cos(Math.PI * elapsed)) / 2.0d
}

/**
 * True when the Solcast attributes still hold yesterday's values. The forecast device polls
 * on a schedule (first call ~08:58), so between midnight and that first poll the estimates
 * are stale and must not drive charging decisions.
 */
private boolean isForecastStale() {
    if (!solarForecastDevice) return true
    def st = solarForecastDevice.currentState("24_Hour_Estimate")
    if (st?.date == null) return true
    return st.date.before(timeToday("00:00", location.timeZone))
}

/**
 * Captures the day's opening Low/Mid/High forecast the first time a fresh forecast arrives
 * (the ~08:58 poll). This baseline is what the solar-noon trend check compares against.
 *
 * The comparison MUST use a forecast issued before the period being measured. Solcast ingests
 * satellite cloud imagery, so a midday forecast already reflects the morning's actual
 * conditions — comparing generation-so-far against it would be circular and would conclude
 * the day is tracking the middle estimate almost every time.
 */
private void captureForecastSnapshot() {
    if (!solarForecastDevice || isForecastStale()) return
    def today = new Date().format("yyyy-MM-dd", location.timeZone)
    if (state.forecastSnapshotDate == today) return

    def mid = solarForecastDevice.currentValue("24_Hour_Estimate")?.toDouble()
    if (mid == null) return

    state.forecastSnapshotDate = today
    state.forecastSnapshotLow  = solarForecastDevice.currentValue("24_Hour_Estimate_Low")?.toDouble()
    state.forecastSnapshotMid  = mid
    state.forecastSnapshotHigh = solarForecastDevice.currentValue("24_Hour_Estimate_High")?.toDouble()
    log.info "Forecast baseline captured for ${today}: low=${state.forecastSnapshotLow}, " +
             "mid=${state.forecastSnapshotMid}, high=${state.forecastSnapshotHigh} kWh"
}

/**
 * Decides which Solcast estimate today is tracking: "low", "mid" or "high".
 *
 * Projects actual generation so far to a full-day total by dividing by the fraction of the
 * solar day elapsed, then compares that projection against the morning baseline band. At
 * solar noon the fraction is exactly 0.50, so the projection is simply generation × 2.
 *
 * What this measures is site performance against modelled irradiance. Solcast predicts the
 * sun better than any local heuristic can, but it cannot see panel soiling, new shading,
 * inverter derating or a dropped string — a projection persistently below baseline on clear
 * days is a maintenance signal, not a weather one.
 *
 * Selection is deliberately biased toward the conservative choice: under-charging costs a
 * peak-rate import, while over-charging only costs the off-peak/feed-in spread. The
 * projection must travel forecastUpgradeBias% of the way toward the next-higher estimate
 * before that estimate is selected.
 *
 * Pure function – no state writes, so it is safe to call during page rendering.
 */
private String selectForecastEstimate() {
    if (!solarForecastDevice || !solarGenerationDevice) return "mid"
    if (isForecastStale())                               return "mid"

    def snapMid = state.forecastSnapshotMid as Double
    if (snapMid == null || snapMid <= 0) return "mid"

    // Before solar noon the divisor is small and the projection is too noisy to act on
    def now      = new Date()
    def noonTime = getSolarNoon()
    if (noonTime == null || now.before(noonTime)) return "mid"

    def fraction = getSolarDayFraction(now)
    if (fraction == null || fraction < 0.15d) return "mid"

    def actual = solarGenerationDevice.currentValue("energy")?.toDouble()
    if (actual == null) return "mid"

    double implied  = actual / fraction
    def    snapHigh = (state.forecastSnapshotHigh as Double) ?: snapMid

    // Sanity guard: the generation attribute is expected to reset to 0 each midnight. A
    // projection several times the optimistic estimate means it is behaving as a lifetime
    // counter (or is faulty) — fall back to the middle estimate rather than acting on it.
    if (implied > snapHigh * 3.0d) {
        log.warn "Forecast selection: implied full day ${implied.round(1)} kWh is implausible vs " +
                 "high estimate ${snapHigh} kWh – is the generation meter a lifetime counter? Using middle estimate"
        return "mid"
    }

    def    snapLow = (state.forecastSnapshotLow as Double) ?: snapMid
    double bias    = ((forecastUpgradeBias ?: 60) as Integer) / 100.0d

    String selection
    if      (implied >= snapMid + bias * (snapHigh - snapMid)) selection = "high"
    else if (implied >= snapLow + bias * (snapMid  - snapLow)) selection = "mid"
    else                                                       selection = "low"

    logDebug "Forecast selection: actual=${actual} kWh at ${Math.round(fraction * 100)}% of solar day " +
             "→ implied ${implied.round(1)} kWh vs baseline [${snapLow}/${snapMid}/${snapHigh}] → ${selection}"
    return selection
}

/**
 * Creates a hub variable if it does not already exist. The variable's type is inferred by the
 * hub from the initial value, so pass a number for Number and a boolean for Boolean.
 *
 * Returns true if the variable exists (or was just created), false if it is still missing.
 */
private boolean ensureGlobalVar(String name, def initialValue, String typeLabel) {
    if (getGlobalVar(name) != null) return true
    try {
        if (createGlobalVar(name, initialValue)) {
            log.info "Created missing hub variable '${name}' (${typeLabel}), initial value ${initialValue}"
            return true
        }
        log.error "Could not create hub variable '${name}' (${typeLabel}) – create it manually under Settings → Hub Variables"
        return false
    } catch (e) {
        log.error "Error creating hub variable '${name}' (${typeLabel}): ${e.message} – create it manually under Settings → Hub Variables"
        return false
    }
}

/**
 * Ensures both hub variables this app depends on exist, creating either if missing, then
 * registers the app as a user of them so the hub will not allow them to be deleted while
 * this app is installed.
 */
private void ensureRequiredGlobalVars() {
    boolean chargeOk = ensureGlobalVar("PW_Charge_Target",      0,     "Number")
    boolean severeOk = ensureGlobalVar("SevereWeatherWarnings", false, "Boolean")
    state.globalVarsReady = chargeOk && severeOk

    // Registering as in-use makes the app appear in the variable's "in use by" list and
    // blocks deletion. Wrapped defensively: not worth failing initialize() over.
    try {
        addInUseGlobalVar(["PW_Charge_Target", "SevereWeatherWarnings"])
    } catch (e) {
        logDebug "addInUseGlobalVar unavailable or failed: ${e.message}"
    }

    if (!state.globalVarsReady) {
        log.warn "One or more required hub variables are missing – the app cannot store the charge " +
                 "target or weather flag until they exist"
    }
}

/**
 * Hub callback fired when a variable this app registered via addInUseGlobalVar() is renamed.
 * The names are hardcoded throughout the app, so a rename breaks it — warn loudly rather than
 * failing silently. Recreate the original name (or rename it back) to restore operation.
 */
def renameVariable(String oldName, String newName) {
    log.error "Hub variable '${oldName}' was renamed to '${newName}'. Advanced Powerwall Manager " +
              "requires the original name – rename it back, or open and re-save the app to recreate it."
}

/** Read SevereWeatherWarnings hub variable as boolean. */
private boolean getSevereWeatherWarnings() {
    def v = getGlobalVar("SevereWeatherWarnings")?.value
    return (v == true || v == "true")
}

/** Called on every temperature report from the weather station; updates daily max. */
def temperatureHandler(evt) {
    def temp = evt.doubleValue
    def prev = state.dailyMaxTemp
    if (prev == null || temp > prev) {
        state.dailyMaxTemp = temp
        log.info "Daily max temp updated: ${prev != null ? prev + '°C → ' : ''}${temp}°C"
        if (isExtremeWeatherWindow()) {
            log.info "New daily max within extreme weather window → triggering extreme weather re-evaluation"
            extremeWeatherCheck()
        }
    } else {
        logDebug "Temperature reading ${temp}°C ≤ current daily max ${prev}°C, no update"
    }
}

/** Resets daily tracking values at midnight. */
def resetDailyMaxTemp() {
    def prev = state.dailyMaxTemp
    state.dailyMaxTemp             = weatherStation?.currentValue("temperature")?.toDouble()
    state.chargeTargetReachedToday = false

    // Clear the forecast baseline and selection — the day starts on the middle estimate and
    // the new baseline is captured from the first forecast poll (~08:58). Until then
    // isForecastStale() suppresses charging decisions.
    state.selectedEstimate     = null
    state.forecastSnapshotDate = null
    state.forecastSnapshotLow  = null
    state.forecastSnapshotMid  = null
    state.forecastSnapshotHigh = null

    log.info "Midnight reset: dailyMaxTemp was ${prev}°C, seeded with ${state.dailyMaxTemp}°C; " +
             "forecast baseline and daily flags cleared"
}

/** Returns today's tracked maximum temperature, falling back to OpenWeather if no station set. */
private Double getCurrentMaxTemp() {
    if (weatherStation) {
        return state.dailyMaxTemp as Double
    }
    return getAttr(openWeatherDevice, "temperatureMaximum")?.toDouble()
}

/** True if currently within the extreme-weather monitoring window (may span midnight). */
private boolean isExtremeWeatherWindow() {
    def now   = new Date()
    def start = toDate(extremeWindowStart)
    def end   = toDate(extremeWindowEnd)

    if (!start.after(end)) {
        // Same-day window
        return timeOfDayIsBetween(start, end, now, location.timeZone)
    }
    // Overnight window: active after start OR before end (next morning)
    return now.after(start) || now.before(end)
}

/** True if the current hub mode satisfies the off-peak mode restriction. */
private boolean isOffPeakModeActive() {
    if (!offPeakModes) return true
    return offPeakModes.contains(location.mode)
}

/** Split a comma-separated string into a trimmed list, ignoring blank entries. */
private List parseCSV(String csv) {
    if (!csv) return []
    return csv.split(",").collect { it.trim() }.findAll { it }
}

/**
 * Returns today's estimated daily consumption in kWh using a sinusoidal Southern Hemisphere
 * seasonal model applied to the configured annual average:
 *
 *   consumption = annualAvg × (1 + 0.25 × cos(2π × month / 12))
 *
 * Month 0 (January) is the peak (summer in Australia), month 6 (July) is the trough.
 * Amplitude of 0.25 gives ±25% seasonal swing — e.g. for 40 kWh/day average:
 *   January ≈ 50 kWh, July ≈ 30 kWh.
 *
 * Returns null if annualAvgConsumptionKwh is not configured.
 */
private Double getDailyConsumption() {
    if (!annualAvgConsumptionKwh) return null
    def month   = Calendar.getInstance(location.timeZone).get(Calendar.MONTH) // 0 = January
    def factor  = 1.0 + 0.25 * Math.cos(2 * Math.PI * month / 12.0)
    return (annualAvgConsumptionKwh as Double) * factor
}

private void logDebug(String msg) {
    if (logEnable) log.debug "[AdvPWManager] ${msg}"
}
