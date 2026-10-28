# DailySync

An Android personal organiser built around a wake-up alarm that is hard to sleep
through. The app opens on a home screen of tiles: today's schedule, a rolling
to-do list, an inbox and shopping lists. The alarm sits one tab along. It wakes
you in two stages and makes you walk to NFC tags placed around your home before
it lets you go.

This is a personal project, built and tested on one phone, a Samsung Galaxy A32 5G
running Android 13. The source is public for anyone curious about the alarm design
or wanting to adapt it.

## Features

### The wake-up alarm

- **Stage 1: gentle wake.** At the alarm time a soft alarm plays at moderate volume.
  To silence it you shake the phone for 15 seconds in total (time only counts while
  you're actually shaking). The alarm shows over the lock screen and the back button
  can't dismiss it.
- **Stage 2: NFC checkpoints.** A countdown starts (5 to 15 minutes, you choose).
  Before it runs out you must tap a sequence of NFC tags you've stuck around the
  house. The order is shuffled every morning so you can't do it on autopilot. A
  wrong tag doesn't reset your progress, it just costs you time.
- **Content screens between taps.** After a checkpoint the app can show a quote, a
  guided stretch routine, today's schedule or your to-do list. Each one has a short
  timer (15 seconds, or the length of the stretch session). The countdown pauses
  while that timer runs and resumes as soon as you can tap Continue, so you can't
  sit on a content screen and doze off. Each screen can be turned off.
- **Nuclear alarm.** If the countdown runs out, the nuclear alarm fires at maximum
  volume with strong vibration and an optional flashlight strobe. Stopping it takes
  all three of: a 30-second shake, a maths problem and a typed confirmation phrase.
  It's logged as a failed morning and resets your streak.
- **Built to survive.** The alarm is rescheduled after a reboot. If Android kills the
  app mid-alarm, the countdown carries on to its original deadline and the alarm
  screens rebuild themselves without losing progress.
- **Streaks.** Each morning is logged as a success or failure, and the Alarm tab
  shows your current streak.
- **Optional alarm PIN.** A 4-digit PIN (stored only as a salted hash) guards the
  alarm time, the on/off switch and the Stage 2 time limit. That way you can't
  quietly weaken the alarm at 6am.

### The home screen

- **Daily Schedule.** Today's plan grouped into parts of the day, with the current
  one highlighted. Tap it for a week view.
- **Rolling To-Do.** An ongoing to-do list. Items can be ticked off, dropped or added.
- **Inbox.** A quick-capture list for thoughts you want to deal with later.
- **Shopping lists.** Several lists, each with items that can be ticked or added.
- **Home Hub sign-in.** Opens or closes a session on a home dashboard using a PIN.
- **Coming soon.** A Kanban tile is shown greyed out.

The home screen tiles read from **Alfred**, a self-hosted personal API on the home
network, which isn't part of this repository (see [Backend](#backend-optional)).
Without it the tiles say the data is unavailable. The alarm itself never needs the
network.

### Everything else

- Light and dark themes, with every screen following the chosen theme.
- Eight bundled alarm sounds (gentle ones for Stage 1, urgent ones for nuclear).
- Your own quote pool and stretch routines, edited in the app.
- Works offline. Anything written while Alfred is unreachable is queued and sent
  once it's back. Screens that show Alfred data say when it's a saved copy and how
  old that copy is.

## Screenshots

_Screenshots to come._

<!--
| Home | Stage 1 | Stage 2 checkpoint | Nuclear |
|------|---------|--------------------|---------|
| ![](docs/screenshots/home.png) | ![](docs/screenshots/stage1.png) | ![](docs/screenshots/stage2.png) | ![](docs/screenshots/nuclear.png) |
-->

## Tech stack

- **Kotlin**, single-module Android app
- **MVVM** with a repository layer; ViewModels expose `StateFlow`
- **Room** for local storage (alarm config, NFC tags, alarm history, quotes,
  stretch routines and the offline write queues), with versioned schema
  migrations exported to `app/schemas/`
- **Android NFC API** in reader mode for the checkpoints
- **Foreground services** for the alarm sound, the Stage 2 countdown and the
  nuclear alarm; **AlarmManager** exact alarms for scheduling
- **Retrofit + Gson** for the optional local-network APIs
- **Jetpack Navigation**, **ViewBinding**, **Material Components**
- **Robolectric + JUnit** unit tests, which run on the JVM with no device needed
- Min SDK 26 (Android 8.0), target SDK 34

## Architecture

```
app/src/main/java/com/personalmorningalarm/
├── data/        Room entities, DAOs and AlarmRepository
│   ├── model/   Plain data models and the pure logic that shapes them
│   └── remote/  Alfred and dashboard clients, the response cache, offline write queues
├── challenge/   The dismissal challenges: shake, NFC sequence, maths, typing,
│                plus the session objects that let an alarm screen be rebuilt
├── service/     AlarmService (Stage 1 sound), CountdownService (Stage 2 timer),
│                NuclearAlarmService
├── receiver/    AlarmReceiver (alarm fires) and BootReceiver (re-arm after reboot)
├── ui/          Activities, fragments, ViewModels and adapters
└── util/        Alarm scheduling, the PIN, theming and other helpers
```

**How a morning flows.** `AlarmScheduler` sets an exact alarm. When it fires,
`AlarmReceiver` starts `AlarmService`, which plays the Stage 1 sound and opens
`AlarmDismissalActivity` over the lock screen. When the shake is done, the activity
stops the sound and starts `CountdownService`. `NfcCheckpointManager` then decides
which tag comes next and checks each tap. If all the tags are tapped in time, the
morning is logged as a success. If the countdown expires, `CountdownService` starts
`NuclearAlarmService` and the `NuclearDismissalActivity` gauntlet takes over.

**State lives outside the screens.** Android may destroy an alarm screen at any
time, so it isn't trusted to hold anything important. The countdown lives in its
service and saves its deadline so a restarted service can carry on. The progress of
each stage lives in process-wide session objects (`Stage2Session`, `NuclearSession`),
and a rebuilt screen picks up from there.

**Challenges are pluggable.** Each challenge implements `ChallengeInterface`
(`start`, `stop`, `onProgress`, `onComplete`). The timing logic is kept apart from
the Android sensor code (for example, `ShakeAccumulator` holds the shake timing and
`ShakeChallenge` holds the sensor wiring), so it can be unit-tested with a fake
clock.

## Setup

### Requirements

- Android Studio (recent), or a JDK 17+ for command-line builds. Android Studio's
  bundled JBR works as `JAVA_HOME`.
- An Android device on Android 8.0 or later. For the full Stage 2 you need NFC and
  a few NFC tags (any cheap NTAG stickers work, since only the tag's ID is read).
  Without NFC, Stage 2 falls back to a confirm button.

### Build and install

```sh
git clone https://github.com/Kezman554/DailySync.git
cd DailySync
./gradlew assembleDebug     # build the debug APK
./gradlew installDebug      # build and install on a connected device
./gradlew test              # run the unit tests
```

On Windows use `gradlew.bat`. Android Studio creates `local.properties` (the SDK
path) on first open.

> **Testing on a phone you use:** don't run `connectedAndroidTest` against a
> device whose alarm data you care about. Instrumented tests run inside the app's
> own data directory and can overwrite its database. Use an emulator for those.
> `installDebug` is an update install and keeps your data.

### First run

1. Grant the permissions the app asks for: notifications, "Alarms & reminders" for
   exact alarms, and an exemption from battery optimisation. Without these Android
   may delay or drop the alarm.
2. On the **Alarm** tab, set the alarm time and switch it on.
3. Under **NFC tags**, register your tags. Tap each one to the phone and give it a
   name ("Kitchen", "Front door"). Then choose how many checkpoints a morning needs.
4. Choose your content screens, wake sound and nuclear options. Optionally set an
   alarm PIN.

Debug builds also have a hidden **Dev tools** row on the Alarm tab: long-press the
Alarm pill in the header to show it. It can fire a test alarm in a minute or start a
15-second countdown. Release builds don't include it.

### Backend (optional)

The home screen tiles talk to two services on the home network. Neither is part of
this repository, and the app works without both:

- **Alfred** (default `192.168.1.100:8200`, changeable in Settings) is a small HTTP
  API serving `daily-schedule`, `daily-schedule/week`, `calendar/events`,
  `chalkboard` (the rolling to-do), `shopping` and `inbox`. The request and response
  shapes are the DTOs in `data/model/`.
- **Home Hub dashboard** (default `192.168.1.100:8500`) serves `api/presence`, used
  only by the Home Hub sign-in tile.

Both are plain HTTP on the LAN by design. Don't point them at anything reachable
from the internet.

## Licence

DailySync is released under the [MIT Licence](LICENSE), © 2026 Nick Young.

Bundled third-party assets keep their own licences:

- [Space Grotesk](https://github.com/floriankarsten/space-grotesk), the app's only
  typeface, is bundled under the SIL Open Font License
  (`docs/licenses/SpaceGrotesk-OFL.txt`).
- Icons are adapted from [Lucide](https://lucide.dev) under the ISC licence
  (`docs/licenses/Lucide-LICENSE.txt`).
