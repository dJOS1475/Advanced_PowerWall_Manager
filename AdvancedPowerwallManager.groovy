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
 *  Written and tested only against this stack — substitutions are possible since the app
 *  depends on attribute/command names rather than specific drivers, but are unverified:
 *    Powerwall         Tesla Powerwall 2 integration by DarwinsDen
 *                      (currentOpState, battery, power; setSelfPoweredMode/setBackupOnlyMode)
 *                      Assumes 13.5 kWh per unit and the 99% Backup-Only ceiling — PW2 behaviour
 *    Solar forecast    Solcast_dual by Alan F — 24_Hour_Estimate / _Low / _High, full-day totals
 *    Solar generation  Fronius inverter integration — 'energy', daily kWh resetting at midnight
 *    Weather alerts    OpenWeather Alerts (HPM) — alertDescrFull, forecastHigh
 *    Weather station   Weather Underground (HPM) — temperature
 *    Grid presence     Virtual presence sensor, driven externally
 *
 *  Version history:
 *    4.5.2  The day summary no longer compares a PARTIAL generation figure against a whole-day
 *           forecast. It fires when the charging day closes, which is the start of peak — the
 *           sun is still up. On 11 Sep it reported "Solar 23.4 kWh vs this morning's 23.8
 *           (98%)" when the day finished at 25.37 kWh: another 2 kWh, 8% of the day, arrived
 *           between 16:00 and 17:43. The correct figure was 107%.
 *
 *           That is not a cosmetic difference. Reviewing those logs, the truncated total made
 *           the trend analysis look wrong — it had selected High (25.05) and the apparent
 *           23.4 sat closer to Mid — and made the geometry curve look as though it understated
 *           elapsed fraction late in the day. Against the real total both were right: High was
 *           the nearest estimate by 0.32 kWh, and the curve tracked actual generation to within
 *           two points from midday to sunset. A summary that misreports the headline number is
 *           worse than one that omits it.
 *
 *           The summary line now reads "Solar X kWh by 16:00 (still generating)" and makes no
 *           forecast comparison. A new logSolarDayFinal() runs at midnight, before the
 *           inverter's counter rolls over, and reports the finished total against the morning
 *           band together with which estimate turned out nearest and which the app actually
 *           used — the check that says whether the trend analysis called the day correctly.
 *    4.5.1  House load is now SAMPLED ON A TIMER rather than on device events. Found reviewing
 *           11 Sep, where the day summary reported a median that its own hourly profile could
 *           not produce: 1.62 kW against daylight hours of 2.2, 1.5, 1.9, 1.7, 0.8, 0.7, 0.6,
 *           0.7, 0.7, 0.8, 1.2 — whose median is 0.80.
 *
 *           Working out which subset gives the reported figures identifies the fault exactly:
 *           hours 6-9 plus 10 median 1.70 (logged 1.71 at 15:45), and with 16 added 1.60
 *           (logged 1.62 at 16:00). The quiet afternoon hours had been discarded.
 *
 *           loadPower reports on change, so an event-driven sample is CHANGE-WEIGHTED: a
 *           volatile hour contributes dozens and a steady one contributes a handful. Hours 12,
 *           13 and 14 logged a single sample each, fell below the minimum-count gate of 5 in
 *           getMeasuredDaytimeLoadKw(), and were dropped from the median altogether — leaving
 *           it set entirely by the busy morning. The bias is one-directional and it buys grid
 *           energy: the projection for 11:00-16:00 was 8.6 kWh against an actual 3.5 kWh, and
 *           the target at 11:00 should have been around 20% with the battery already at 48%.
 *           It charged roughly 2 kWh anyway, exporting the same solar at 0.04c.
 *
 *           New loadSampleHandler() reads loadPower once a minute into the hourly buckets, so
 *           each bucket is a genuine time-weighted mean — a five-minute heat-pump burst
 *           contributes five samples in sixty, which is what it was. loadPowerHandler keeps
 *           only the current-draw display and its throttled log line. The minimum-count gate
 *           drops from 5 to 2 in both getMeasuredDaytimeLoadKw() and rollLoadIntoHistory();
 *           with regular sampling any real hour clears it, and it now only excludes an hour
 *           barely started after a restart.
 *
 *           rollLoadIntoHistory() carried the same gate, so the per-hour history introduced in
 *           4.5.0 was banking holes in exactly the quiet hours the projection most depends on.
 *           Fixed before that history is old enough to be used.
 *
 *           Also: the "Forecast revised" line is reported from the mid attribute only. All
 *           three handlers reach that branch within milliseconds and logSteady's throttle
 *           cannot help — its state write has not landed before the next instance reads it — so
 *           11 Sep logged the same revision twice at 12:55 and again at 14:55. The opening-band
 *           capture keeps its all-attributes-fresh gate from 4.5.0 untouched.
 *    4.5.0  HOUSE LOAD IS NOW PROJECTED HOUR BY HOUR, and the opening forecast band no longer
 *           picks up yesterday's Low and High. Both found reviewing 10 Sep.
 *
 *           THE LOAD. One number could never describe a house whose morning and afternoon are
 *           structurally different, and it failed in the direction that costs money. The heat
 *           pump ran 07:00-09:00 at 3.3-4.0 kW, so at 11:02 — the moment the charging decision
 *           is made — the median of the elapsed daylight hours was 2.15 kW and the projection to
 *           16:00 was 10.9 kWh. The afternoon actually drew about 1.2 kW, or 6.0 kWh.
 *           Overstating load overstates the target: it held 83% when about 55% was right, and
 *           roughly 3 kWh of grid energy was bought that the sun would have supplied for free.
 *
 *           Each day's hourly means are now banked, up to 20 days, and the window between now
 *           and the deadline is walked hour by hour using the MEDIAN of what that hour of the
 *           day typically draws. Replayed against the three days observed so far, the 11:02
 *           projection becomes 7.2 kWh against a true 6.0 — the target lands at 55% instead of
 *           83%. Hours with no history fall back to today's median, so behaviour is unchanged
 *           until three days are banked, and the seasonal model still backstops everything when
 *           there is no load meter at all.
 *
 *           THE BAND. The driver writes Low, Mid and High as three separate events about a
 *           tenth of a second apart, and 4.4.0 read all three the moment the Mid event landed —
 *           so the opening band took yesterday's Low and High. On 10 Sep it recorded
 *           [5.46 / 18.43 / 24.01] when the day's real band was [10.56 / 18.43 / 24.38]. A Low
 *           understated by 5 kWh moves the downgrade threshold by nearly 2 kWh, in the direction
 *           that overstates solar and undercharges.
 *
 *           The opening capture now waits until every attribute carries today's timestamp, so
 *           only the last event of a poll can open the day. That also retires the single-
 *           attribute guard added in 4.3.0: with the band consistent, two handlers racing past
 *           the gate necessarily read identical values, so a duplicate capture is harmless.
 *
 *           The House Load panel row says whether the projection is per-hour or flat, and how
 *           many days are banked.
 *    4.4.1  The charging band is now asymmetric, which stops the Powerwall cycling through the
 *           afternoon. Found reviewing 9 Sep: eight commanded mode changes between 13:28 and
 *           15:04, none of which changed the outcome.
 *
 *           The cause was structural rather than a bug. Through the afternoon the target
 *           ratchets up 2-4 points every quarter hour as the remaining solar shrinks, while the
 *           deadband was 2 points either side — so the sequence was always: charge to target,
 *           target rises past the battery, charge again. The battery was climbing to 99%
 *           regardless; the only thing the cycling changed was how many times the inverter
 *           switched mode to get there.
 *
 *           The start threshold is unchanged at 2% below target. The stop threshold now
 *           overshoots by however far the target actually climbed over the previous quarter
 *           hour, measured from a 15-minute anchor rather than assumed, so it adapts to the day
 *           and the season. On a day when the target is flat or falling the margin is zero and
 *           behaviour is identical to before. It is capped at 6 points, cannot push past 99%,
 *           and zeroes itself when the target has not moved for 45 minutes — so the endgame
 *           into peak and the overnight hold are both untouched.
 *
 *           Replayed against 9 Sep's actual target and battery trace: 7 mode changes become 3.
 *           The one remaining early pair is the anchor warming up, since no climb has been
 *           measured yet on the first evaluation of the day.
 *    4.4.0  The solar day curve is now COMPUTED FROM PANEL GEOMETRY, with measurement demoted
 *           to a correction on top of it. This supersedes the curve introduced in 4.3.0, which
 *           learned the whole shape from banked days — a design whose weakness was fair: twenty
 *           days of history is twenty days of weather as much as it is the site.
 *
 *           New Panel Array Geometry settings take each roof face as kW, compass azimuth and
 *           tilt. From those, plus the hub's latitude and the date, the app computes when the
 *           day's generation arrives. Standard solar position: declination from the date, hour
 *           angle from solar noon taken as the midpoint of the hub's own sunrise and sunset
 *           (which absorbs the equation of time and longitude without computing either), Meinel
 *           clear-sky beam irradiance and an isotropic diffuse term, integrated across the day
 *           and resampled onto twenty slices.
 *
 *           Only the SHAPE comes from this — magnitude still comes from Solcast — so the crude
 *           irradiance model costs little, and the result barely moves with tilt (15, 22.5 and
 *           30 degrees all land within 0.003, so an approximate roof pitch is fine). Validated
 *           against a cloudless day at the reference site, 1.5 kW facing NE and 3.9 kW facing
 *           NW:
 *
 *               mean error vs actual      geometry 0.020    half-sine 0.038
 *               fraction at solar noon    geometry 0.461    half-sine 0.500    actual 0.464
 *
 *           Correct on day one, no warm-up, no weather contamination, and exact through the
 *           seasons because declination is a function of the date rather than something the
 *           history has to catch up with.
 *
 *           The banked history survives as layer three, but now stores the DIFFERENCE between
 *           what a day actually did and what the computed curve said it would — a small signed
 *           number centred near zero. Weather pushes it either way from day to day and largely
 *           cancels in the median, instead of being mistaken for the site's shape. On that same
 *           day the correction runs about -0.03 through the morning and +0.02 in the afternoon,
 *           which is the terrain to the east that geometry cannot see, and nothing else.
 *
 *           getExpectedSolarKw() no longer carries its own derivative of the half-sine. It
 *           differences whichever curve is in force across an hour centred on the moment asked
 *           about, so all three layers stay consistent with the cumulative form by construction.
 *
 *           The settings page previews the computed curve as you enter it — array shares, and
 *           what percentage of the day lands by solar noon — so a mistyped azimuth shows up
 *           immediately rather than quietly biasing the charge target for weeks.
 *    4.3.0  The solar day is now MEASURED rather than assumed, and the house-load average no
 *           longer lets one busy hour speak for the day. Both were found by reviewing 8 Sep,
 *           a cloudless day that was charged from the grid as though it would produce 14 kWh
 *           when it produced 25 — about 64c of avoidable import, and the battery full at 13:29
 *           with the rest of the afternoon exported at 0.04c.
 *
 *           THE CURVE. getSolarDayFraction() assumed a symmetric half-sine between geometric
 *           sunrise and sunset. Measured against that clear day, it claimed 11.5% of the day
 *           was done by 09:01 when the true figure was 5.0%, 21.9% by 10:04 against 14.6%, and
 *           it did not converge until about 13:45. Terrain to the east, panel azimuth and an
 *           inverter that plateaus near its AC limit around midday all shift the real curve in
 *           ways no symmetric function can express. This matters more than it looks, because
 *           remaining solar reduces to actual × Δf ÷ f_now — the forecast cancels out entirely
 *           once the performance ratio is live — so f_now is doing all the work, and an
 *           overstated f_now halves the projection.
 *
 *           The app now banks each day's generation profile and takes the MEDIAN across up to
 *           20 days. (4.4.0 rebases this onto a computed curve, so what is banked became a
 *           correction rather than the shape itself.)
 *           Below five days it falls back to the half-sine, and days under 3 kWh are discarded
 *           rather than banked. The profile is cached and rebuilt once a day, because the read
 *           path runs several times a minute. Status panel gains a Solar Curve row showing
 *           which curve is in use and what fraction of the day it puts at solar noon.
 *
 *           The profile is indexed by SLICE OF THE SOLAR DAY, not by clock hour. Hour-indexing
 *           would bake today's daylight length into the curve, and near an equinox that moves
 *           about two minutes a day — the profile would be stale within a fortnight and
 *           meaningless within a season. Slicing sunrise-to-sunset into twenty keeps the
 *           self-adjusting anchor the half-sine had while still learning the real shape.
 *
 *           What the shape turns out to be, for the reference site: 1.5 kW of panels facing NE
 *           and 3.9 kW facing NW, southern hemisphere. Nearly three quarters of the capacity is
 *           on the afternoon sun, which is why the morning runs so far behind a symmetric curve
 *           — and the broad flat midday peak is two arrays whose individual peaks fall either
 *           side of solar noon, not inverter clipping. 5.4 kW of panels peaking at 4.1 kW AC
 *           settles that: the two arrays never peak together, and nothing is being clipped.
 *           None of it is configured anywhere either — the measured curve absorbs azimuth,
 *           tilt, terrain shading and soiling without being told about any of them.
 *
 *           THE LOAD. getMeasuredDaytimeLoadKw() pooled every sample from every daylight hour,
 *           so it was sample-weighted: a heat-pump burst from 09:20 to 09:50 left hour 9
 *           averaging 3.94 kW against 1.08-2.15 kW for every other hour, pulling the figure to
 *           2.21 kW and still inflating it six hours later. Since the result is projected flat
 *           across every remaining hour, 17.3 kWh of load was predicted to 16:00 against
 *           10.5 kWh actual. It now takes the median of the hourly means (1.70 kW on that day),
 *           falling back to the pooled mean until three hours have accumulated.
 *
 *           A forecast total is now clamped to at least generation-so-far. Solcast's midday
 *           revision on 8 Sep read 15.95 kWh for the day when 22.47 kWh was already banked;
 *           whatever that figure is, it is not a full-day total and cannot be used as one.
 *
 *           Two logging bugs. state.lastSolarForecast was one shared value read and written by
 *           all three attribute handlers, so every "Solar forecast updated" line compared
 *           against whichever attribute fired last and reported transitions that never happened
 *           — it is now keyed per attribute. And the opening-snapshot date guard lost a race
 *           between the three near-simultaneous handler instances, so 8 Sep logged two opening
 *           bands with different High values; only the 24_Hour_Estimate event may open a day.
 *    4.2.0  Two forecasts are now kept per day instead of one, and the code no longer treats
 *           Solcast's 24_Hour_Estimate as a rolling window. It is a whole-of-day total for the
 *           current day, used exactly as published — the old reconstruction that added back
 *           generation already banked at capture time was double-counting the morning, and the
 *           "capture before sunrise" warning it produced is gone with it.
 *
 *           captureForecastSnapshot() now stores the OPENING band once (the ~08:58 poll) and
 *           overwrites a LATEST band on every poll after it. They serve different purposes and
 *           had been conflated:
 *
 *             getOpeningForecast()  trend reference. selectForecastEstimate() must compare
 *                                   against a prediction made before the morning it is judging,
 *                                   or the comparison is circular — Solcast ingests satellite
 *                                   imagery, so a midday forecast already knows how the morning
 *                                   went and would report "tracking Mid" almost every day.
 *
 *             getLatestForecast()   everything forward-looking: getSolarForecast(),
 *                                   getSolarRemainingKwh(), getExpectedSolarKw() and the
 *                                   performance ratio that scales them. Solcast revises the same
 *                                   day's total as the day develops, and the target only cares
 *                                   about solar still to come. Freezing at 08:58 discarded three
 *                                   better-informed revisions every day.
 *
 *           The fallback to the raw device attribute is now refused while the forecast is stale,
 *           so the window between midnight and the first poll can no longer project today from
 *           yesterday's numbers — the target holds its safe default instead.
 *
 *           Status panel forecast rows show the latest figures with the morning's alongside them
 *           wherever they differ, coloured by direction, so a day being revised up or down is
 *           visible without reading logs. The trend row is relabelled "vs this morning's" to make
 *           clear it is not comparing against the same numbers. The day summary reports the total
 *           revision, and a throttled log line reports each one as it lands.
 *    4.1.4  Observability and log volume. A DAY SUMMARY is now written at info the moment the
 *           charging day closes — which is when peak begins, and the only point where every
 *           figure that matters is final. It reports the battery level entering peak, the
 *           charging session, solar against baseline, the estimate selected, the measured load
 *           and its hourly profile. Previously there was no way to judge a day without reading
 *           several thousand log lines and redoing the arithmetic by hand.
 *
 *           Logging cut substantially. forecastHighHandler was firing a full recalculation
 *           cascade every few minutes on 0.1°C nudges from OpenWeather — three times the work
 *           and three times the volume for no change in outcome; it now reacts only to a move of
 *           0.5°C or one crossing the hot-day or extreme threshold. chargingCheck() runs on
 *           every battery report, several times a minute, so its steady-state branches route
 *           through the new logSteady() and repeat at most every few minutes; anything
 *           representing a change still logs immediately. The two charge-target info lines are
 *           now one, logged at info only when the target moves. selectForecastEstimate() and the
 *           solar-remaining line are throttled, removing the four-per-cycle repetition.
 *
 *           Status panel gains a House Load row showing current draw, today's daylight average
 *           and the hour-by-hour profile — it is a primary input to the target and was
 *           previously visible only in debug logs. The Charge Target row is flagged when held
 *           outside the charging day, so a static value no longer looks like a stuck one.
 *    4.1.3  The charge target is no longer recalculated outside the charging day. Once peak
 *           begins, getNextPeakStart() rolls to tomorrow and the calculation degraded into
 *           nonsense — a deadline ~19 hours out, a whole night of house load against it, and no
 *           knowledge of tomorrow's solar — pinning it at 99% every evening. Nothing acted on it
 *           then, but it misled the logs and any external rule reading the hub variable, and it
 *           would be actively wrong for anyone charging in an overnight period. Updates now stop
 *           at the deadline and resume when the next morning's forecast baseline lands, holding
 *           the last daytime value in between; the new shouldUpdateChargeTarget() gates it, and
 *           falls back to the deadline test alone when no forecast device is configured.
 *
 *           Also widened the solar performance ratio's lower bound from 0.4 to 0.2. The bounds
 *           should not be symmetric: understating remaining solar only over-charges at the cheap
 *           rate, while overstating it enters peak short at the expensive one. Observed live, a
 *           heavily overcast morning ran a true ratio of 0.36 and spent 90 minutes pinned to the
 *           floor, nudging the estimate in the costly direction. A dead inverter is still caught
 *           separately by isSolarStopped().
 *    4.1.2  Bug fix: closeoutHandler threw MissingMethodException (java.util.Date.call()) on
 *           every tick of the closeout window. Its local `def now = new Date()` shadows
 *           Hubitat's now(), so `state.lastModeChangeMs = now()` tried to call the Date. The
 *           Self-Powered command itself had already been sent by that point, so the handover
 *           still happened, but the cooldown timestamp was never recorded and the handler
 *           aborted. Uses now.time instead. This predates 4.0.0 — the same shadowing existed in
 *           offPeakCloseoutHandler.
 *
 *           Also restored a guard lost in the 4.0.0 rewrite: charging no longer STARTS inside
 *           the closeout window. Because the target climbs toward 99% as the deadline nears,
 *           the battery can fall below it with a minute or two of the period left — observed
 *           live, Backup-Only commanded at 15:58:24 and the closeout handing back to
 *           Self-Powered 47 seconds later. A session already running is left to finish; only
 *           starting a new one is suppressed.
 *    4.1.1  Tariff period boundaries are now half-open — [start, end) — and compared by
 *           minutes-since-midnight rather than by Date. Surfaced by the coverage check
 *           reporting a 15-minute overlap on a plan whose three periods tile perfectly.
 *
 *           Two faults. timeOfDayIsBetween() is inclusive at both ends, so 16:00 belonged to
 *           both the 11:00-16:00 window and the 16:00-21:00 window; getPeriodAt() returns the
 *           first match, so at the exact moment peak began the app still considered itself in
 *           a chargeable period. The closeout masked this in practice, but it was wrong.
 *
 *           And because period boundaries come from timeToday() they are always dated today,
 *           while the coverage check probes each weekday on its own date. The wrapping branch
 *           compared absolute Dates, so every non-today probe matched the overnight period and
 *           nothing else — which is why only the current day was ever reported on. Comparing
 *           minutes-since-midnight makes the check date-independent and correct for all seven.
 *    4.1.0  House load is now measured as PER-HOUR AVERAGES FOR TODAY rather than a single
 *           rolling average. A scalar mean cannot represent a load that swings between roughly
 *           0.8 kW and 4.3 kW as a heat pump cycles — a fast average chases each compressor
 *           start, a slow one lags an hour behind. Observed live: a 4.32 kW reading (genuine,
 *           heat pump running) seeded the exponential average and, because state survives
 *           updated(), stayed there across saves while 1% per sample could not shift it.
 *
 *           Each hour now accumulates its own time-weighted mean, and the prediction averages
 *           today's DAYLIGHT hours so far — overnight load is not representative of the
 *           afternoon being extrapolated into. Today's own hours are used rather than a profile
 *           learned across days, because consumption here tracks weather and occupancy rather
 *           than a repeating weekly shape.
 *
 *           This also removes a whole class of fault: the buckets reset at midnight, so a
 *           misleading value cannot persist beyond the day that produced it. The seeding logic,
 *           the conservative max() seed and the clear-on-save that existed only to work around
 *           that are all gone. Falls back to the seasonal estimate until about half an hour of
 *           daylight samples exist. New debug line shows the hour-by-hour profile.
 *    4.0.1  First-run corrections from live logs. The house-load average seeded from the very
 *           first loadPower sample, so whatever the house was drawing at the moment the app was
 *           saved became the whole average — a 4.35 kW spot reading extrapolated to 23.2 kWh
 *           over five hours and forced the target to 99%. It now seeds from the seasonal
 *           estimate, uses a much slower time constant (~100 minutes, since the figure is
 *           extrapolated over hours rather than minutes), and carries a wide gross-error guard
 *           (0.1–5x the configured daily average, warned) to catch a unit mix-up or a stuck
 *           meter. The guard is deliberately loose: loadPower reports house consumption alone,
 *           excluding the battery charge draw, so a genuinely heavy day is real information and
 *           clipping it would defeat the point of measuring at all.
 *
 *           The solar performance ratio was pinned at its 1.4 ceiling on an ordinary morning:
 *           comparing live power against the curve's predicted power carries a shape error,
 *           because a real clear-sky day is more peaked than a half-sine. It now compares
 *           CUMULATIVE energy to date against the baseline's prediction for now, which
 *           integrates that error out. Live power is still used for the solar-stopped check,
 *           which is what it is genuinely good at.
 *    4.0.0  Tariff-driven rewrite. The app is now configured by describing your electricity
 *           plan — 1 to 8 periods, each with a type (Super Off-Peak / Off-Peak / Shoulder /
 *           Peak), a time range that may wrap midnight, optional day restriction, a rate in
 *           c/kWh, and a checkbox to allow charging. Peak periods define the deadline. This
 *           replaces the three-way charging mode enum and both fixed charging windows, so
 *           "free", "standard" and "none" are now just expressions of the same model: free is
 *           a period with rate 0, none is every checkbox unticked.
 *
 *           THE TARGET IS NOW A TIME-VARYING LEVEL, not a morning pre-charge figure:
 *
 *             solar to battery = solar still expected before peak − house load until then
 *             target kWh       = capacity − solar to battery
 *
 *           Early in the day plenty of solar is still to come, so the target sits low. As the
 *           afternoon wears on it climbs, reaching 99% by the deadline — which is what
 *           guarantees a full battery at peak without charging to full early and pushing the
 *           day's remaining solar out to export. The old model credited the WHOLE day's solar
 *           regardless of how much had already been spent, so once the battery passed the
 *           morning figure nothing topped it up and an underdelivering afternoon left you
 *           short at peak with no mechanism to notice.
 *
 *           Both inputs are now measured rather than assumed. Solar remaining comes from the
 *           morning baseline shaped by the real sunrise/sunset curve and corrected by live
 *           inverter output; house load comes from the Powerwall's own loadPower meter,
 *           smoothed (EMA, ~30 min) so an oven cycle cannot move the target. This retires the
 *           hardcoded 0.90 "solar before peak" factor and the 8-hour solar day, both of which
 *           were season-blind. annualAvgConsumptionKwh survives only as a fallback for drivers
 *           without a load meter.
 *
 *           Measured generation additionally corrects the remaining-solar estimate, comparing
 *           energy banked so far against what the baseline predicted by now and clamping the
 *           result to [0.4, 1.4]. Live inverter power drives a separate check that treats
 *           remaining solar as zero when generation has stopped while the curve still expects
 *           output — late cloud, an inverter dropping out, or terrain shading the sun-angle
 *           model knows nothing about.
 *
 *           Charging collapses to one rule for every tariff shape: in a chargeable period,
 *           battery below target → Backup-Only, otherwise Self-Powered, with a 2% deadband so
 *           the rising target cannot cause flapping. Charging begins at the START of a
 *           chargeable period rather than as late as possible, which trades a modest cost on
 *           good solar days for certainty of a full battery at peak. That removes the whole
 *           late-start apparatus: latest-start timing, the early-start hedge and its worst-case
 *           Low sizing, maxChargeRateKw, and the top-up suppression flag.
 *
 *           A zero-rate period is the one exception, held in Backup-Only for its whole length
 *           so the house runs on free grid while the battery fills and never discharges.
 *
 *           Severe weather and extreme heat override the tariff entirely — charging runs in
 *           whatever period is active, including Peak. Vacation Mode suppresses charging only
 *           where the rate is above zero. The Tariff Periods page validates coverage per
 *           weekday, reporting gaps, overlaps, a missing Peak period, and charging enabled on
 *           a Peak period.
 *
 *           Removed: chargingMode, offPeakStart/End/CloseoutMinutes/Modes, every freeOffPeak*
 *           and freeTopUp* setting, chargeWindowStart/End, maxChargeRateKw,
 *           computeSolarModelTarget(), offPeakChargeCheck(), freeOffPeakCheck(),
 *           isFreeChargingWindow(), isFreeOffPeakDay(), getActiveWindowEnd(),
 *           isChargingWindowOpen(), isOffPeakModeActive(), and both charging pages.
 *    3.10.1 Bug fix: PW_Charge_Target could remain pinned at the severe weather level after a
 *           warning cleared. Observed as a stored target of 99% while the status panel's own
 *           Target Calculation row, which computes live, showed a 35% pre-charge.
 *
 *           Two faults compounded. severeWeatherHandler cascaded only to extremeWeatherCheck()
 *           and never recalculated the charge target, even though the warning is Priority 2 in
 *           that ladder — so a cleared warning left the target stale until the next scheduled
 *           run. And the target write was gated solely on the evaluation window, which can be
 *           configured to close before the charging window does (the defaults do exactly that,
 *           14:05 against 15:00), freezing the target partway through the period where it still
 *           governs charging. With a part-full battery the app would have kept importing for
 *           that final stretch chasing a target that no longer applied.
 *
 *           severeWeatherHandler now recalculates the charge target whenever the warning
 *           changes, ordered after extremeWeatherCheck() so charging logic has the final say on
 *           the Powerwall mode while a window is open. The target is additionally recalculated
 *           whenever the active mode's charging window is open, via the new
 *           isChargingWindowOpen(), so the evaluation window can no longer cut it short.
 *    3.10.0 Severe weather alerts are now deferred until the day the event actually begins.
 *           Bureau of Meteorology alerts are routinely issued a day ahead ("damaging winds ...
 *           from early Wednesday morning" published on a Tuesday), and acting immediately held
 *           the Powerwall in Backup-Only for an extra day, wasting a full cycle of stored solar.
 *
 *           BoM carries its timing in prose rather than a structured field, so the new
 *           getAlertOnsetOffset() scans the alert text for relative markers (today, tonight,
 *           tomorrow) and weekday names, resolves each weekday to its next occurrence, and takes
 *           the earliest. The rule is deliberately asymmetric — no day reference at all, or any
 *           reference to today, counts as current; only text referring exclusively to future
 *           days is deferred. This also absorbs alerts carrying an issue line such as "Issued at
 *           5:00 am Tuesday", which on a Tuesday reads as current. The cost is an occasional
 *           missed deferral, which is much cheaper than a missed storm.
 *
 *           A deferred alert normally keeps the same text until it expires, so no attribute
 *           event fires on its onset day. The midnight reset therefore re-runs the severe
 *           weather check against the new date, which both activates a deferred alert on the
 *           right day and lets a stale one lapse (a weekday name now in the past resolves to
 *           next week and defers again).
 *
 *           The extreme-heat fallback still applies while an alert is deferred, since today's
 *           measured temperature is current regardless of when a forecast wind event starts.
 *           New toggle on the Severe Weather page (default on); status panel shows the pending
 *           onset date.
 *    3.9.1  Bug fix: '24_Hour_Estimate' is a ROLLING 24-hour window measured from the moment
 *           of the API poll, not a calendar-day total. The Solcast driver requests forecasts
 *           with hours=72 (which the API returns starting from now) and sums the first 48
 *           half-hour periods, so the value decays through the day as generation is consumed
 *           from the front of the window, and by midday is largely composed of TOMORROW's
 *           generation. Observed: baseline mid 13.72 kWh at 08:58, live value 6.79 kWh at
 *           13:45 on a day that had already generated 10.86 kWh.
 *
 *           Because getSolarForecast() read the live attribute, the charge target climbed
 *           every afternoon regardless of actual production — a day tracking ~15 kWh was
 *           computing 6.79 kWh of solar, finding no surplus, and requesting a 100% pre-charge.
 *
 *           Fixed by reading the morning baseline instead of the live attribute. The ~08:58
 *           snapshot is the one usable reading: at that hour the rolling window is effectively
 *           "rest of today" plus a negligible sliver of tomorrow. captureForecastSnapshot()
 *           now also records generation already banked at capture time, and the new
 *           getBaselineFullDay() adds it back to reconstruct a true full-day total.
 *
 *           This also fixes a units mismatch in the trend analysis, which was comparing a
 *           full-day projection against a from-08:58 baseline — the projection was
 *           systematically inflated relative to the band, biasing selection upward. Both
 *           sides are now full-day equivalents. Status panel shows the corrected baseline.
 *
 *           Note: only the first forecast poll of the day now affects today's target. Later
 *           polls describe tomorrow more than today, so poll placement after ~09:00 no longer
 *           matters for this app.
 *
 *           IMPORTANT — schedule the first Solcast poll BEFORE SUNRISE (e.g. 04:30). The
 *           rolling window runs from the poll time to the same clock time tomorrow, so adding
 *           back today's pre-capture generation cancels the near end but not the far end: the
 *           baseline stays overstated by roughly tomorrow's generation before that time
 *           (~0.5 kWh midwinter, 2-3 kWh midsummer), biasing the target low exactly when solar
 *           is strongest. Capturing before sunrise makes both terms zero and the rolling window
 *           becomes today's calendar total exactly. A warning is logged if the baseline is
 *           captured after sunrise.
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
    page(name: "tariffPage")
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
            if (pwTarget != null && !shouldUpdateChargeTarget()) {
                pwTargetStr += " <i>· held, charging day has ended</i>"
            }

            // Forecast estimate selection — computed once for consistency across all rows.
            // selectForecastEstimate() is a pure function, so calling it during a page render
            // cannot mutate app state.
            // Shows the LATEST poll — the numbers the charge target is actually working from —
            // with this morning's opening value alongside wherever the two differ, so a day that
            // is being revised up or down is visible at a glance rather than only in the logs.
            boolean forecastStale = solarForecastDevice ? isForecastStale() : false
            def selection = (solarForecastDevice && solarGenerationDevice) ? selectForecastEstimate() : "mid"
            def solarStr     = "–"
            def solarHighStr = "–"
            def solarLowStr  = "–"
            if (solarForecastDevice) {
                def staleTag = forecastStale ? " ⚠ stale (awaiting today's poll)" : ""
                def pending  = "Awaiting today's first forecast poll"
                def band     = { String est ->
                    def latest = getLatestForecast(est)?.round(2)
                    if (latest == null) return pending
                    def open  = getOpeningForecast(est)?.round(2)
                    def moved = ""
                    if (open != null && open != latest) {
                        double d   = latest - open
                        def    col = (d >= 0) ? "#1a7f37" : "#a12622"
                        moved = " <span style='color:${col};'>(${d >= 0 ? '+' : ''}${d.round(2)} " +
                                "since ${open} this morning)</span>"
                    }
                    return "${latest} kWh${selection == est ? ' ← active' : ''}${moved}"
                }
                def pollTime = state.forecastLatestTime
                def asOf     = pollTime ? " <i>· polled ${pollTime}</i>" : ""
                def midStr   = band("mid")
                solarStr     = (midStr == pending) ? midStr : midStr + staleTag + asOf
                solarHighStr = band("high")
                solarLowStr  = band("low")
            } else {
                solarStr     = "Not configured"
                solarHighStr = "Not configured"
                solarLowStr  = "Not configured"
            }

            // Solar generation (actual today) and the solar-noon trend projection
            def genRaw     = solarGenerationDevice ? solarGenerationDevice.currentValue("energy") : null
            def genStr     = genRaw != null ? "${genRaw} kWh" : (solarGenerationDevice ? "–" : "Not configured")
            def genDevName = solarGenerationDevice?.displayName ?: "–"

            def shapeDays = (state.solarShape?.get("10") ?: []).size()
            def baseLayer = (getGeometricProfile() != null)
                          ? "<b>Geometry</b> (${getPanelArrays().size()} array${getPanelArrays().size() == 1 ? '' : 's'})"
                          : "<b>Idealised half-sine</b> — configure Panel Array Geometry for a real curve"
            def corrLayer = (getSolarShapeProfile() != null)
                          ? " + measured correction (${shapeDays} days)"
                          : " · correction from ${shapeDays}/${shapeMinDays()} days banked"
            def noonNowD  = getSolarNoon()
            def noonFrac  = (noonNowD != null) ? getSolarDayFraction(noonNowD) : null
            def shapeStr  = baseLayer + corrLayer +
                            (noonFrac != null
                             ? " · <b>${Math.round(noonFrac * 100)}%</b> of the day done by solar noon " +
                               "<i>(a symmetric curve assumes 50%)</i>" : "")

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
                def snapLow    = getOpeningForecast("low")?.round(1)
                def snapMid    = getOpeningForecast("mid")?.round(1)
                def snapHigh   = getOpeningForecast("high")?.round(1)
                trendStr = "${genRaw} kWh at ${Math.round(dayFraction * 100)}% of solar day → projected " +
                           "<b>${implied.round(1)} kWh</b> vs this morning's [${snapLow ?: '–'} / ${snapMid ?: '–'} / ${snapHigh ?: '–'}] " +
                           "→ <b>${selection.toUpperCase()}</b>"
            } else {
                trendStr = "Awaiting generation data"
            }

            // Measured house load — a primary input to the target, so show the evidence
            // alongside the result instead of leaving it in debug logs only
            def loadKwNow = state.lastLoadKw as Double
            def loadAvgKw = getMeasuredDaytimeLoadKw()
            def loadStr
            if (!powerwallDevice) {
                loadStr = "Not configured"
            } else if (loadAvgKw == null && loadKwNow == null) {
                loadStr = "Awaiting first reading"
            } else {
                def loadDays = (state.loadHistory?.get("13") ?: []).size()
                loadStr = (loadKwNow != null ? "<b>${loadKwNow.round(2)} kW</b> now · " : "") +
                          (loadDays >= 3 ? "projected by hour from ${loadDays} days · "
                                         : "projected flat (${loadDays}/3 days banked) · ") +
                          (loadAvgKw != null ? "${loadAvgKw.round(2)} kW daylight average (measured)"
                                             : "using seasonal estimate") +
                          "<div style='color:#555; font-size:0.95em; margin-top:2px;'>" +
                          "${describeLoadProfile()}</div>"
            }

            // Current tariff period, and whether it is charging
            def nowD       = new Date()
            def curPeriod  = getPeriodAt(nowD)
            def peakStart  = getNextPeakStart(nowD)
            def modeStr
            if (curPeriod == null) {
                modeStr = "No tariff period covers now"
            } else {
                boolean vac  = (vacationDisableOffPeak == true || location.mode == "Vacation")
                boolean chg  = curPeriod.charge == true && curPeriod.type != "peak" &&
                               !(vac && curPeriod.rate > 0.0d)
                modeStr = describePeriod(curPeriod) +
                          (chg ? " &nbsp;·&nbsp; <b>charging enabled</b>" : " &nbsp;·&nbsp; not charging")
            }
            if (isExtremeConditionActive()) {
                modeStr += "<br><b>⚠ weather override active — charging in any period</b>"
            }
            if (peakStart != null) {
                modeStr += "<div style='color:#555; font-size:0.95em;'>Next peak: ${peakStart.format('EEE HH:mm')}</div>"
            }

            // Charge target working — the same figures calculateChargeTarget() uses
            def chargeCalcStr = "–"
            if (!numPowerwalls) {
                chargeCalcStr = "Not configured"
            } else if (isExtremeConditionActive()) {
                chargeCalcStr = "Severe/extreme weather — holding maximum charge, solar forecast not used"
            } else {
                def solarRem = getSolarRemainingKwh(nowD)
                def loadRem  = getLoadUntilDeadlineKwh(nowD)
                if (solarRem == null || loadRem == null || peakStart == null) {
                    chargeCalcStr = "Awaiting solar or load data"
                } else {
                    double totalCap = (numPowerwalls as Integer) * 13.5d
                    double toBatt   = Math.min(totalCap, Math.max(0.0d, solarRem - loadRem))
                    double tgtKwh   = Math.max(0.0d, totalCap - toBatt)
                    def    loadSrc  = isLoadProjectedFromHistory() ? "typical by hour"
                                    : (getMeasuredDaytimeLoadKw() != null) ? "measured" : "estimated"
                    chargeCalcStr = "By ${peakStart.format('HH:mm')}: ${solarRem.round(1)} kWh solar " +
                                    "(${selection.capitalize()}) − ${loadRem.round(1)} kWh load (${loadSrc}) " +
                                    "= ${toBatt.round(1)} kWh to battery → hold ${tgtKwh.round(1)} / ${totalCap} kWh"
                }
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
            if (!getSevereWeatherWarnings() && state.pendingSevereOnset) {
                def onsetLabel = state.pendingSevereOnset
                try {
                    onsetLabel = Date.parse("yyyy-MM-dd", state.pendingSevereOnset).format("EEEE d MMM")
                } catch (ignored) { /* fall back to the raw date string */ }
                severeStr = "None – alert deferred to ${onsetLabel}"
            }
            def gridStr   = powerGridPresence ? (getAttr(powerGridPresence, "presence") == "present" ? "Present" : "⚠️ NOT PRESENT") : "Not configured"

            // Current alert text, prefixed with why it did or did not trigger. Showing which region
            // term and which keyword actually matched makes the two comma-separated lists on the
            // Severe Weather page tunable without having to read the logs.
            def alertRaw = openWeatherDevice ? (openWeatherDevice.currentValue("alertDescrFull") ?: "") : null
            def alertStr
            if (alertRaw == null) {
                alertStr = "Not configured"
            } else if (!alertRaw.trim()) {
                alertStr = "None"
            } else {
                def lowerA = alertRaw.toLowerCase()
                def locHit = parseCSV(weatherLocations).find { lowerA.contains(it.toLowerCase()) }
                def keyHit = parseCSV(weatherKeywords).find  { lowerA.contains(it.toLowerCase()) }
                def bits   = []
                bits << (locHit ? "region <b>✓</b> ${locHit}"  : "region <b>✗</b>")
                bits << (keyHit ? "keyword <b>✓</b> ${keyHit}" : "keyword <b>✗</b>")
                if (locHit && keyHit) {
                    def onset = (alertDayCheckEnabled != false) ? getAlertOnsetOffset(alertRaw) : 0
                    bits << (onset > 0
                        ? "starts in ${onset} day${onset == 1 ? '' : 's'}"
                        : "<b>in effect today</b>")
                }
                alertStr = "<div style='margin-bottom:4px; color:#555;'>${bits.join(' &nbsp;·&nbsp; ')}</div>" +
                           "<div style='font-size:0.95em;'>${alertRaw}</div>"
            }

            // Source device names for the 3rd column
            def pwDevName    = powerwallDevice?.displayName     ?: "–"
            def stationName  = weatherStation?.displayName      ?: "–"
            def solarDevName = solarForecastDevice?.displayName ?: "–"
            def gridDevName  = powerGridPresence?.displayName   ?: "–"
            def alertDevName = openWeatherDevice?.displayName   ?: "–"

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
                "<tr style='${r1}'><td style='${tl}'><b>Forecast Low (today)</b></td>      <td style='${td}'>${solarLowStr}</td>    <td style='${ts}'>${solarDevName} latest</td></tr>" +
                "<tr style='${r0}'><td style='${tl}'><b>Forecast Mid (today)</b></td>      <td style='${td}'>${solarStr}</td>       <td style='${ts}'>${solarDevName} latest</td></tr>" +
                "<tr style='${r1}'><td style='${tl}'><b>Forecast High (today)</b></td>     <td style='${td}'>${solarHighStr}</td>   <td style='${ts}'>${solarDevName} latest</td></tr>" +
                "<tr style='${r0}'><td style='${tl}'><b>Trend Analysis</b></td>            <td style='${td}'>${trendStr}</td>       <td style='${ts}'>Calculated</td></tr>"      +
                "<tr style='${r1}'><td style='${tl}'><b>Solar Curve</b></td>               <td style='${td}'>${shapeStr}</td>       <td style='${ts}'>Measured</td></tr>"        +
                "<tr style='${r0}'><td style='${tl}'><b>House Load</b></td>                <td style='${td}'>${loadStr}</td>        <td style='${ts}'>${pwDevName}</td></tr>"    +
                "<tr style='${r1}'><td style='${tl}'><b>Target Calculation</b></td>        <td style='${td}'>${chargeCalcStr}</td>  <td style='${ts}'>Calculated</td></tr>"      +
                "<tr style='${r0}'><td style='${tl}'><b>Current Temperature</b></td>       <td style='${td}'>${currentTempStr}</td> <td style='${ts}'>${stationName}</td></tr>"  +
                "<tr style='${r1}'><td style='${tl}'><b>Today's Max Temperature</b></td>   <td style='${td}'>${dailyMaxStr}</td>    <td style='${ts}'>${stationName}</td></tr>"  +
                "<tr style='${r0}'><td style='${tl}'><b>Severe Weather Warning</b></td>    <td style='${td}'>${severeStr}</td>      <td style='${ts}'>Calculated</td></tr>"      +
                "<tr style='${r1}'><td style='${tl}'><b>Grid Status</b></td>               <td style='${td}'>${gridStr}</td>        <td style='${ts}'>${gridDevName}</td></tr>"  +
                "<tr style='${r0}'><td style='${tl}'><b>Weather Alert</b></td>             <td style='${td}'>${alertStr}</td>       <td style='${ts}'>${alertDevName}</td></tr>" +
                "</table>"
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
            def periods = getTariffPeriods()
            def tariffDesc = periods
                ? "${periods.size()} period${periods.size() == 1 ? '' : 's'}" +
                  (periods.any { it.charge && it.type != 'peak' }
                      ? " | Charging: " + periods.findAll { it.charge && it.type != 'peak' }
                            .collect { toDate(it.start).format('HH:mm') + '–' + toDate(it.end).format('HH:mm') }.join(', ')
                      : " | No charging periods enabled")
                : "Define your tariff periods — required before anything will charge"
            def chargeLevelDesc = (numPowerwalls
                ? "${numPowerwalls} × 13.5 kWh" : "Battery capacity, overrides, forecast selection")
            def severeDesc = weatherLocations
                ? "Locations: ${weatherLocations}"
                : "Region and keyword-based alert monitoring"
            def extremeDesc = (extremeWindowStart && extremeWindowEnd)
                ? "Window: ${toDate(extremeWindowStart).format('HH:mm')}–${toDate(extremeWindowEnd).format('HH:mm')}" +
                  (extremeTempThreshold ? " | Threshold: ${extremeTempThreshold}°C" : "")
                : "Backup-Only triggers and grid failure response"

            href "tariffPage",         title: "Tariff Periods",                description: tariffDesc
            href "chargeLevelPage",    title: "Charge Level",                  description: chargeLevelDesc
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
        section("<span style='${sA}'><b>Priority Overrides</b></span>") {
            paragraph "These are evaluated before the solar-aware calculation, and both override the " +
                      "tariff periods — charging runs in whatever period is active, including Peak."
            input "severeWeatherCharge", "number",
                  title: "<b>Charge target when Severe Weather Warning is active</b> (%)",
                  defaultValue: 100, required: true, range: "0..100"
            input "hotDayThreshold", "decimal",
                  title: "<b>Forecast high temperature to force a full target</b> (°C)",
                  defaultValue: 26.0, required: true
            input "hotDayWindowStart", "time",
                  title: "<b>Hot-day window start</b>", defaultValue: "11:58", required: true
            input "hotDayWindowEnd",   "time",
                  title: "<b>Hot-day window end</b>",   defaultValue: "15:00", required: true
        }

        section("<span style='${sA}'><b>Solar-Aware Charge Target</b></span>") {
            paragraph "The target is a <b>level the battery should be at right now</b>, not a fixed " +
                      "morning figure. It is recalculated continuously from what is still to come " +
                      "before the next Peak period:<br><br>" +
                      "<b>solar → battery = solar still expected before peak − house load until then</b><br>" +
                      "<b>target kWh &nbsp;&nbsp;&nbsp;= battery capacity − solar → battery</b><br><br>" +
                      "Early in the day plenty of solar is still coming, so the target sits low and grid " +
                      "charging stays out of the way. As the afternoon wears on the remaining solar " +
                      "shrinks and the target climbs, reaching <b>99% by the time peak begins</b>. That " +
                      "guarantees a full battery at peak without charging to full early and pushing the " +
                      "day's remaining solar out to export.<br><br>" +
                      "Both inputs are measured rather than assumed. Solar comes from the morning Solcast " +
                      "baseline shaped by the real sunrise/sunset curve and corrected by live inverter " +
                      "output; house load comes from the Powerwall's own load meter."
            input "numPowerwalls", "number",
                  title: "<b>Number of Powerwalls</b> (each 13.5 kWh — sets total battery capacity)",
                  required: true, defaultValue: 1, range: "1..10"
            input "annualAvgConsumptionKwh", "decimal",
                  title: "<b>Annual average daily household consumption</b> (kWh/day) – fallback only, " +
                         "used when no live load measurement is available",
                  required: false
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

        section("<span style='${sA}'><b>Panel Array Geometry</b></span>") {
            paragraph "Describe your panels and the app computes <b>when</b> your generation arrives, from " +
                      "solar geometry. Nothing here affects <i>how much</i> — that still comes from " +
                      "Solcast — only the shape of the day.<br><br>" +
                      "This matters more than it sounds. Remaining solar is projected as " +
                      "<b>generation so far × (remaining share ÷ elapsed share)</b>, so the elapsed share " +
                      "does all the work. Assuming a symmetric day when yours is not is how a 25 kWh day " +
                      "gets charged from the grid as though it will make 14.<br><br>" +
                      "A split array is the clearest case: 1.5 kW facing NE and 3.9 kW facing NW puts " +
                      "nearly three quarters of the capacity on the afternoon sun, so half the day's " +
                      "generation lands <i>after</i> solar noon plus a bit — measured at 0.46 of the day " +
                      "at solar noon against the 0.50 a symmetric curve assumes.<br><br>" +
                      "<b>Leave the count at 0 to use the idealised curve.</b>"
            input "arrayCount", "enum",
                  title: "<b>How many panel arrays?</b> (separate roof faces with different orientations)",
                  options: ["0","1","2","3","4"], defaultValue: "0", required: true, submitOnChange: true

            int ac = ((arrayCount ?: "0") as String).toInteger()
            (1..4).each { n ->
                if (n <= ac) {
                    input "array${n}Kw", "decimal",
                          title: "<b>Array ${n} — size</b> (kW of panels)", required: true
                    input "array${n}Azimuth", "number",
                          title: "<b>Array ${n} — azimuth</b> (compass degrees the panels face: " +
                                 "0 = N, 90 = E, 180 = S, 270 = W)",
                          required: true, range: "0..360"
                    input "array${n}Tilt", "number",
                          title: "<b>Array ${n} — tilt</b> (degrees from horizontal; roof pitch, " +
                                 "typically 15-30)",
                          defaultValue: 22, required: true, range: "0..90"
                }
            }
            if (ac > 0) paragraph buildArrayGeometryHtml()
        }
    }
}

