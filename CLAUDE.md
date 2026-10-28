Claude · MDCopyProject: DailySync
An Android personal-organizer app that opens on a tile home screen aggregating other
tools (Daily Schedule, Rolling To-Do, with KitchenSync and Kanban to come), and also
features a robust two-stage wake-up alarm with NFC checkpoints and toggleable content
screens. The alarm — once the whole app — is now one area behind the Alarm tab; the
tile grid is the landing screen.
Built with Kotlin, MVVM, Room, Android NFC API.
Structure

app/src/main/java/com/personalmorningalarm/data/ - Room entities, DAOs, repository
app/src/main/java/com/personalmorningalarm/ui/ - Activities, fragments, viewmodels
app/src/main/java/com/personalmorningalarm/service/ - Alarm, countdown, foreground services
app/src/main/java/com/personalmorningalarm/receiver/ - Boot and alarm broadcast receivers
app/src/main/java/com/personalmorningalarm/challenge/ - Shake, NFC, math, typing challenges
app/src/main/java/com/personalmorningalarm/util/ - Helpers, constants
app/src/main/res/ - Layouts, strings, drawables, themes
docs/ - PRD and progress log

Naming: the project has been Personal Morning Alarm, then MorningSync, and is now
DailySync (repo folder C:\Dev\DailySync, remote Kezman554/DailySync, app_name
"DailySync"). The Kotlin package and applicationId remain com.personalmorningalarm
and are deliberately NOT renamed: applicationId is the app's identity to Android, so
changing it makes the A32 treat the build as a different app — a fresh install with
an empty data directory, losing registered NFC tags and alarm history exactly as the
2026-07-16 incident below did. Treat com.personalmorningalarm as a legacy internal
identifier; only user-facing names say DailySync.

Commands

./gradlew assembleDebug - Build debug APK
./gradlew installDebug - Build and install on connected device
./gradlew test - Run unit tests
./gradlew connectedAndroidTest - Run instrumented tests. NEVER against the A32 — see below

Never run connectedAndroidTest against the A32. AGP uninstalls the app when the run
ends, which wipes its data directory — on 2026-07-16 this destroyed registered NFC
tags and a month of alarm history, and the reinstall then silently restored a stale
cloud backup, disguising the wipe as partial data loss. gradle.properties now sets
leaveApksInstalledAfterRun=true, but that only stops the uninstall: an instrumented
test still runs inside the app's own data directory and can overwrite its database
and prefs. Verify on-device with installDebug + adb (update installs preserve data);
keep logic in the Robolectric unit tests; use an emulator if instrumentation is
genuinely needed.

Build prerequisite: gradle needs JAVA_HOME pointing at a JDK 17+ (this dev box has
no java on PATH by default). Android Studio's bundled JBR works:
JAVA_HOME=C:\Program Files\Android\Android Studio\jbr (OpenJDK 21). A persistent
user-level JAVA_HOME is set on this machine; if a fresh shell can't find java, set
it for the session before running gradle. Unit tests use Robolectric (pinned to
sdk=33 in app/src/test/resources/robolectric.properties) so Room/framework-backed
tests run on the local JVM.

Git

Do not push to GitHub without explicit permission
Commit after completing each session
Update docs/progress.txt briefly if significant work was done

Conventions

Min API level 26, target API 34
Primary test device: Samsung Galaxy A32 5G, model SM-A326B (Android 13 / API 33)
MVVM architecture with Repository pattern throughout
Public repo — never commit credentials, tokens, personal data, public IPs or hostnames, port-forwards, or VPN endpoints
RFC1918 LAN defaults (192.168.x.x, 10.x.x.x, 172.16-31.x.x) are fine to commit — non-routable, unreachable from outside, and reveal nothing about the network. e.g. the Alfred host default 192.168.1.100:8200
Foreground services for alarm and countdown reliability
Nothing polls, with one deliberate, narrow exception (keep it): FreshnessWatch retries Alfred every 60s only while a screen is visible and showing a saved copy, and on reachability returning with a 10s minimum gap — it is what clears stale-data warnings without a restart
Extensible challenge system — new challenges implement a common interface

## Design
Theme roles live in res/values/colors.xml + values-night/colors.xml, wired up in
themes.xml (Theme.PersonalMorningAlarm). Reference: the design canvas, 2026-10-15.

Colours only via theme attributes (?attr/colorPrimary, ?attr/colorPositive,
?attr/colorSurface2, ...): never a hex or @android:color in a layout, style or
Kotlin; resolve in code with MaterialColors.getColor. Colour resources are named
by role, never by colour
Accent: colorPrimary for text, icons, progress and rings; colorAccentFill for
buttons and switches. Labels on the cyan fill are black, in both modes
Red (colorError) only for errors and the nuclear alarm
Green (colorPositive) means done or armed
Light mode applies to every screen, the alarm screens included: no screen
paints its own background over the glow ground (drawable/bg_ground)
Space Grotesk only (bundled in res/font, never fetched); weights via textStyle
or textFontWeight; tabular figures are on app-wide

Reference

Requirements: docs/wake-up-alarm_PRD.md
Progress log: docs/progress.txt
Task prompts: Kanban app