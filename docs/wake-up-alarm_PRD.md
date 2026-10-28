Wake up alarm prd · MDCopyDailySync - Product Requirements Document
(formerly Personal Morning Alarm, then MorningSync)
Version: 2.0
Last Updated: July 2026
Overview
Problem Statement
The user's day is scattered across separate tools — a to-do list, a schedule, and more — with no single place to see them, and mornings compound the problem: traditional alarms are too easy to dismiss, leading to snoozing and a slow start. This app is a personal-organizer hub: a tile home screen that aggregates those other tools in one glanceable place, built around a two-stage wake-up alarm that physically gets the user out of bed and into the day — without disturbing household members.
Goals

Give the user one landing screen that aggregates their day's tools (schedule, rolling to-do, and more to come)
Reliably wake the user and get them physically moving through their space
Create progressive accountability through multi-checkpoint dismissal
Keep Stage 1 quiet enough to avoid disturbing a partner
Build an extensible tile system that new aggregated tools can slot into

Target Users
Personal use. A single user who wants their schedule and to-do list in one place and struggles with snoozing, needing structured accountability to get moving in the morning.
Features
Home: Tile Aggregator (Landing Screen)

The app opens on a grid of tiles, one per tool
Live tiles: Daily Schedule and Rolling To-Do, each opening its aggregated view
Coming-soon tiles: KitchenSync and Kanban render greyed and inert (no navigation yet)
The wake-up alarm lives behind the Alarm tab, one slot along in the bottom nav (Home / Alarm / Settings)
New tools are added as tiles rather than bolted onto the alarm
Stage 1: Gentle Wake

Soft alarm at scheduled time, moderate volume
Shake phone vigorously for 15 seconds to dismiss
Visual progress bar and haptic feedback during shake
Prevents back-button exit and shows over lock screen

Stage 2: Guided Wake-Up Sequence

Configurable NFC checkpoint system (user sets number of tags)
Must tap NFC tags placed around the home to fully dismiss
Tap order randomised each morning to prevent autopilot
Content screens displayed between taps (toggleable per screen):

Motivational quote (bundled pool)
Stretching prompt (5 or 10 min timer)
Additional content slots for future use


Countdown timer runs during sequence
If timer expires before all taps complete, nuclear alarm activates

Nuclear Alarm (Stage 2 Failure)

Maximum volume, strong vibration, optional flashlight strobe
Requires ALL of: extended shake (30s), math problem, typed confirmation phrase
No NFC step: away from home (or with NFC off) there may be no tag to tap, and the alarm must stay stoppable
Logged as failed wake-up, streak resets

Content Toggle System

Settings screen to enable/disable each content type
Configured the evening before alarm is due
V1: bundled quotes and stretching timer
Future: external data via API integration

Data & Streaks

Daily success/failure logging with timestamps
Current streak tracked and shown on the Alarm screen ("Current streak: N days", "This week: N/N days")
No dedicated statistics screen — the streak lives inline on the Alarm screen; the alarm_events store is retained (the alarm reads it for the streak line)

Settings

Alarm time, enable/disable, Stage 2 timer duration (5-15 min)
NFC tag registration and management (configurable count)
Sound selection for Stage 1 and nuclear alarm
Content screen toggles
Dark/light theme

Scope
In Scope

Two-stage alarm with NFC checkpoints
Bundled static content (quotes, stretching prompts)
Local data storage and streak tracking
Configurable NFC tag count and content toggles
Battery optimisation prompts

Out of Scope

Cloud sync or multi-device support
Social features or leaderboards
Sleep tracking
iOS version
Widget or Wear OS support

Future Considerations

New aggregator tiles: KitchenSync and Kanban (currently coming-soon placeholders), plus further tools as tiles
Deeper external integrations feeding the tiles (Daily Schedule and Rolling To-Do already pull aggregated content)
Additional challenge types (photo verification, step counter, voice recording)
Challenge pool system with random selection
Gamification (XP, achievements, levels)
Smart difficulty adjustment based on patterns

Technical
Stack

Kotlin: Primary language
MVVM: Architecture pattern
Room: Local SQLite database
Android NFC API: Checkpoint tag reading
AlarmManager: Alarm scheduling
Foreground Service: Alarm and countdown reliability
Material Design: UI components

Integrations

None for V1 (fully self-contained)
Future: External REST API for personalised content

Constraints

Min API level 26 (Android 8.0), primary test device API 33 (Galaxy A32 5G on Android 13)
Must work offline (no network dependency at alarm time)
Battery usage < 5% overnight
App size < 20MB
Must survive device restart (reschedule alarms on boot)
Public repository — no personal data, tokens, or private network details in code

Project Structure
PersonalMorningAlarm/
├── docs/
│   ├── wake-up-alarm_PRD.md
│   └── progress.txt
├── CLAUDE.md
├── app/
│   └── src/
│       └── main/
│           ├── java/
│           │   └── com/personalmorningalarm/
│           │       ├── data/          # Room entities, DAOs, repository
│           │       ├── ui/            # Activities, fragments, viewmodels
│           │       ├── service/       # Alarm, countdown, foreground services
│           │       ├── receiver/      # Boot, alarm broadcast receivers
│           │       ├── challenge/     # Shake, NFC, math, typing challenges
│           │       └── util/          # Helpers, constants
│           └── res/                   # Layouts, strings, themes
├── build.gradle
└── .gitignore
Success Criteria

 App opens on the tile home screen; live tiles open their aggregated views, coming-soon tiles are inert
 Alarm triggers reliably at scheduled time, including after device restart
 Stage 1 shake detection works accurately with clear progress feedback
 NFC tag registration and reading works consistently
 Tap order randomises each morning
 Content screens display between taps with correct toggle respect
 Nuclear alarm activates on Stage 2 timeout and requires all challenges
 Streak tracking persists correctly across sessions
 App does not wake household members during Stage 1
 Runs reliably on Samsung Galaxy A32 5G
 Repository contains no personal data or credentials