/** Shows what the configured geometry implies, so a wrong azimuth is visible rather than silent. */
private String buildArrayGeometryHtml() {
    def arrays = getPanelArrays()
    if (!arrays) return "<i>Fill in every field above to see the computed curve.</i>"

    def noon = getSolarNoon()
    def frac = (noon != null) ? getGeometricDayFraction(noon) : null
    def totalKw = arrays.sum { it.kw }

    def rows = arrays.collect { a ->
        "<tr><td style='padding:2px 8px;'>${a.index}</td><td style='padding:2px 8px;'>${a.kw} kW</td>" +
        "<td style='padding:2px 8px;'>${compassName(a.azimuth)} (${a.azimuth}°)</td>" +
        "<td style='padding:2px 8px;'>${a.tilt}°</td>" +
        "<td style='padding:2px 8px;'>${Math.round((a.kw / totalKw) * 100)}%</td></tr>"
    }.join("")

    def table = "<table style='width:100%; border-collapse:collapse;'>" +
                "<tr style='background:#c5d9ea;'><th style='padding:2px 8px; text-align:left;'>#</th>" +
                "<th style='padding:2px 8px; text-align:left;'>Size</th>" +
                "<th style='padding:2px 8px; text-align:left;'>Faces</th>" +
                "<th style='padding:2px 8px; text-align:left;'>Tilt</th>" +
                "<th style='padding:2px 8px; text-align:left;'>Share</th></tr>${rows}</table>"

    if (frac == null) {
        return table + "<br><i>Waiting on sunrise/sunset data to compute today's curve.</i>"
    }
    def pct  = Math.round(frac * 100)
    def lean = (frac < 0.47) ? "afternoon-weighted" : (frac > 0.53) ? "morning-weighted" : "close to symmetric"
    return table + "<br><div style='color:#1a7f37;'><b>Today's computed curve:</b> ${pct}% of generation " +
           "by solar noon${noon != null ? ' (' + noon.format('HH:mm') + ')' : ''} — <b>${lean}</b>. " +
           "An idealised curve would assume 50%.</div>"
}

private String compassName(int deg) {
    def names = ["N","NNE","NE","ENE","E","ESE","SE","SSE","S","SSW","SW","WSW","W","WNW","NW","NNW"]
    return names[(int) Math.round(((deg % 360) / 22.5d)) % 16]
}

// ── Tariff Periods ────────────────────────────────────────────────────────────

def tariffPage() {
    dynamicPage(name: "tariffPage", title: "Tariff Periods") {
        def sA = "background-color:#e8f0f8; padding:2px 6px; border-radius:3px;"

        section("<span style='${sA}'><b>How This Works</b></span>") {
            paragraph "Describe your electricity plan as it appears on your bill, and the app works out " +
                      "the rest. Every charging decision comes from these periods.<br><br>" +
                      "<b>Peak</b> periods are the deadline — the battery must be full and the Powerwall " +
                      "back in Self-Powered before one begins. Charging never happens during Peak.<br><br>" +
                      "Tick <b>charge during this period</b> on the periods you want to buy grid energy in. " +
                      "Charging starts at the beginning of a ticked period whenever the battery is below " +
                      "target, and stops as soon as the target is met. The target itself rises through the " +
                      "day as the remaining solar shrinks, reaching 99% by the deadline — so a full battery " +
                      "at peak is guaranteed without charging to full early and exporting the afternoon's " +
                      "solar.<br><br>" +
                      "A period with a rate of <b>0</b> behaves differently: the Powerwall is held in " +
                      "Backup-Only for the whole period, so the house runs on free grid power and the " +
                      "battery never discharges.<br><br>" +
                      "Severe weather and extreme heat override all of this — the battery is charged to 99% " +
                      "in whatever period is running, including Peak."
            input "tariffPeriodCount", "enum",
                  title: "<b>How many tariff periods?</b>",
                  options: ["1","2","3","4","5","6","7","8"],
                  defaultValue: "3", required: true, submitOnChange: true
        }

        int count = ((tariffPeriodCount ?: "3") as String).toInteger()
        (1..count).each { n ->
            section("<span style='${sA}'><b>Period ${n}</b></span>") {
                input "tariff${n}Type", "enum",
                      title: "<b>Type</b>",
                      options: ["superoffpeak": "Super Off-Peak", "offpeak": "Off-Peak",
                                "shoulder": "Shoulder", "peak": "Peak"],
                      required: true, submitOnChange: true
                input "tariff${n}Start", "time", title: "<b>Start</b>", required: true
                input "tariff${n}End",   "time", title: "<b>End</b> (may pass midnight)", required: true
                input "tariff${n}Rate",  "decimal",
                      title: "<b>Rate</b> (¢/kWh) – enter 0 for a free period",
                      required: true
                input "tariff${n}Days", "enum",
                      title: "<b>Days</b> (leave blank for every day)",
                      options: ["Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday"],
                      multiple: true, required: false
                if (settings["tariff${n}Type"] != "peak") {
                    input "tariff${n}Charge", "bool",
                          title: "<b>Charge the battery during this period</b>", defaultValue: false
                }
            }
        }

        section("<span style='${sA}'><b>Coverage Check</b></span>") {
            paragraph buildTariffValidationHtml()
        }

        section("<span style='${sA}'><b>Peak Handover</b></span>") {
            input "closeoutMinutes", "number",
                  title: "<b>Force Self-Powered</b> this many minutes before a Peak period begins",
                  defaultValue: 5, required: true, range: "1..30"
        }

        section("<span style='${sA}'><b>Restrictions</b></span>") {
            input "chargingModes", "mode",
                  title: "<b>Only charge in these hub modes</b> (leave blank for all modes)",
                  multiple: true, required: false
            paragraph "<small>Vacation Mode suppresses charging in periods that cost money, but still " +
                      "permits charging in a free (0¢) period.</small>"
        }
    }
}

/**
 * Renders the configured periods as a per-day coverage report. Checks the four things that
 * actually go wrong: uncovered hours, overlapping periods, no Peak defined, and charging
 * enabled on a Peak period. Runs per weekday because day restrictions make coverage
 * day-dependent.
 */
private String buildTariffValidationHtml() {
    def periods = getTariffPeriods()
    if (!periods) return "<i>No complete periods configured yet.</i>"

    def problems = []
    def dayNames = ["Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday"]

    if (!periods.any { it.type == "peak" }) {
        problems << "No <b>Peak</b> period defined — the app has no deadline to charge towards, and will fall back to sunset."
    }
    periods.findAll { it.type == "peak" && it.charge }.each {
        problems << "Period ${it.index} is <b>Peak</b> but has charging enabled — charging never runs during Peak."
    }

    // Walk each weekday in 15-minute steps, counting how many periods cover each slot
    dayNames.eachWithIndex { dayName, dayIdx ->
        def gaps = 0, overlaps = 0
        def probe = timeToday("00:00", location.timeZone)
        // Align the probe to the named weekday so day restrictions evaluate correctly
        int todayIdx = Calendar.getInstance(location.timeZone).get(Calendar.DAY_OF_WEEK) - 2
        if (todayIdx < 0) todayIdx = 6
        int shift = ((dayIdx - todayIdx) + 7) % 7
        probe = new Date(probe.time + (shift * 86400000L))

        (0..95).each { slot ->
            def at = new Date(probe.time + (slot * 900000L))
            int covering = periods.count { isPeriodActiveAt(it, at) }
            if (covering == 0)     gaps++
            else if (covering > 1) overlaps++
        }
        if (gaps > 0)     problems << "<b>${dayName}</b>: ${(gaps * 15)} minutes not covered by any period."
        if (overlaps > 0) problems << "<b>${dayName}</b>: ${(overlaps * 15)} minutes covered by more than one period."
    }

    def rows = periods.sort { it.rate }.collect { p ->
        def label = [superoffpeak: "Super Off-Peak", offpeak: "Off-Peak",
                     shoulder: "Shoulder", peak: "Peak"][p.type] ?: p.type
        def days  = p.days ? p.days.collect { it[0..2] }.join(", ") : "every day"
        def charge = (p.type == "peak") ? "—" : (p.charge ? "✓ charge" : "—")
        "<tr><td style='padding:2px 8px;'>${p.index}</td>" +
        "<td style='padding:2px 8px;'>${label}</td>" +
        "<td style='padding:2px 8px;'>${toDate(p.start).format('HH:mm')}–${toDate(p.end).format('HH:mm')}</td>" +
        "<td style='padding:2px 8px;'>${p.rate}¢</td>" +
        "<td style='padding:2px 8px;'>${days}</td>" +
        "<td style='padding:2px 8px;'>${charge}</td></tr>"
    }.join("")

    def table = "<table style='width:100%; border-collapse:collapse;'>" +
                "<tr style='background:#c5d9ea;'><th style='padding:2px 8px; text-align:left;'>#</th>" +
                "<th style='padding:2px 8px; text-align:left;'>Type</th>" +
                "<th style='padding:2px 8px; text-align:left;'>Window</th>" +
                "<th style='padding:2px 8px; text-align:left;'>Rate</th>" +
                "<th style='padding:2px 8px; text-align:left;'>Days</th>" +
                "<th style='padding:2px 8px; text-align:left;'>Charging</th></tr>${rows}</table>"

    if (!problems) {
        return table + "<br><div style='color:#1a7f37;'><b>✓ No problems found.</b> " +
               "Every hour of every day is covered by exactly one period.</div>"
    }
    return table + "<br><div style='background:#fdecea; border:1px solid #f5c6cb; border-radius:4px; " +
           "padding:8px 12px; color:#a12622;'><b>Check these:</b><ul style='margin:4px 0 0 16px;'><li>" +
           problems.join("</li><li>") + "</li></ul></div>"
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

        section("<span style='${sA}'><b>Alert Timing</b></span>") {
            paragraph "Bureau of Meteorology alerts are often issued a day ahead — <i>\"damaging winds … " +
                      "from early Wednesday morning\"</i> published on a Tuesday. Acting immediately would " +
                      "hold the Powerwall in Backup-Only for a full extra day and waste a cycle of stored " +
                      "solar.<br><br>" +
                      "When enabled, the app reads the day references in the alert text and waits until the " +
                      "day the event actually begins. It re-checks at midnight, so an alert deferred today " +
                      "activates tomorrow without needing the text to change.<br><br>" +
                      "The check is deliberately cautious and only defers on unambiguous wording: an alert " +
                      "with no day reference, or one mentioning today at all, is treated as current. Missing " +
                      "a real warning is far worse than a wasted day of Backup-Only."
            input "alertDayCheckEnabled", "bool",
                  title: "<b>Defer alerts until their onset day</b>",
                  defaultValue: true
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
    // loadPower is house consumption only — it excludes the battery charge draw, so sampling it
    // while charging in Backup-Only cannot feed back into the target
    subscribe(powerwallDevice, "loadPower",      "loadPowerHandler")

    // ── Solar forecast ────────────────────────────────────────────────────────
    if (solarForecastDevice) {
        subscribe(solarForecastDevice, "24_Hour_Estimate",      "solarForecastHandler")
        subscribe(solarForecastDevice, "24_Hour_Estimate_Low",  "solarForecastHandler")
        subscribe(solarForecastDevice, "24_Hour_Estimate_High", "solarForecastHandler")
        // Seed the baseline if today's forecast has already landed (e.g. app saved mid-morning);
        // no-op before the first poll of the day or if a baseline is already stored
        captureForecastSnapshot()
    }

    // ── Solar generation: cumulative energy drives the trend analysis and baseline
    //    reconstruction; live power corrects the remaining-solar estimate in real time ──
    if (solarGenerationDevice) {
        subscribe(solarGenerationDevice, "energy", "solarGenerationHandler")
        subscribe(solarGenerationDevice, "power",  "solarPowerHandler")
        // Rebuild the cached generation curve — cheap, and it picks up a history that was
        // banked before an app update
        buildSolarShapeProfile()
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
    runEvery1Minute("closeoutHandler")
    // Regular sampling, so every hour's load bucket is a time-weighted mean rather than a
    // change-weighted one. See loadSampleHandler().
    runEvery1Minute("loadSampleHandler")

    // Fire one minute after the extreme-weather window opens, derived from the setting rather
    // than hardcoded to 15:05 — otherwise moving extremeWindowStart later (e.g. 16:04 on a
    // midday-saver tariff where peak begins at 4pm) means nothing fires when the window opens
    def ewFire = new Date(toDate(extremeWindowStart).time + 60000L)
    schedule("0 ${ewFire.format('m')} ${ewFire.format('H')} * * ?", "extremeWeatherHandler")

    def periods  = getTariffPeriods()
    def nextPeak = getNextPeakStart()
    log.info "Initialized – ${periods.size()} tariff period(s): " +
             periods.collect { describePeriod(it) + (it.charge && it.type != 'peak' ? " [charge]" : "") }.join("; ")
    log.info "Next peak: ${nextPeak ? nextPeak.format('EEE HH:mm') : 'none configured'}, " +
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

    // ── Rule 1: keep PW_Charge_Target current ─────────────────────────────────
    // Recalculated throughout the charging day, then held. See shouldUpdateChargeTarget().
    if (shouldUpdateChargeTarget()) {
        def previous = getGlobalVar("PW_Charge_Target")?.value?.toInteger()
        def target   = Math.min(calculateChargeTarget(), 99)
        def working  = state.lastTargetWorking ?: ""
        if (target != previous) {
            log.info "Charge target ${previous}% → ${target}% · ${working}"
        } else {
            logDebug "Charge target ${target}% · ${working}"
        }
        setGlobalVar("PW_Charge_Target", target)
        recordTargetMovement(target)
        state.chargingDayOpen = true
    } else {
        // The moment the charging day closes is the natural point to report on it
        if (state.chargingDayOpen) {
            logDaySummary()
            state.chargingDayOpen = false
        }
        logSteady("targetHeld", "Charge target: charging day has ended – holding " +
                  "${getGlobalVar('PW_Charge_Target')?.value}% until the next forecast arrives", 1800)
    }

    // ── Rule 2: charging, against the configured tariff periods ───────────────
    chargingCheck(now)
}

/**
 * One readable account of the day, logged as the charging day closes — which is the moment peak
 * begins, and the only moment where every figure that matters is final.
 *
 * Exists because there was previously no way to tell whether a day had gone well without reading
 * several thousand log lines and redoing the arithmetic by hand. Everything here is either
 * measured or already in state; the money figure is deliberately framed as a ceiling, since what
 * the battery is actually worth depends on how much of it the evening uses.
 */
private void logDaySummary() {
    def battery  = getAttr(powerwallDevice, "battery")?.toDouble()
    def capacity = numPowerwalls ? ((numPowerwalls as Integer) * 13.5d) : null
    def actual   = solarGenerationDevice?.currentValue("energy")?.toDouble()
    def baseline = getOpeningForecast("mid")
    def revision = getForecastRevision()
    def peakRate = getTariffPeriods().findAll { it.type == "peak" }*.rate.max()

    def lines = ["── Day summary ─────────────────────────────────────────"]

    if (battery != null) {
        def stored = (capacity != null) ? " (${((battery / 100.0d) * capacity).round(1)} kWh stored)" : ""
        lines << "  Entered peak at ${battery}%${stored}"
    }
    if (state.dayChargeStartPct != null) {
        lines << "  Charged from ${state.dayChargeStartPct}% at ${state.dayChargeStartTime}" +
                 (state.dayTargetMetTime ? ", target met ${state.dayTargetMetTime}" : ", target not reached")
    } else {
        lines << "  No grid charging needed today"
    }
    if (actual != null) {
        // Deliberately NOT compared against the forecast here. This runs when the charging day
        // closes, which is the start of peak — the sun is still up and on 11 Sep another 2 kWh
        // arrived after it, 8% of the day. Reporting a partial figure against a whole-day
        // forecast made a 107% day read as 98%, and that misreading led to a real misdiagnosis
        // of the solar curve. The honest comparison is logSolarDayFinal(), at midnight.
        lines << "  Solar ${actual} kWh by ${new Date().format('HH:mm', location.timeZone)} " +
                 "(still generating) · estimate used: ${state.selectedEstimate ?: 'mid'}"
        if (revision != null && Math.abs(revision) >= 0.1d) {
            def latestMid = state.forecastLatestMid as Double
            lines << "  Solcast revised ${revision >= 0 ? 'up' : 'down'} " +
                     "${Math.abs(revision).round(1)} kWh during the day (final mid ${latestMid})"
        }
    }
    def loadKw = getMeasuredDaytimeLoadKw()
    if (loadKw != null) lines << "  House load ${loadKw.round(2)} kW median of the daylight hours"
    lines << "  Solar curve: " + ((getGeometricProfile() != null) ? "geometry" : "idealised half-sine") +
             ((getSolarShapeProfile() != null) ? " + measured correction (${(state.solarShape?.get('10') ?: []).size()} days)" : "")
    lines << "  Load profile: ${describeLoadProfile()}"

    if (battery != null && capacity != null && peakRate != null) {
        double worth = (battery / 100.0d) * capacity * (peakRate / 100.0d)
        lines << "  Stored energy worth up to \$${worth.round(2)} at the ${peakRate}c peak rate"
    }
    log.info lines.join("\n")
}

/**
 * True while the charge target is still worth recalculating.
 *
 * The target only means anything inside today's solar day, working toward today's peak. Once
 * peak begins, getNextPeakStart() rolls forward to tomorrow and the calculation degrades into
 * nonsense: a deadline ~19 hours away, a full night of house load against it, and no knowledge
 * of tomorrow's solar — which pinned it at 99% every evening. Nothing acts on it at that point,
 * but it is misleading in the logs and to any external rule reading the hub variable, and it
 * would be actively wrong for anyone who enabled charging in an overnight period.
 *
 * So updates stop once today's deadline has passed and resume when the next morning's forecast
 * baseline lands. In between, the last daytime value simply stands — there is nothing new to
 * calculate from until a fresh forecast arrives.
 */
private boolean shouldUpdateChargeTarget() {
    def today = new Date().format("yyyy-MM-dd", location.timeZone)

    // A baseline dated today is the signal that a new day's forecast has arrived. With no
    // forecast device there is nothing to wait for, so the gate reduces to the deadline test.
    if (solarForecastDevice && state.forecastSnapshotDate != today) return false

    def deadline = getChargeDeadline()
    if (deadline == null) return false
    return deadline.format("yyyy-MM-dd", location.timeZone) == today
}

/**
 * The battery level the Powerwall should hold, as a percentage.
 *
 * This is a LEVEL, not a delta: it answers "where should the battery be right now", and
 * chargingCheck() simply compares the current level against it. It is deliberately
 * time-varying — as the solar still to come shrinks through the afternoon, the target rises,
 * reaching 99% by the deadline. That is what guarantees a full battery at peak without
 * charging to full early and pushing the day's remaining solar out to export.
 *
 *     solar to battery = solar still expected before the deadline − house load until then
 *     target kWh       = capacity − (solar to battery, capped at capacity)
 *     target %         = target kWh ÷ capacity, capped at 99
 *
 * Both inputs are measured rather than assumed: solar from the morning baseline shaped by the
 * sunrise/sunset curve and corrected by live generation, house load from the Powerwall's own
 * load meter. Neither the old fixed 0.90 "solar before peak" factor nor the 8-hour solar day
 * survives — the curve supplies real figures for whatever deadline is configured.
 */
def calculateChargeTarget() {
    // Priority 1: severe weather or extreme heat — hold maximum charge regardless of economics
    if (isExtremeConditionActive()) {
        def t = Math.min((severeWeatherCharge ?: 100) as Integer, 99)
        log.info "Charge target: severe/extreme weather active → ${t}%"
        return t
    }

    // Priority 2: hot-day pre-charge, a lower threshold than the extreme-weather one
    if (hotDayWindowStart && hotDayWindowEnd &&
        timeOfDayIsBetween(toDate(hotDayWindowStart), toDate(hotDayWindowEnd), new Date(), location.timeZone)) {
        def forecastHigh = getAttr(openWeatherDevice, "forecastHigh")?.toDouble()
        def threshold    = (hotDayThreshold ?: 26.0).toDouble()
        if (forecastHigh != null && forecastHigh >= threshold) {
            log.info "Charge target: hot day override (forecast ${forecastHigh}°C ≥ ${threshold}°C) → 99%"
            return 99
        }
    }

    // Priority 3: solar-aware target
    if (!numPowerwalls) {
        log.warn "Charge target: number of Powerwalls not configured → 0%"
        return 0
    }
    double capacity = (numPowerwalls as Integer) * 13.5d

    def deadline = getChargeDeadline()
    if (deadline == null) {
        log.warn "Charge target: no Peak period configured and no sunset available → 0%"
        return 0
    }

    def solarRemaining = getSolarRemainingKwh()
    def loadRemaining  = getLoadUntilDeadlineKwh()
    if (solarRemaining == null || loadRemaining == null) {
        log.warn "Charge target: solar (${solarRemaining}) or load (${loadRemaining}) estimate unavailable → 99% (safe default)"
        return 99
    }

    double solarToBattery = Math.min(capacity, Math.max(0.0d, solarRemaining - loadRemaining))
    double targetKwh      = Math.max(0.0d, capacity - solarToBattery)
    int    target         = Math.min(99, Math.round((targetKwh / capacity) * 100.0d) as Integer)

    double hoursLeft = Math.max(0.01d, (deadline.time - now().toLong()) / 3600000.0d)
    def    loadSrc   = isLoadProjectedFromHistory() ? "by hour"
                     : (getMeasuredDaytimeLoadKw() != null) ? "measured" : "estimated"

    // One line instead of three. The caller logs it at info only when the target actually
    // moves; an unchanged target is debug, so a quiet afternoon stays quiet.
    state.lastTargetWorking =
        "by ${deadline.format('HH:mm')}: solar ${solarRemaining.round(1)} − load ${loadRemaining.round(1)} " +
        "= ${solarToBattery.round(1)} kWh to battery (hold ${targetKwh.round(1)}/${capacity} kWh) · " +
        "load ${loadSrc} ${(loadRemaining / hoursLeft).round(2)} kW over ${hoursLeft.round(1)}h"
    return target
}

// ─────────────────────────────────────────────────────────────────────────────
// Tariff periods
// ─────────────────────────────────────────────────────────────────────────────

/** Reads the configured tariff periods into a list of maps, skipping incomplete rows. */
private List getTariffPeriods() {
    int count = ((tariffPeriodCount ?: "3") as String).toInteger()
    def out = []
    (1..count).each { n ->
        def type  = settings["tariff${n}Type"]
        def start = settings["tariff${n}Start"]
        def end   = settings["tariff${n}End"]
        if (!type || !start || !end) return
        out << [
            index : n,
            type  : type,
            start : start,
            end   : end,
            days  : settings["tariff${n}Days"],
            rate  : ((settings["tariff${n}Rate"] ?: 0) as BigDecimal).toDouble(),
            charge: settings["tariff${n}Charge"] == true
        ]
    }
    return out
}

/** Minutes since local midnight for a given instant. */
private int minutesOfDay(Date d) {
    def cal = Calendar.getInstance(location.timeZone)
    cal.setTime(d)
    return (cal.get(Calendar.HOUR_OF_DAY) * 60) + cal.get(Calendar.MINUTE)
}

/**
 * True when a period covers the given moment. Periods may wrap midnight (9pm–11am is ordinary
 * on an Australian tariff), in which case the day restriction is tested against the day the
 * period STARTED, not the day it is currently running into.
 *
 * The interval is HALF-OPEN — [start, end) — so back-to-back periods do not both claim the
 * instant they share. With an inclusive end, 16:00 belonged to both the 11:00–16:00 window and
 * the 16:00–21:00 window, and getPeriodAt() returns the first match, so the app considered
 * charging permitted at the exact moment peak began.
 *
 * Comparison is by minutes-since-midnight rather than by Date, because the period boundaries
 * come from timeToday() and are therefore always dated today. Comparing a probe on another
 * weekday against today's boundaries made every non-today check meaningless — the wrapping
 * period matched everything and the rest matched nothing.
 */
private boolean isPeriodActiveAt(Map p, Date when) {
    int s = minutesOfDay(toDate(p.start))
    int e = minutesOfDay(toDate(p.end))
    int t = minutesOfDay(when)

    boolean inWindow
    String  dayToCheck = when.format("EEEE", location.timeZone)

    if (s == e) {
        inWindow = true                                     // start == end means the whole day
    } else if (s < e) {
        inWindow = (t >= s && t < e)
    } else if (t >= s) {
        inWindow = true                                     // evening portion, before midnight
    } else if (t < e) {
        inWindow = true                                     // morning portion, period began yesterday
        dayToCheck = new Date(when.time - 86400000L).format("EEEE", location.timeZone)
    } else {
        inWindow = false
    }

    if (!inWindow) return false
    if (!p.days)   return true
    return p.days.contains(dayToCheck)
}

/** The tariff period covering the given moment, or null if the day has a gap. */
private Map getPeriodAt(Date when = new Date()) {
    return getTariffPeriods().find { isPeriodActiveAt(it, when) }
}

/**
 * Start of the next Peak period — the deadline the battery must be ready for, and the point
 * the closeout returns the Powerwall to Self-Powered. Searches forward up to 8 days so a
 * weekday-only peak is still found from a weekend.
 */
private Date getNextPeakStart(Date from = new Date()) {
    def peaks = getTariffPeriods().findAll { it.type == "peak" }
    if (!peaks) return null

    Date best = null
    (0..8).each { dayOffset ->
        peaks.each { p ->
            def cand = new Date(toDate(p.start).time + (dayOffset * 86400000L))
            if (!cand.after(from)) return
            if (p.days && !p.days.contains(cand.format("EEEE", location.timeZone))) return
            if (best == null || cand.before(best)) best = cand
        }
    }
    return best
}

/**
 * The moment the battery must be full by. Normally the next Peak start; if no Peak period is
 * configured there is no economic deadline, so sunset is used as a safe stand-in.
 */
private Date getChargeDeadline(Date from = new Date()) {
    def peak = getNextPeakStart(from)
    if (peak != null) return peak
    def sun = getSunriseAndSunset()
    return sun?.sunset
}

// ─────────────────────────────────────────────────────────────────────────────
// Live measurement — house load and solar generation
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Powerwall reports house consumption in watts, roughly once a minute. Samples are accumulated
 * into per-hour buckets for TODAY, rather than a single rolling average.
 *
 * A scalar average cannot represent a load that swings between roughly 0.8 kW and 4.3 kW as a
 * heat pump cycles: a fast average chases each compressor start, and a slow one lags an hour
 * behind reality. Hourly buckets sidestep the trade-off — each hour is a genuine time-weighted
 * mean including both on and off portions of the duty cycle, and averaging across several hours
 * gives a figure that is stable without being stale.
 *
 * Today's own hours are used rather than a profile learned across days, because consumption here
 * tracks weather and occupancy rather than a repeating weekly shape. The buckets reset at
 * midnight, which also means a misleading value can never persist beyond the day that produced
 * it — the failure mode a long-running exponential average had.
 */
/**
 * Keeps the current draw for display. The hourly buckets are NOT filled from here — see
 * loadSampleHandler() for why.
 */
def loadPowerHandler(evt) {
    double kw = (evt.doubleValue ?: 0.0d) / 1000.0d
    state.lastLoadKw = kw

    def sums   = state.loadHourSum   ?: [:]
    def counts = state.loadHourCount ?: [:]
    String h   = new Date().format("H", location.timeZone)
    int    c   = (counts[h] ?: 0) as Integer
    def    avg = (c > 0) ? (((sums[h] as Double) / c)).round(2) : kw.round(2)
    logSteady("loadPower", "Load power: now ${kw.round(2)} kW; hour ${h} averaging ${avg} kW over ${c} samples", 900)
}

/**
 * Samples house load once a minute into the hourly buckets.
 *
 * Deliberately a TIMER rather than the attribute event. loadPower reports on change, so an
 * event-driven sample is change-weighted: a volatile hour contributes dozens of samples and a
 * steady one contributes a handful. That is the wrong statistic for a mean, and on 11 Sep it
 * broke the load estimate outright — the quiet afternoon hours logged one sample each, fell
 * below the minimum-count gate in getMeasuredDaytimeLoadKw(), and were dropped from the median
 * entirely. The figure that survived was 1.71 kW, set by the busy morning, against an afternoon
 * that actually drew 0.70 kW. The charge target inherited the error and bought grid energy the
 * sun was about to supply.
 *
 * Sampling on a fixed interval makes each bucket a genuine time-weighted average: a five-minute
 * heat-pump burst contributes five samples in sixty, which is exactly what it was.
 */
def loadSampleHandler() {
    if (!powerwallDevice) return
    def raw = powerwallDevice.currentValue("loadPower")
    if (raw == null) return

    double kw = (raw as Double) / 1000.0d
    String h  = new Date().format("H", location.timeZone)

    def sums   = (state.loadHourSum   ?: [:])
    def counts = (state.loadHourCount ?: [:])
    sums[h]   = ((sums[h]   ?: 0.0d) as Double)  + kw
    counts[h] = ((counts[h] ?: 0)    as Integer) + 1

    state.loadHourSum   = sums
    state.loadHourCount = counts
    if (state.lastLoadKw == null) state.lastLoadKw = kw
}

/**
 * Mean house load measured across today's daylight hours so far, in kW.
 *
 * Daylight hours only: this figure is extrapolated across the afternoon, and overnight load is
 * not representative of it. Returns null until there is enough of the day measured to be worth
 * trusting, so the caller can fall back to the seasonal estimate.
 */
private Double getMeasuredDaytimeLoadKw() {
    def sums   = state.loadHourSum
    def counts = state.loadHourCount
    if (!sums || !counts) return null

    def sun = getSunriseAndSunset()
    if (sun?.sunrise == null) return null

    int firstHour = (sun.sunrise.format("H", location.timeZone) as String).toInteger()
    int nowHour   = (new Date().format("H", location.timeZone) as String).toInteger()
    if (nowHour < firstHour) return null

    double total = 0.0d
    int    n     = 0
    def    means = []
    (firstHour..nowHour).each { hr ->
        def k = "${hr}".toString()
        int c = (counts[k] ?: 0) as Integer
        if (c > 0) {
            total += (sums[k] as Double)
            n     += c
            // Low enough that a genuinely quiet hour still counts. The old threshold of 5 was
            // written for change-driven samples and silently discarded every low-draw hour;
            // with loadSampleHandler() filling the buckets on a timer, any real hour clears
            // this easily and the gate only excludes a barely-started one after a restart.
            if (c >= 2) means << ((sums[k] as Double) / c)
        }
    }
    // Roughly half an hour of samples before any of this means anything
    if (n < 30) return null

    // The MEDIAN of the hourly means, not the pooled mean of every sample. Two reasons, and
    // 8 Sep demonstrated both. A heat-pump burst from 09:20 to 09:50 left hour 9 averaging
    // 3.94 kW against 1.08-2.15 kW for every other daylight hour; because the pooled mean is
    // sample-weighted, that one busy hour pulled the figure to 2.21 kW and was still inflating
    // it six hours later. And since the result is projected flat across every remaining hour,
    // the error is multiplied by the whole window — 17.3 kWh of load was predicted to 16:00
    // against 10.5 kWh actual, which on its own pushed the charge target to 99%.
    //
    //   pooled mean (was)          2.21 kW
    //   median of hourly means     1.70 kW      ← what the afternoon actually drew
    //
    // The median needs three hours before it can outvote an outlier; below that the pooled
    // mean is all there is.
    if (means.size() < 3) return total / n
    means.sort()
    int m = means.size()
    return (m % 2 == 1) ? (means[(int) (m / 2)] as Double)
                        : (((means[(int) (m / 2) - 1] as Double) + (means[(int) (m / 2)] as Double)) / 2.0d)
}

/** Compact per-hour load profile for today, for logs and the status panel. */
private String describeLoadProfile() {
    def sums   = state.loadHourSum
    def counts = state.loadHourCount
    if (!sums || !counts) return "no measurements yet today"

    def parts = (0..23).findAll { counts["${it}".toString()] }.collect { hr ->
        def k = "${hr}".toString()
        "${hr}:00 ${(((sums[k] as Double) / (counts[k] as Integer))).round(1)}"
    }
    return parts ? parts.join("  ") + " kW" : "no measurements yet today"
}

/**
 * Inverter generation in watts. Smoothed faster than load (alpha 0.06, roughly a 15-minute
 * time constant) because it is used to detect cloud arriving, where responsiveness matters.
 */
def solarPowerHandler(evt) {
    double kw   = (evt.doubleValue ?: 0.0d) / 1000.0d
    def    prev = state.avgSolarKw as Double
    state.avgSolarKw = (prev == null) ? kw : ((0.06d * kw) + (0.94d * prev))
}

/**
 * House energy expected between now and the charging deadline, from measured load.
 * Falls back to the seasonal consumption model when no load meter is available.
 */
/**
 * Reports the finished day's generation against the band that was forecast for it. Called at
 * midnight, before the inverter's daily counter rolls over.
 *
 * This is the only point at which the comparison is fair: the day summary fires when the
 * charging day closes at the start of peak, with the sun still up. Naming the nearest estimate
 * is what tells you whether the morning trend analysis actually called the day correctly.
 */
private void logSolarDayFinal() {
    def actual = solarGenerationDevice?.currentValue("energy")?.toDouble()
    if (actual == null || actual <= 0.0d) return

    def low  = getOpeningForecast("low")
    def mid  = getOpeningForecast("mid")
    def high = getOpeningForecast("high")
    if (mid == null || mid <= 0.0d) {
        log.info "Solar final: ${actual.round(2)} kWh"
        return
    }

    def band    = [low: low, mid: mid, high: high].findAll { k, v -> v != null }
    def nearest = band.min { k, v -> Math.abs(actual - (v as Double)) }?.key
    def used    = state.selectedEstimate ?: "mid"

    log.info "Solar final: ${actual.round(2)} kWh vs this morning's " +
             "[${low != null ? low : '–'}/${mid}/${high != null ? high : '–'}] = " +
             "${Math.round((actual / mid) * 100)}% of mid · closest was ${nearest}, " +
             "app used ${used}${nearest == used ? ' ✓' : ''}"
}

/**
 * Banks today's per-hour load means into the rolling history. Called at midnight, before the
 * hourly buckets are cleared.
 */
private void rollLoadIntoHistory() {
    def sums   = state.loadHourSum
    def counts = state.loadHourCount
    if (!sums || !counts) return

    def hist = state.loadHistory ?: [:]
    (0..23).each { hr ->
        def k = "${hr}".toString()
        int c = (counts[k] ?: 0) as Integer
        if (c >= 2) {                        // see getMeasuredDaytimeLoadKw() on why this is low
            def samples = (hist[k] ?: []) as List
            samples << ((sums[k] as Double) / c)
            if (samples.size() > 20) samples = samples[-20..-1]
            hist[k] = samples
        }
    }
    state.loadHistory = hist
    int banked = (hist["13"] ?: []).size()
    logDebug "Load history: banked today's hourly profile — ${banked} day${banked == 1 ? '' : 's'} of history"
}

/**
 * What this house typically draws during a given hour of the day, from banked history, or null
 * until at least three days have been seen for that hour. Median, so one unusual day cannot
 * move it.
 */
private Double getTypicalLoadKw(int hour) {
    def samples = state.loadHistory?.get("${hour}".toString())
    if (!samples || samples.size() < 3) return null
    def sorted = samples.collect { it as Double }.sort()
    int n      = sorted.size()
    return (n % 2 == 1) ? (sorted[(int) (n / 2)] as Double)
                        : (((sorted[(int) (n / 2) - 1] as Double) +
                            (sorted[(int) (n / 2)] as Double)) / 2.0d)
}

/**
 * House load expected between now and the charging deadline, in kWh.
 *
 * Projected HOUR BY HOUR from what each hour of the day typically draws, rather than by
 * carrying one number across the whole window. A single scalar cannot describe a house whose
 * morning and afternoon are structurally different, and it fails in the direction that costs
 * money: on 10 Sep the heat pump ran 07:00-09:00 at 3.3-4.0 kW, so at 11:02 the median of the
 * elapsed daylight hours was 2.15 kW and the projection to 16:00 was 10.9 kWh. The afternoon
 * actually drew about 1.2 kW, or 6.0 kWh. Overstating load overstates the target, and roughly
 * 3 kWh of grid energy was bought that the sun would have supplied for nothing.
 *
 * Hours with no history fall back to today's median, and the whole thing falls back to the
 * seasonal model when there is no load meter at all.
 */
private Double getLoadUntilDeadlineKwh(Date from = new Date()) {
    def deadline = getChargeDeadline(from)
    if (deadline == null) return null
    double hours = (deadline.time - from.time) / 3600000.0d
    if (hours <= 0) return 0.0d

    def kw       = getMeasuredDaytimeLoadKw()
    def daily    = getDailyConsumption()
    def fallback = (daily != null) ? (daily.toDouble() / 24.0d) : null

    if (kw == null) {
        if (fallback == null) return null
        kw = fallback
        logDebug "Load estimate: no measured load, using seasonal model ${kw.round(2)} kW"
    } else if (fallback != null) {
        // Gross-error guard only. loadPower reports house consumption alone — it excludes the
        // battery charge draw — so a genuinely heavy day is real information and must not be
        // clipped; that measurement is the whole reason for preferring the meter over the
        // typed-in annual average. The band is therefore wide enough to pass any plausible
        // household while still catching a unit mix-up or a meter stuck at a silly value.
        double capped = Math.max(fallback * 0.1d, Math.min(fallback * 5.0d, kw))
        if (capped != kw) {
            log.warn "Load estimate: measured average ${kw.round(2)} kW is implausible against the " +
                     "seasonal expectation (${fallback.round(2)} kW) – clamped to ${capped.round(2)} kW. " +
                     "Check the load meter, or the configured annual average consumption."
            kw = capped
        }
    }

    // Walk the window hour by hour, using each hour's own typical draw where history allows
    double  total       = 0.0d
    boolean usedHistory = false
    long    cursor      = from.time
    int     guard       = 0
    while (cursor < deadline.time && guard++ < 48) {
        def cal = Calendar.getInstance(location.timeZone)
        cal.setTimeInMillis(cursor)
        int hr = cal.get(Calendar.HOUR_OF_DAY)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        long   segEnd = Math.min(cal.getTimeInMillis() + 3600000L, deadline.time)
        double span   = (segEnd - cursor) / 3600000.0d

        def typical = getTypicalLoadKw(hr)
        if (typical != null) {
            usedHistory = true
            total += (typical as Double) * span
        } else {
            total += (kw as Double) * span
        }
        cursor = segEnd
    }
    if (usedHistory) logSteady("loadByHour", "Load estimate: ${total.round(1)} kWh to " +
        "${deadline.format('HH:mm')}, projected from each hour's typical draw", 900)
    return total
}

/**
 * Whether the load projection is coming from banked history rather than today's average.
 *
 * Pure — it only reads. The label it feeds is rendered on the settings page, and a function
 * called during a page render must not write state.
 */
private boolean isLoadProjectedFromHistory(Date from = new Date()) {
    def cal = Calendar.getInstance(location.timeZone)
    cal.setTime(from)
    return getTypicalLoadKw(cal.get(Calendar.HOUR_OF_DAY)) != null
}

/**
 * Generation the curve predicts right now, in kW. The cumulative solar-day curve is
 * f(e) = (1 − cos(πe))/2, so its derivative — instantaneous share of the day's total — is
 * (π/2)·sin(πe), which integrates to exactly 1.0 across the day and is therefore consistent
 * with the baseline by construction.
 */
private Double getExpectedSolarKw(Date at = new Date()) {
    // Read straight off whichever curve is in force by differencing it across an hour centred
    // on `at`. Layer-agnostic, so geometry, half-sine and the banked correction all work, and
    // it needs no separate derivative to be kept consistent with the cumulative form.
    def full = clampForecastToActual(getLatestForecast(selectForecastEstimate()))
    if (full == null) return null

    def before = getSolarDayFraction(new Date(at.time - 1800000L))
    def after  = getSolarDayFraction(new Date(at.time + 1800000L))
    if (before == null || after == null) return null

    return full * Math.max(0.0d, (after as Double) - (before as Double))   // kWh across 1 h = kW
}

/**
 * Generation banked so far as a fraction of what the baseline predicted by now, used to
 * correct the remaining-solar estimate. Returns null when it cannot be judged.
 *
 * Deliberately CUMULATIVE rather than instantaneous. Comparing live power against the curve's
 * predicted power carries a shape error that is not a weather signal — a real clear-sky day is
 * more peaked than a half-sine, because sun elevation concentrates output around midday more
 * sharply than time-of-day alone — and in practice that pinned the ratio at its 1.4 ceiling on
 * ordinary mornings. Comparing energy-to-date integrates the shape error out.
 *
 * This is not double-correcting against the trend analysis: that picks the nearest member of
 * the Low/Mid/High band, and this refines continuously within it.
 */
private Double getSolarPerformanceRatio(Date at = new Date()) {
    if (!solarGenerationDevice) return null

    def full = clampForecastToActual(getLatestForecast(selectForecastEstimate()))
    if (full == null || full <= 0) return null

    def fraction = getSolarDayFraction(at)
    if (fraction == null || fraction < 0.15d) return null   // too early to divide by meaningfully

    def actual = solarGenerationDevice.currentValue("energy")?.toDouble()
    if (actual == null) return null

    double expectedSoFar = full * fraction
    if (expectedSoFar <= 0.5d) return null

    // The bounds are deliberately asymmetric. Understating remaining solar only over-charges at
    // the cheap rate, while overstating it enters peak short and imports at the expensive one —
    // so the floor sits well below the ceiling. Observed live: a heavily overcast morning ran a
    // true ratio of 0.36 and spent 90 minutes pinned against a 0.4 floor, which was nudging the
    // estimate in the wrong direction. A genuinely dead inverter is caught by isSolarStopped().
    return Math.max(0.2d, Math.min(1.4d, actual / expectedSoFar))
}

/**
 * True when generation has effectively stopped while the curve still expects meaningful
 * output — late cloud, an inverter dropping out, or terrain shading the sun-angle model knows
 * nothing about. The EMA's lag means a low reading already implies it has been sustained.
 */
private boolean isSolarStopped(Date at = new Date()) {
    def avgKw = state.avgSolarKw as Double
    if (avgKw == null) return false
    def expected = getExpectedSolarKw(at)
    if (expected == null || expected < 0.5d) return false
    return avgKw < 0.15d
}

/**
 * Solar still expected to arrive between now and the charging deadline, in kWh.
 * Baseline forecast shaped by the sunrise/sunset curve, then corrected by measured output.
 */
private Double getSolarRemainingKwh(Date from = new Date()) {
    def deadline = getChargeDeadline(from)
    if (deadline == null) return null

    def full = clampForecastToActual(getLatestForecast(selectForecastEstimate()))
    if (full == null) return null

    if (isSolarStopped(from)) {
        logDebug "Solar remaining: generation has stopped while the curve still expects output → 0 kWh"
        return 0.0d
    }

    def fNow      = getSolarDayFraction(from)
    def fDeadline = getSolarDayFraction(deadline)
    if (fNow == null || fDeadline == null) return null

    double raw   = full * Math.max(0.0d, fDeadline - fNow)
    def    ratio = getSolarPerformanceRatio(from)
    if (ratio != null) {
        logSteady("solarRemaining", "Solar remaining: ${raw.round(2)} kWh from curve × " +
                  "${ratio.round(2)} measured ratio", 600)
        return raw * ratio
    }
    return raw
}

// ─────────────────────────────────────────────────────────────────────────────
// Rule 2: Charging
// ─────────────────────────────────────────────────────────────────────────────

/**
 * One rule covers every tariff shape:
 *
 *     in a chargeable period, battery below target  →  Backup-Only
 *     otherwise                                     →  Self-Powered
 *
 * All of the intelligence lives in the target (see calculateChargeTarget), which rises through
 * the day as the solar still to come shrinks, and reaches 99% by the deadline. There is no
 * late-start calculation: charging begins at the start of a chargeable period whenever the
 * battery is below target, and stops as soon as it is met.
 */
def chargingCheck(Date now = new Date()) {
    if (!powerwallDevice) return

    boolean weatherOverride = isExtremeConditionActive()
    def     period          = getPeriodAt(now)

    if (period == null && !weatherOverride) {
        logSteady("noPeriod", "Charging: no tariff period covers ${now.format('HH:mm')} – leaving the Powerwall alone")
        return
    }
    if (chargingModes && !chargingModes.contains(location.mode)) {
        logDebug "Charging: skipped (hub mode '${location.mode}' not in allowed modes)"
        return
    }

    def battery = getAttr(powerwallDevice, "battery")?.toDouble()
    if (battery == null) { log.warn "Charging: battery level unavailable"; return }

    def opState = getAttr(powerwallDevice, "currentOpState")
    def target  = getGlobalVar("PW_Charge_Target")?.value?.toInteger() ?: 0

    // Grid outage stands everything down — gridPresenceHandler has already moved the Powerwall
    // to Self-Powered to preserve the battery, and there is nothing to import during an outage
    if (powerGridPresence && getAttr(powerGridPresence, "presence") != "present") {
        logDebug "Charging: skipped (grid not present)"
        return
    }

    // Weather override: economics no longer apply. Charge in any period, including Peak.
    if (weatherOverride) {
        if (battery < target) {
            if (opState != "Backup-Only") {
                setPowerwallBackupOnly("weather override – ${battery}% → ${target}% (ignoring tariff periods)")
            } else {
                logSteady("wxCharging", "Charging: weather override, already charging (${battery}% → ${target}%)")
            }
        } else {
            extremeWeatherCheck()   // conditions decide the mode once the target is met
        }
        return
    }

    boolean vacation   = (vacationDisableOffPeak == true || location.mode == "Vacation")
    boolean chargeable = (period.charge == true && period.type != "peak")

    // Vacation suppresses paid charging only. Free energy is worth taking whether or not
    // anyone is home, so a zero-rate period still charges.
    if (chargeable && vacation && period.rate > 0.0d) {
        chargeable = false
        logDebug "Charging: period ${period.index} suppressed (vacation, rate ${period.rate}c)"
    }

    if (!chargeable) {
        if (opState != "Self-Powered") {
            setPowerwallSelfPowered("not a charging period (${describePeriod(period)})")
        } else {
            logSteady("notChargeable", "Charging: ${describePeriod(period)} is not chargeable, already Self-Powered")
        }
        return
    }

    // A zero-rate period is held in Backup-Only for its whole length: the house runs on free
    // grid while the battery fills and never discharges. Dropping to Self-Powered on reaching
    // target — correct when energy costs money — would spend free hours draining the battery.
    if (period.rate <= 0.0d) {
        if (opState != "Backup-Only") {
            setPowerwallBackupOnly("free period ${describePeriod(period)} – battery ${battery}%")
        } else {
            logSteady("freeHold", "Charging: free period, holding Backup-Only (battery ${battery}%)")
        }
        return
    }

    // Never begin a new session inside the closeout window. The target climbs toward 99% as the
    // deadline nears, so the battery can dip below it with only a minute or two of the period
    // left — long enough to command Backup-Only, not long enough to gain anything before the
    // closeout hands back to Self-Powered. An existing session is left alone to run out the
    // clock; only starting one is suppressed.
    def peakStart = getNextPeakStart(now)
    if (peakStart != null && opState != "Backup-Only") {
        long closeoutMs = ((closeoutMinutes ?: 5) as Integer) * 60000L
        if (now.time >= peakStart.time - closeoutMs) {
            logDebug "Charging: within the closeout window before ${peakStart.format('HH:mm')} – " +
                     "not starting a new session (${battery}% vs target ${target}%)"
            return
        }
    }

    // Paid chargeable period.
    //
    // The band is deliberately ASYMMETRIC. A 2% deadband below the target is enough to absorb
    // reporting jitter, but it is not enough on the way out: through the afternoon the target
    // ratchets upward by 2-4 points every quarter hour as the remaining solar shrinks, so
    // stopping the moment the battery reaches it guarantees the next evaluation finds the
    // battery below the new target and starts again. Observed on 9 Sep: eight commanded mode
    // changes between 13:28 and 15:04, none of which changed the outcome — the battery was
    // climbing to 99% either way.
    //
    // So the stop threshold overshoots by however far the target actually climbed over the
    // previous quarter hour. On a day when the target is flat or falling the margin is zero and
    // this behaves exactly as before; the overshoot only appears when there is a ratchet to
    // absorb. It cannot push past 99%, so the endgame into peak is unchanged.
    double deadband   = 2.0d
    double stopMargin = getTargetStopMargin()
    double stopAt     = Math.min(99.0d, target + stopMargin)

    if (battery < target - deadband) {
        if (opState != "Backup-Only") {
            if (state.dayChargeStartPct == null) {
                state.dayChargeStartPct  = battery
                state.dayChargeStartTime = new Date().format("HH:mm", location.timeZone)
            }
            setPowerwallBackupOnly("${describePeriod(period)} – ${battery}% → ${target}%")
        } else {
            logSteady("charging", "Charging: in progress (${battery}% → ${target}%)")
        }
    } else if (battery >= stopAt) {
        if (opState != "Self-Powered") {
            if (state.dayTargetMetTime == null && state.dayChargeStartPct != null) {
                state.dayTargetMetTime = new Date().format("HH:mm", location.timeZone)
            }
            def why = (stopMargin > 0.0d)
                    ? "target met – ${battery}% ≥ ${target}% + ${stopMargin.round(0)}% climb allowance"
                    : "target met – ${battery}% ≥ ${target}%"
            setPowerwallSelfPowered(why)
        } else {
            logSteady("targetMet", "Charging: target met (${battery}% ≥ ${target}%), already Self-Powered")
        }
    } else if (opState == "Backup-Only") {
        // Above the target but below the overshoot mark: keep charging rather than stopping into
        // a target that is about to rise past the battery again.
        logSteady("chargeThrough", "Charging: continuing through the climbing target " +
                  "(${battery}% vs target ${target}%, stopping at ${stopAt.round(0)}%)")
    } else {
        logSteady("deadband", "Charging: within deadband (${battery}% vs target ${target}%), no change")
    }
}

/**
 * Tracks how fast the charge target is climbing, in percentage points per quarter hour.
 *
 * An anchor is taken every 15 minutes and the rise since the previous anchor is kept. That is
 * the natural predictor for what the target will do before the next evaluation, and it is read
 * off actual movement rather than assumed, so it adapts to the season and to the day.
 */
private void recordTargetMovement(int target) {
    long t  = now()
    def  am = state.targetAnchorMs as Long
    if (am == null || (t - am) > 900000L) {
        def ap = state.targetAnchorPct as Integer
        if (ap != null) state.targetLastRise = Math.max(0, target - ap)
        state.targetAnchorPct = target
        state.targetAnchorMs  = t
    }
}

/** How far the target is expected to climb before the next evaluation, in points. */
private double getTargetStopMargin() {
    def rise = state.targetLastRise as Integer
    def am   = state.targetAnchorMs as Long
    if (rise == null || am == null) return 0.0d
    if ((now() - am) > 2700000L) return 0.0d   // no movement in 45 minutes: the ratchet has stopped
    return Math.min(6.0d, Math.max(0.0d, rise as Double))
}

/** Short human label for a period, used in mode-change reasons and the status panel. */
private String describePeriod(Map p) {
    if (p == null) return "no period"
    def label = [superoffpeak: "Super Off-Peak", offpeak: "Off-Peak",
                 shoulder: "Shoulder", peak: "Peak"][p.type] ?: p.type
    return "${label} ${toDate(p.start).format('HH:mm')}–${toDate(p.end).format('HH:mm')} @ ${p.rate}c"
}

/**
 * Runs every minute. Forces Self-Powered in the closing minutes before Peak begins, bypassing
 * the mode-change cooldown: the peak boundary is a hard deadline and the reported device state
 * can be stale, so the command is repeated until it takes.
 */
def closeoutHandler(evt = null) {
    def now  = new Date()
    def peak = getNextPeakStart(now)
    if (peak == null) return

    long closeoutMs = ((closeoutMinutes ?: 5) as Integer) * 60000L
    if (now.time < peak.time - closeoutMs || now.time >= peak.time) return
    if (chargingModes && !chargingModes.contains(location.mode)) return

    // Severe or extreme weather wants Backup-Only through the peak period — delegate rather
    // than clearing a weather-driven state on the way in
    if (isExtremeConditionActive()) {
        log.info "Closeout: extreme/severe weather active – delegating mode decision to extremeWeatherCheck()"
        extremeWeatherCheck()
        return
    }

    def opState = getAttr(powerwallDevice, "currentOpState")
    log.info "Closeout: peak begins ${peak.format('HH:mm')} – forcing Self-Powered (reported state: ${opState})"
    powerwallDevice.setSelfPoweredMode()
    // now.time, not now() — the local Date shadows Hubitat's now(), and calling it threw
    // MissingMethodException: java.util.Date.call() on every closeout tick
    state.lastModeChangeMs = now.time
}

// ─────────────────────────────────────────────────────────────────────────────
// Solar Forecast
// ─────────────────────────────────────────────────────────────────────────────

def solarForecastHandler(evt) {
    // Keyed per attribute. A single shared "last value" was being read and written by all three
    // handlers, so every line compared against whichever attribute happened to fire last and
    // reported transitions that never occurred — 8 Sep logged "24_Hour_Estimate_Low: 25.2 →
    // 19.63" when 25.2 had been the High.
    def key      = "lastForecast_${evt.name}".toString()
    def previous = state[key] as Double
    def current  = evt.doubleValue
    state[key]   = current
    if (previous != null) {
        log.info "── Solar forecast updated (${evt.name}): ${previous} kWh → ${current} kWh → recalculating charge target ──"
    } else {
        log.info "── Solar forecast updated (${evt.name}): ${current} kWh → recalculating charge target ──"
    }
    // Stores the latest band every poll, and the opening band only on the first — so the
    // target tracks same-day revisions while the trend reference stays pre-morning.
    captureForecastSnapshot(evt.name)
    chargeCheckHandler()
}

def solarGenerationHandler(evt) {
    logSteady("solarGen", "Solar generation: ${evt.value} kWh today", 900)
    recordSolarShapeSample(evt.doubleValue)
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
    double current = evt.doubleValue
    def    prev    = state.lastForecastHigh as Double

    // OpenWeather nudges this every few minutes by a tenth of a degree, and each one used to
    // trigger a full recalculation cascade — three times the work and three times the log volume
    // for no change in outcome. Only react to a move large enough to matter, or one that crosses
    // either temperature threshold the app actually acts on.
    if (prev != null) {
        double hotDay  = (hotDayThreshold      ?: 26.0).toDouble()
        double extreme = (extremeTempThreshold ?: 35.0).toDouble()
        boolean crossed = [hotDay, extreme].any { t -> (prev < t) != (current < t) }
        if (!crossed && Math.abs(current - prev) < 0.5d) {
            state.lastForecastHigh = current
            logDebug "Forecast high ${current}°C (was ${prev}°C) – below the 0.5°C threshold, no re-evaluation"
            return
        }
    }

    state.lastForecastHigh = current
    log.info "── Forecast high: ${prev != null ? prev.toString() + '°C → ' : ''}${current}°C → re-evaluating ──"
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
    chargingCheck()
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

    // A matched alert may describe an event that does not begin until a later day. Holding the
    // Powerwall in Backup-Only a day early wastes a full cycle of stored solar, so defer until
    // the onset day — but only when the text is unambiguous about it (see getAlertOnsetOffset).
    boolean alertActive = locationMatch && keywordMatch
    if (alertActive && alertDayCheckEnabled != false) {
        Integer onset = getAlertOnsetOffset(alertText)
        if (onset > 0) {
            def onsetDate = new Date(now() + (onset * 86400000L))
            state.pendingSevereOnset = onsetDate.format("yyyy-MM-dd", location.timeZone)
            alertActive = false
            log.info "Severe weather: alert matched but refers only to ${onsetDate.format('EEEE d MMM')} " +
                     "(${onset} day${onset == 1 ? '' : 's'} away) – deferring until then"
        } else {
            state.pendingSevereOnset = null
        }
    } else if (alertActive) {
        state.pendingSevereOnset = null
    }

    boolean newState
    if (alertActive) {
        log.info "Severe weather: alert matched region and keyword → SevereWeatherWarnings = true"
        newState = true
    } else {
        // Fallback: extreme heat threshold. Still applies to a deferred alert — today's measured
        // temperature is current regardless of when the forecast wind event begins.
        def alertReason = state.pendingSevereOnset ? "alert deferred to a later day" : "no alert match"
        def maxTemp     = getCurrentMaxTemp()
        def threshold   = (extremeHeatThreshold ?: 35.0).toDouble()
        if (maxTemp != null && maxTemp >= threshold) {
            log.info "Severe weather: ${alertReason}, but extreme heat (${maxTemp}°C ≥ ${threshold}°C) → SevereWeatherWarnings = true"
            newState = true
        } else {
            log.info "Severe weather: ${alertReason} and heat below threshold (${maxTemp}°C < ${threshold}°C) → SevereWeatherWarnings = false"
            newState = false
        }
    }

    def previous = getSevereWeatherWarnings()
    if (previous != newState) {
        setGlobalVar("SevereWeatherWarnings", newState)
        log.info "SevereWeatherWarnings changed: ${previous} → ${newState} → re-evaluating extreme weather and charge target"
        extremeWeatherCheck()
        // The warning is Priority 2 in the charge target ladder, so the stored target is now
        // stale — a cleared warning leaves PW_Charge_Target pinned at severeWeatherCharge until
        // the next scheduled run, and possibly past the evaluation window entirely.
        // Runs after extremeWeatherCheck() so that charging logic has the final say on the
        // Powerwall mode while a charging window is open.
        chargeCheckHandler()
    } else {
        log.info "SevereWeatherWarnings unchanged (${newState}), no cascade"
    }
}

/**
 * Works out how many days from today an alert first takes effect, by reading the day
 * references in its text. Returns 0 for "in effect today", or a positive day offset.
 *
 * Bureau of Meteorology text carries its timing in prose rather than in a structured field —
 * "DAMAGING WINDS ... are possible throughout the central ranges from early Wednesday morning"
 * — so this scans for relative markers (today, tonight, tomorrow) and weekday names, resolving
 * each weekday to its next occurrence, then takes the earliest.
 *
 * The rule is deliberately asymmetric, because failing to act on a live warning is far worse
 * than a day of unnecessary Backup-Only:
 *
 *   - No day reference at all      → 0 (treat as current; the text tells us nothing)
 *   - Today referenced anywhere    → 0 (even alongside later days, the event starts today)
 *   - Only future days referenced  → earliest of those
 *
 * Keeping today's own weekday name as an activator also covers alerts that carry an issue line
 * such as "Issued at 5:00 am Tuesday": on a Tuesday that reads as current rather than deferred.
 * The cost is an occasional missed deferral; the alternative risks a missed storm.
 */
private Integer getAlertOnsetOffset(String alertText) {
    if (!alertText) return 0
    def lower = alertText.toLowerCase()

    Set<Integer> offsets = []

    // Relative references to today
    ["today", "tonight", "this morning", "this afternoon", "this evening", "this night"].each {
        if (lower.contains(it)) offsets << 0
    }
    if (lower.contains("tomorrow")) offsets << 1

    // Weekday names resolve to their next occurrence — 0 when the name is today
    def dayNames = ["sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"]
    int todayIdx = Calendar.getInstance(location.timeZone).get(Calendar.DAY_OF_WEEK) - 1  // 0 = Sunday
    dayNames.eachWithIndex { name, idx ->
        if (lower.contains(name)) offsets << (((idx - todayIdx) + 7) % 7)
    }

    if (offsets.isEmpty()) {
        logDebug "Alert onset: no day reference found – treating as current"
        return 0
    }
    if (offsets.contains(0)) {
        logDebug "Alert onset: text references today – active now"
        return 0
    }
    def earliest = offsets.min()
    logDebug "Alert onset: earliest day reference is ${earliest} day(s) away (offsets found: ${offsets.sort()})"
    return earliest
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
        // A chargeable tariff period owns the Powerwall's mode while it runs. Without this
        // guard the scheduled extreme-weather check would force Self-Powered mid-period and
        // cancel charging — a period can easily extend past extremeWindowStart.
        def activePeriod = getPeriodAt()
        if (activePeriod != null && activePeriod.charge == true && activePeriod.type != "peak") {
            log.info "Extreme weather: conditions clear, but ${describePeriod(activePeriod)} is a " +
                     "charging period – leaving the mode to chargingCheck()"
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

    // Which member of the band today is tracking comes from the morning trend check; the
    // magnitude comes from the most recent poll, because Solcast keeps revising the same day's
    // total as satellite imagery arrives and the target only cares about solar still to come.
    def selection = selectForecastEstimate()
    def latest    = getLatestForecast(selection)
    if (latest == null) {
        log.warn "Solar forecast: no ${selection.toUpperCase()} estimate available"
        return null
    }
    logDebug "Solar forecast: using ${selection.toUpperCase()} estimate = ${latest.round(2)} kWh for today"
    return latest
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

// ─────────────────────────────────────────────────────────────────────────────
// The solar day curve
//
// Everything solar-aware rests on one question: what fraction of today's generation has
// already happened? Remaining solar reduces to actual × Δf ÷ f_now, so that fraction does all
// the work — the forecast total cancels out once the performance ratio is live.
//
// It is answered in three layers, each falling back to the one below:
//
//   1. GEOMETRY      Computed from the panel arrays, latitude and the date. Correct on day
//                    one, immune to weather, and exact through the seasons because solar
//                    declination is a function of the date. Needs the arrays configured.
//   2. IDEALISED     A half-sine between sunrise and sunset. Assumes a symmetric day, which
//                    no real array delivers. Only used when the arrays are not configured.
//   3. MEASUREMENT   A median correction on top of whichever of the above is in force,
//                    learned from banked days. Picks up what geometry cannot see: terrain
//                    shading, soiling, a dropped string.
//
// Measured days are banked as a DIFFERENCE from the computed curve rather than as the curve
// itself. That keeps the learned quantity small and centred near zero, so day-to-day weather
// largely cancels in the median instead of being mistaken for the site's shape.
// ─────────────────────────────────────────────────────────────────────────────

// Tuning, inlined rather than held in static fields — Hubitat's app sandbox is unfriendly to
// those. 20 slices puts each about 35 minutes wide at the equinox; 5 days before the measured
// correction is applied; 20 days of history behind the median.
private int shapeBuckets() { return 20 }
private int shapeMinDays() { return 5 }
private int shapeHistory() { return 20 }

/** The configured panel arrays, or an empty list. */
private List getPanelArrays() {
    int count = ((arrayCount ?: "0") as String).toInteger()
    def out = []
    (1..4).each { n ->
        if (n <= count) {
            def kw = settings["array${n}Kw"]
            def az = settings["array${n}Azimuth"]
            def ti = settings["array${n}Tilt"]
            if (kw != null && az != null && (kw as Double) > 0) {
                out << [index: n, kw: (kw as Double), azimuth: (az as Integer),
                        tilt: ((ti ?: 22) as Integer)]
            }
        }
    }
    return out
}

/**
 * Today's generation curve computed from solar geometry, as shapeBuckets() cumulative
 * fractions. Null when the arrays are not configured or the sun times are unavailable.
 *
 * Standard textbook solar position. Declination comes from the date; the hour angle is
 * measured from solar noon taken as the midpoint of the hub's own sunrise and sunset, which
 * absorbs the equation of time and the longitude correction without computing either. Beam
 * irradiance uses the Meinel clear-sky air-mass model with an isotropic diffuse component.
 *
 * Only the SHAPE is used — magnitude comes from Solcast — so the crude irradiance model costs
 * little and the result is barely sensitive to tilt. Validated against a cloudless day at the
 * reference site: mean error against actual generation 0.019 versus 0.038 for the half-sine,
 * and 0.460 of the day complete at solar noon against a measured 0.464 (the half-sine says
 * 0.500). What it cannot see is terrain — layer 3 exists for that.
 */
private List getGeometricProfile() {
    def today = new Date().format("yyyy-MM-dd", location.timeZone)
    if (state.geoProfileDate == today && state.geoProfile != null) return state.geoProfile

    def arrays = getPanelArrays()
    if (!arrays) return null
    def sun = getSunriseAndSunset()
    if (sun?.sunrise == null || sun?.sunset == null) return null
    long dayMs = sun.sunset.time - sun.sunrise.time
    if (dayMs <= 0) return null

    double lat = (location.latitude ?: 0.0d) as Double
    if (lat == 0.0d) return null
    double phi = Math.toRadians(lat)

    def cal = Calendar.getInstance(location.timeZone)
    cal.setTime(new Date())
    int doy = cal.get(Calendar.DAY_OF_YEAR)
    double decl = Math.toRadians(23.45d * Math.sin(Math.toRadians(360.0d * (284 + doy) / 365.0d)))

    long noonMs = (sun.sunrise.time + sun.sunset.time) / 2

    // Integrate plane-of-array output in one-minute steps across the solar day
    int    steps = 240
    double stepH = (dayMs / 3600000.0d) / steps
    def    cum   = []
    double run   = 0.0d
    (0..(steps - 1)).each { i ->
        long   t     = sun.sunrise.time + (long) ((i + 0.5d) * dayMs / steps)
        double hours = (t - noonMs) / 3600000.0d
        double w     = Math.toRadians(15.0d * hours)

        double sinAlt = (Math.sin(phi) * Math.sin(decl)) +
                        (Math.cos(phi) * Math.cos(decl) * Math.cos(w))
        double p = 0.0d
        if (sinAlt > 0.02d) {
            double alt   = Math.asin(sinAlt)
            double cosAz = ((Math.sin(decl) * Math.cos(phi)) -
                            (Math.cos(decl) * Math.sin(phi) * Math.cos(w))) /
                           Math.max(1e-9d, Math.cos(alt))
            double az    = Math.acos(Math.max(-1.0d, Math.min(1.0d, cosAz)))
            if (Math.sin(w) > 0) az = (2.0d * Math.PI) - az     // afternoon: sun past due N/S
            double dni   = 1000.0d * Math.pow(0.7d, Math.pow(1.0d / sinAlt, 0.678d))

            arrays.each { a ->
                double b   = Math.toRadians(a.tilt as Integer)
                double cth = (Math.cos(alt) * Math.sin(b) * Math.cos(az - Math.toRadians(a.azimuth as Integer))) +
                             (Math.sin(alt) * Math.cos(b))
                double poa = (dni * Math.max(0.0d, cth)) + (0.15d * dni * (1.0d + Math.cos(b)) / 2.0d)
                p += (a.kw as Double) * poa / 1000.0d
            }
        }
        run += p * stepH
        cum << run
    }
    if (run <= 0.0d) return null

    // Resample the fine integration onto the shapeBuckets() slice boundaries
    def profile = []
    (1..shapeBuckets()).each { i ->
        int idx = Math.min(steps - 1, (int) ((i / (double) shapeBuckets()) * steps) - 1)
        profile << ((cum[Math.max(0, idx)] as Double) / run)
    }
    state.geoProfile     = profile
    state.geoProfileDate = today
    return profile
}

/** The idealised half-sine expressed on the same slice grid, so the layers are interchangeable. */
private List getHalfSineProfile() {
    def profile = []
    (1..shapeBuckets()).each { i ->
        double e = i / (double) shapeBuckets()
        profile << ((1.0d - Math.cos(Math.PI * e)) / 2.0d)
    }
    return profile
}

/** Whichever computed curve is in force before the measured correction is applied. */
private List getBaseProfile() {
    return getGeometricProfile() ?: getHalfSineProfile()
}

/** Raw linear position through the solar day: 0.0 at sunrise, 1.0 at sunset. */
private Double getSolarElapsed(Date at) {
    def sun = getSunriseAndSunset()
    if (sun?.sunrise == null || sun?.sunset == null) return null
    long dayMs = sun.sunset.time - sun.sunrise.time
    if (dayMs <= 0) return null
    return (at.time - sun.sunrise.time) / (double) dayMs
}

/** Linear interpolation of a cumulative slice profile at elapsed fraction e. */
private double interpProfile(List profile, double e, double atZero = 0.0d) {
    double width = 1.0d / shapeBuckets()
    int    idx   = Math.min(shapeBuckets() - 1, (int) (e / width))
    double pos   = (e - (idx * width)) / width
    double prev  = (idx == 0) ? atZero : (profile[idx - 1] as Double)
    double cur   = profile[idx] as Double
    return prev + (pos * (cur - prev))
}

/** Today's computed curve at a given time, geometry only — used by the settings preview. */
private Double getGeometricDayFraction(Date at) {
    def profile = getGeometricProfile()
    if (profile == null) return null
    def e = getSolarElapsed(at)
    if (e == null) return null
    if (e <= 0.0d) return 0.0d
    if (e >= 1.0d) return 1.0d
    return interpProfile(profile, e)
}

/** The banked median correction to the computed curve, or null if too few days. */
private List getSolarShapeProfile() {
    return state.solarShapeProfile
}

/** Rebuilds the cached correction from the banked history. Called at midnight and on save. */
private void buildSolarShapeProfile() {
    state.solarShapeProfile = computeSolarShapeProfile()
}

private List computeSolarShapeProfile() {
    def shape = state.solarShape
    if (!shape) return null

    int banked = 0
    shape.each { k, v -> if (v != null && v.size() > banked) banked = v.size() }
    if (banked < shapeMinDays()) return null

    def corrections = []
    (0..(shapeBuckets() - 1)).each { i ->
        def samples = shape["${i}".toString()]
        if (samples && samples.size() >= 3) {
            def    sorted = samples.collect { it as Double }.sort()
            int    n      = sorted.size()
            corrections << ((n % 2 == 1) ? (sorted[(int) (n / 2)] as Double)
                                         : (((sorted[(int) (n / 2) - 1] as Double) +
                                             (sorted[(int) (n / 2)] as Double)) / 2.0d))
        } else {
            corrections << 0.0d
        }
    }
    return corrections
}

/**
 * Records the running generation total against the slice of the solar day it was seen in. The
 * last reading in each slice is that slice's closing cumulative figure.
 */
private void recordSolarShapeSample(Double energy) {
    if (energy == null) return
    def e = getSolarElapsed(new Date())
    if (e == null || e < 0.0d || e > 1.0d) return

    int idx     = Math.min(shapeBuckets() - 1, (int) (e * shapeBuckets()))
    def buckets = state.solarShapeToday ?: [:]
    buckets["${idx}".toString()] = energy
    state.solarShapeToday = buckets
}

/**
 * Folds the finished day into the correction history. Called at midnight, before the inverter's
 * daily counter rolls over.
 *
 * What is banked is the DIFFERENCE between what the day actually did and what the computed
 * curve said it would do — a small signed number, near zero when the computed curve is right.
 * Weather moves it either way from day to day and largely cancels in the median, which is the
 * whole reason for storing a correction rather than the shape itself. Days that generated
 * almost nothing are discarded: their shape is noise.
 */
private void rollSolarShapeIntoHistory() {
    def buckets = state.solarShapeToday
    if (!buckets) return

    double total = 0.0d
    buckets.each { k, v -> if ((v as Double) > total) total = (v as Double) }
    if (total < 3.0d) {
        state.solarShapeToday = null
        logDebug "Solar shape: ${total.round(1)} kWh is too little to say anything about shape, day discarded"
        return
    }

    def    base  = getBaseProfile()
    def    shape = state.solarShape ?: [:]
    double last  = 0.0d
    (0..(shapeBuckets() - 1)).each { i ->
        def k = "${i}".toString()
        def v = buckets[k]
        if (v != null && (v as Double) > last) last = (v as Double)
        def samples = (shape[k] ?: []) as List
        samples << ((last / total) - (base[i] as Double))
        if (samples.size() > shapeHistory()) samples = samples[-shapeHistory()..-1]
        shape[k] = samples
    }
    state.solarShape      = shape
    state.solarShapeToday = null
    buildSolarShapeProfile()

    int banked = (shape["10"] ?: []).size()
    log.info "Solar shape: banked today's profile (${total.round(1)} kWh) — ${banked} day${banked == 1 ? '' : 's'} " +
             "of history${banked < shapeMinDays() ? ', correction applies from ' + shapeMinDays() : ' in use'}"
}

/**
 * Fraction of the day's total solar generation expected to be complete by the given time —
 * the computed curve, plus the banked correction once there is one.
 *
 * Anchored to real sunrise and sunset, so it self-adjusts for season and daylight saving.
 */
private Double getSolarDayFraction(Date at = new Date()) {
    def e = getSolarElapsed(at)
    if (e == null) return null
    if (e <= 0.0d) return 0.0d
    if (e >= 1.0d) return 1.0d

    double f = interpProfile(getBaseProfile(), e)

    def correction = getSolarShapeProfile()
    if (correction != null) f += interpProfile(correction, e, 0.0d)

    return Math.max(0.0d, Math.min(1.0d, f))
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
 * True until EVERY member of the Low/Mid/High band carries today's timestamp.
 *
 * The driver writes the three attributes as three separate events roughly a tenth of a second
 * apart, and reading them the moment the first arrives picks up yesterday's values for the
 * other two. Observed 10 Sep: the opening band was recorded as [5.46 / 18.43 / 24.01] when the
 * day's real band was [10.56 / 18.43 / 24.38] — the Low and High were the previous day's,
 * because only the Mid event had landed. A Low understated by 5 kWh moves the downgrade
 * threshold by nearly 2 kWh, in the direction that overstates solar and undercharges.
 *
 * Gating on all three means only the last event of a poll can open the day, so the band is
 * always internally consistent. It also makes a duplicate capture harmless: two handlers
 * racing past this gate necessarily read identical values.
 */
private boolean isBandStale() {
    if (!solarForecastDevice) return true
    def midnight = timeToday("00:00", location.timeZone)
    return ["24_Hour_Estimate", "24_Hour_Estimate_Low", "24_Hour_Estimate_High"].any { a ->
        def st = solarForecastDevice.currentState(a)
        return (st?.date == null) || st.date.before(midnight)
    }
}

/**
 * Records the Low/Mid/High band on every fresh forecast, keeping two copies of it:
 *
 *   - the OPENING band, from the first poll of the day (~08:58), frozen thereafter
 *   - the LATEST band, overwritten by every subsequent poll
 *
 * Both are needed because they answer different questions.
 *
 * The opening band is the trend reference. The solar-noon check asks "is the array delivering
 * what was predicted this morning?", and that comparison must use a forecast issued BEFORE the
 * period being measured. Solcast ingests satellite cloud imagery, so a midday forecast already
 * reflects the morning's actual conditions — comparing generation-so-far against it would be
 * circular and would conclude the day is tracking the middle estimate almost every time.
 *
 * The latest band is what the charge target projects forward from. Each poll is a fresh
 * prediction for the same day, better informed than the last, and the target only cares about
 * solar still to come. Freezing it at 08:58 threw away every same-day revision.
 *
 * Keeping both also makes the revision itself visible: latest minus opening says whether the
 * day is now expected to do better or worse than it looked at breakfast.
 */
private void captureForecastSnapshot(String triggerAttr = null) {
    if (!solarForecastDevice || isForecastStale()) return
    def today = new Date().format("yyyy-MM-dd", location.timeZone)

    def mid = solarForecastDevice.currentValue("24_Hour_Estimate")?.toDouble()
    if (mid == null) return
    def low  = solarForecastDevice.currentValue("24_Hour_Estimate_Low")?.toDouble()
    def high = solarForecastDevice.currentValue("24_Hour_Estimate_High")?.toDouble()

    // The latest band always tracks the most recent poll
    state.forecastLatestDate = today
    state.forecastLatestLow  = low
    state.forecastLatestMid  = mid
    state.forecastLatestHigh = high
    state.forecastLatestTime = new Date().format("HH:mm", location.timeZone)

    // The opening band is the trend reference for the whole day, so it must be internally
    // consistent — every attribute from the same poll. See isBandStale().
    if (state.forecastSnapshotDate != today && isBandStale()) {
        logDebug "Opening forecast: waiting for the rest of today's band before taking the trend reference"
        return
    }

    if (state.forecastSnapshotDate == today) {
        // Report the revision once per poll, from the mid attribute only. All three handlers
        // reach this branch within milliseconds of each other and logSteady's own throttle
        // cannot help: its state write has not landed before the next instance reads it, so
        // 11 Sep logged the same revision twice at 12:55 and again at 14:55.
        boolean mayReport = (triggerAttr == null || triggerAttr == "24_Hour_Estimate")
        def openMid = state.forecastSnapshotMid as Double
        if (mayReport && openMid != null) {
            double delta = mid - openMid
            if (Math.abs(delta) >= 0.05d) {
                logSteady("forecastRevision",
                          "Forecast revised ${delta >= 0 ? 'up' : 'down'} ${Math.abs(delta).round(2)} kWh " +
                          "since this morning: opening mid ${openMid} → ${mid} kWh", 300)
            }
        }
        return
    }

    state.forecastSnapshotDate = today
    state.forecastSnapshotLow  = low
    state.forecastSnapshotMid  = mid
    state.forecastSnapshotHigh = high

    log.info "Opening forecast for ${today}: low=${low}, mid=${mid}, high=${high} kWh " +
             "(trend reference for today — later polls update the charge target but not this)"
}

/**
 * A forecast total can never be less than what the array has already produced today.
 *
 * Solcast revises through the day, and those revisions are not always reconcilable with the
 * meter: on 8 Sep the midday estimate read 15.95 kWh for the day when 22.47 kWh was already
 * banked. Whatever such a number means, it is not a full-day total, and feeding it into
 * full × Δf as though it were produces a remaining-solar figure that is simply wrong.
 */
private Double clampForecastToActual(Double full) {
    if (full == null) return null
    def actual = solarGenerationDevice?.currentValue("energy")?.toDouble()
    return (actual == null) ? full : Math.max(full, actual)
}

/**
 * The requested estimate from the day's OPENING forecast — the first poll of the day.
 *
 * Solcast's '24_Hour_Estimate' family is a whole-of-day total for the current day, so the
 * value is used as published: no reconstruction, no adding back generation already banked.
 *
 * This is the trend reference only. It is deliberately frozen at the first poll so the
 * solar-noon comparison is against a prediction made before the morning it is judging.
 * Anything projecting forward should call getLatestForecast() instead.
 *
 * Returns null before today's first poll has landed.
 */
private Double getOpeningForecast(String estimate) {
    def v = (estimate == "low")  ? state.forecastSnapshotLow
          : (estimate == "high") ? state.forecastSnapshotHigh
          :                        state.forecastSnapshotMid
    return (v == null) ? null : (v as Double)
}

/**
 * The requested estimate from the most recent forecast poll — the app's current best view of
 * what today will produce in total.
 *
 * This is what every forward-looking calculation uses: the charge target, remaining solar, and
 * the performance ratio that scales it. Solcast revises through the day as satellite imagery
 * comes in, and a 13:58 prediction of this afternoon is better informed than an 08:58 one.
 *
 * Falls back to the opening band if a later poll has not landed yet, then to the device
 * attribute itself. That last fallback is refused while the forecast is stale — before today's
 * first poll the attribute still holds yesterday's numbers, and returning null there keeps the
 * charge target on its safe default instead of projecting today from yesterday's weather.
 */
private Double getLatestForecast(String estimate) {
    def today = new Date().format("yyyy-MM-dd", location.timeZone)
    if (state.forecastLatestDate == today) {
        def v = (estimate == "low")  ? state.forecastLatestLow
              : (estimate == "high") ? state.forecastLatestHigh
              :                        state.forecastLatestMid
        if (v != null) return v as Double
    }

    def opening = getOpeningForecast(estimate)
    if (opening != null && state.forecastSnapshotDate == today) return opening

    if (!solarForecastDevice || isForecastStale()) return null
    def attr = (estimate == "low")  ? "24_Hour_Estimate_Low"
             : (estimate == "high") ? "24_Hour_Estimate_High"
             :                        "24_Hour_Estimate"
    return solarForecastDevice.currentValue(attr)?.toDouble()
}

/**
 * How far the latest forecast has moved from this morning's, in kWh. Positive means today is
 * now expected to produce more than it looked like at the first poll. Null until both exist.
 */
private Double getForecastRevision() {
    def opening = getOpeningForecast("mid")
    def latest  = state.forecastLatestMid as Double
    if (opening == null || latest == null) return null
    return latest - opening
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

    // Deliberately the OPENING band, not the latest one. A midday forecast already reflects
    // the morning's actual weather, so comparing the morning's generation against it would be
    // circular — it would conclude "tracking Mid" nearly every day. See captureForecastSnapshot().
    def snapMid = getOpeningForecast("mid")
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
    def    snapHigh = getOpeningForecast("high") ?: snapMid

    // Sanity guard: the generation attribute is expected to reset to 0 each midnight. A
    // projection several times the optimistic estimate means it is behaving as a lifetime
    // counter (or is faulty) — fall back to the middle estimate rather than acting on it.
    if (implied > snapHigh * 3.0d) {
        log.warn "Forecast selection: implied full day ${implied.round(1)} kWh is implausible vs " +
                 "high estimate ${snapHigh} kWh – is the generation meter a lifetime counter? Using middle estimate"
        return "mid"
    }

    def    snapLow = getOpeningForecast("low") ?: snapMid
    double bias    = ((forecastUpgradeBias ?: 60) as Integer) / 100.0d

    String selection
    if      (implied >= snapMid + bias * (snapHigh - snapMid)) selection = "high"
    else if (implied >= snapLow + bias * (snapMid  - snapLow)) selection = "mid"
    else                                                       selection = "low"

    logSteady("selection", "Forecast selection: actual=${actual} kWh at ${Math.round(fraction * 100)}% of " +
              "solar day → implied ${implied.round(1)} kWh vs baseline " +
              "[${snapLow}/${snapMid}/${snapHigh}] → ${selection}", 600)
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
    // Before anything else: yesterday's generation counter has not rolled over yet, so this is
    // the last moment its hourly profile can be banked.
    rollSolarShapeIntoHistory()

    def prev = state.dailyMaxTemp
    state.dailyMaxTemp             = weatherStation?.currentValue("temperature")?.toDouble()
    state.chargeTargetReachedToday = false

    // Clear the forecast baseline and selection — the day starts on the middle estimate and
    // the new baseline is captured from the first forecast poll (~08:58). Until then
    // isForecastStale() suppresses charging decisions.
    // Report the finished day against its forecast, and bank today's hourly profile, both
    // before the counters that hold them are cleared
    logSolarDayFinal()
    rollLoadIntoHistory()

    // Today's hourly load buckets expire with the day that produced them
    state.loadHourSum   = null
    state.loadHourCount = null

    // Day-summary tracking
    state.dayChargeStartPct  = null
    state.dayChargeStartTime = null
    state.dayTargetMetTime   = null

    state.selectedEstimate     = null
    state.targetAnchorPct      = null
    state.targetAnchorMs       = null
    state.targetLastRise       = null
    state.forecastSnapshotDate = null
    state.forecastSnapshotLow  = null
    state.forecastSnapshotMid  = null
    state.forecastSnapshotHigh = null
    state.forecastLatestDate   = null
    state.forecastLatestLow    = null
    state.forecastLatestMid    = null
    state.forecastLatestHigh   = null
    state.forecastLatestTime   = null

    log.info "Midnight reset: dailyMaxTemp was ${prev}°C, seeded with ${state.dailyMaxTemp}°C; " +
             "forecast baseline and daily flags cleared"

    // Re-evaluate the alert against the new date. A deferred alert usually keeps the same text
    // until it expires, so no attribute event fires on its onset day — without this the warning
    // would never activate. Re-running here also lets a stale alert lapse: a weekday name now in
    // the past resolves to next week and defers again.
    if (state.pendingSevereOnset) {
        log.info "Midnight reset: severe weather alert was deferred to ${state.pendingSevereOnset} – re-evaluating for the new date"
    }
    severeWeatherHandler(null)
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

/**
 * Debug-logs at most once per interval for a given key. chargingCheck() runs on every battery
 * report — several times a minute — and its steady-state branches would otherwise write several
 * thousand identical lines a day. Anything representing a CHANGE still logs immediately; only
 * the "nothing happened" cases are throttled.
 */
private void logSteady(String key, String msg, int minSeconds = 300) {
    if (!logEnable) return
    def marks = state.logMarks ?: [:]
    long t    = now()
    def  last = marks[key]
    if (last != null && (t - (last as Long)) < (minSeconds * 1000L)) return
    marks[key]     = t
    state.logMarks = marks
    log.debug "[AdvPWManager] ${msg}"
}

private void logDebug(String msg) {
    if (logEnable) log.debug "[AdvPWManager] ${msg}"
}
