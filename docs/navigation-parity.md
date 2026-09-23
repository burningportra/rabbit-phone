# RabbitOS navigation reference and acceptance

Target: rabbitOS 2's device navigation, as documented in the current official
[interaction guide](https://www.rabbit.tech/support/article/use-rabbit-r1) and
[user guide](https://www.rabbit.tech/r1-user-guide). The visual/motion reference
is Rabbit's [official launch demo](https://www.youtube.com/watch?v=9F_BiQ77ey0),
also embedded on its [launch page](https://www.rabbit.tech/newsroom/rabbitos-2-launch).
The downloaded demo and extracted frames stay in ignored `evidence/navigation/`.

The installed Android apps, private recorder, phone/SMS intents and secure
keyguard remain the feature backends. No Rabbit account, cloud result or
recording transcript is fabricated by the navigation layer.

## Observable contract

- Home is a black clock/battery/mascot surface; the first wheel movement or
  upward swipe opens the card stack instead of selecting a list-row app.
- The stack uses overlapping colored cards, small corner glyphs and lowercase
  Power Grotesk labels. Wheel and vertical touch movement browse the same
  selection. Tap and the existing side-button selection open that card.
- The translator sequence follows the launch demo: its selected strip expands
  into a full green card face before the native translator setup page appears.
  That setup stores a local language pair; **Continue** opens Google Translate,
  rather than claiming a Rabbit translation service or account-backed result.
- The ordinary catalog remains stable across feature visits. An active card is
  owned by a real local task, currently the timer, rather than being a generic
  record of the most recently opened feature; the legacy `opened_cards` cache
  does not control its order. Swiping an active card left closes
  that task's navigation entry without deleting recordings, photos or app data.
- A feature's top-left Back control passes through its full card and returns Home. Bottom-edge upward
  navigation returns Home without activating another card.
- Top-edge downward navigation opens quick settings: brightness, media volume,
  then camera, text input, lock and settings controls in that order.
- The Home side-button press sleeps; double press opens Camera; the previously
  chosen hold-to-record behavior remains. Rabbit's [camera guide](https://www.rabbit.tech/support/article/r1-rabbit-eye-camera) confirms that a
  second double press turns the camera off, but does not document whether it
  returns Home or the originating stack, so this app's origin-aware return is a
  local policy. Wheel haptics and fail-open hardware ownership survive every
  transition.
- Launching an Android app preserves return navigation and selected-card
  identity. Unavailable cloud features must never produce fake successful output.

## Reference limits and completion gate

The official launch footage shows the first eight cards as Camera, Magic Gallery,
Timer, Translator, Recorder, r-cade, Reminder and Alarms. At 288 seconds the
same Camera → Magic Gallery → Timer → Translator order remains after opening
Translator; it does not show generic recent-feature promotion. The later hand
includes Settings, Creations and Intern. Local Music and Apps follow those
observed catalog cards, so they are local additions rather than evidence of a
Rabbit account's complete catalog.

Observed Home-to-stack reveal settles between 231.4 and 231.7 seconds in the
30 fps official demo. Quick Settings is unobstructed at 178.6 seconds. Exact
interpolation, system-edge ownership and all return paths still require direct
implementation comparison; source compilation or a colorful mockup alone do
not establish one-to-one navigation parity.

The demo gives a tighter translator motion reference. At 238.9 seconds the
selected Translator strip has grown into its full green face; the native setup
page is established by 239.6 seconds. A Back action starts at 241.6 seconds,
recreates the full card face through roughly 242.6 seconds, and reaches Home by
242.8 seconds. The app uses a 720 ms open transition and a 1200 ms feature-exit
transition to preserve that ordered sequence. Those durations are implementation
choices informed by the footage, not yet a device-timing receipt.

## Feature transitions, Translator and recorder navigation in 0.9.0

Opening a card expands its face before revealing the feature in the same window.
Back recreates the full face and then reaches Home. Version 0.9 used a centered
300×390 card at `(88, 118)`, which left too much unused space on the actual
480×640 display. Version 0.11 supersedes that inset interpretation: the settled
face is 432×500 at `(24, 96)`, with a rotated bottom label, and the next-card cue
occupies `y=616..632`. Translator's opening face has no cue. The larger geometry
implements the owner's screen-fill correction rather than claiming a newly
measured one-to-one reference match.

Translator now has a native language-pair setup with a wheel-operated chooser,
persisted preferences, and a Back path from chooser to setup before returning
Home. The setup spacing follows the visible demo; the chooser layout remains a
local design because the footage does not show it. Continue opens Google
Translate with the selected language codes, without sending recorded audio or
claiming Rabbit's live translation backend.

The recorder library is native and local: a red header sits above borderless
**Voice note** rows that show each note's real saved date and chevron. `+` opens
the ready recorder without microphone use. Selecting a row opens quiet detail
with the note's actual duration and Play/Stop; Back returns to the library and
keeps the existing media-volume setting. The existing recording and saved reel
states are unchanged.

The v0.9 device pass verified 17 Recorder checks, 20 active-card checks,
22 general navigation checks and 15 transition/interruption checks. It covered
silent note-detail opening, explicit muted fixture playback, volume persistence,
task-owned card order, timer recovery and modal accessibility restoration. The
new Gallery has its own verification below.

The recording header's Back action uses the existing cancellation path, which
clears an unfinished take before dismissing the overlay. The recorder
navigation check uses generated silence rather than microphone audio.

Earlier `scripts/verify_card_flows.py --device-test --interruptions` receipts
also covered:
Translator picker/Back and unchanged hardware lease, Timer setup/Back, Recorder
library/Back, cancellation by Back/wheel/focus loss before reveal, wheel input
during exit, and disabled animations. The test restores Android's animation
setting and verifies unchanged notes, timer state and media volume, with the
microphone idle. These are interaction receipts, not a frame-perfect timing
claim. Reference-aligned spacing is also inspected through device screenshots.

The general navigation verifier passed all 21 checks and the camera verifier
passed all 15, including Camera Back returning Home with a fresh input lease.
Those earlier receipts are separate from the v0.9 and Gallery checks above. The README screenshot shows the app's native fallback artwork; the
optional official PNG and all raw device evidence remain outside Git.

## Historical connected-R1 receipts (0.6.0–0.8.0)

The 0.6.0 navigation build implements the Home/stack reveal, wheel and touch
selection, opened-card promotion, left dismissal, top quick settings, and bottom
Home gesture. `scripts/verify_navigation.py --device-test` exercises these through
visible UI and the real wheel/PMIC driver, with note hashes, microphone state and
media volume checked afterward. Native sanitizer checks, 15 button-gesture cases,
the pure card-order model and 68 Python tests pass.

`verify_quick_settings.py --device-test` verifies both sliders through touch,
reopening and app restart, then restores their original values. The recorder's
zero-volume restart check passes too, without playback or recording. Device
screenshots, APK receipts and reference video remain in ignored evidence folders.

Camera now lives in Home's window with a separate, foreground-only controller.
Home stops its hardware client before Camera resumes and reacquires it after
Camera releases. The legacy Camera Activity redirects into that screen. Its
Back control uses the observed feature-exit sequence to Home; the double-press
destination remains the local origin-aware policy described above. Focus loss
and pause still release the input grab and camera.

Timer and Translator both implement `HardwarePage`, so they share the existing
wheel and side-button routing while visible. This keeps the underlying hardware
lease and its fail-open lifecycle in Home rather than creating a second input
owner for feature pages.

## Native timer card

The official demo at 250–254 seconds shows a running five-minute timer as a
blue card with a live countdown, duration caption and sparse cancel/pause
controls. Its settled face now uses the shared normalized `(24, 96, 432, 500)`
active-card envelope, with the cue beneath it; the 250-second pop-in frame is
not the final placement. Timer digits and the two controls are rendered from real,
persisted timer state. Preview updates preserve scrolling, touch tracking and
accessibility focus; actions keep distinct identities across pause/resume/expiry
so a stale Pause action cannot become Restart.

The [official timer guide](https://www.rabbit.tech/support/article/rabbit-smart-timers)
confirms preset choices, custom duration through a plus control, and one active
timer with replacement. The footage and guide do not show the preset menu or
custom editor. The six preset values and hours/minutes/seconds editor remain
local design choices, not verified visual replicas. Wheel selection and the side
button operate the editor without a second window.

The running countdown uses elapsed time; a wall-clock anchor and boot counter
restore it across reboot. An atomic private state file and generation-bound
alarms keep replaced or canceled timers from firing. The non-exported receiver
posts the existing Android alarm sound/channel notification once. There is no
background input grab, foreground service, permanent wakelock or volume override.
Starting/resuming requires notification and exact-alarm access; a failed start
leaves the prior timer intact. Lost scheduling access pauses the recovered timer
until the user restores access and resumes it.

`scripts/verify_timer.py --device-test --reboot` verifies a live five-minute card,
pause/resume, persistence, custom duration, background completion, notification
cleanup, permission denial, and recovery after an actual reboot. The checks use
visible controls and the R1's raw input driver, never a timer-start test intent.
The pure model has 15 test groups for elapsed sleep, wall-clock edits, reboot,
state transitions, stale expiry and notification deduplication. The separate
`--access-recovery` run checks the selected preset through wheel/PTT, permission
revocation during a running timer, and swipe cancellation. Local receipts stay
under ignored `evidence/timer/`.

## Native Gallery in v0.10

The official demo shows Magic Gallery opening from the cyan card at 264–265
seconds into a black overview by 266 seconds: cyan Back/status/header treatment,
a **favorites** row, and three visible thumbnails. The local NativeGallery
mirrors that overview. Its viewer returns to the originating collection; the
Favorites row returns to the gallery, and the root Back path follows the feature
return to the card/Home sequence. Wheel moves grid selection and the viewer's
previous/next image; PTT opens the selected image in the grid and toggles
Favorites in the viewer. Those hardware mappings are explicit local choices.

NativeGallery reads only MediaStore originals created by this package under
`Pictures/Rabbit Phone`. It requests no storage permission, Internet permission,
or cloud AI. Favorites use the real persisted `IS_FAVORITE` metadata. Delete
stays in-window, defaults to Cancel, and changes only after explicit
confirmation; metadata, generation and version guards prevent stale callbacks
from deleting or altering a newer item. Decode, caching, observer updates and
focus lifecycle work are bounded and asynchronous.

Rabbit's [Magic Gallery guide](https://www.rabbit.tech/support/article/rabbit-magic-gallery)
documents thumbnail selection, scrolling, favorites, magic/original toggling and
deletion, but the official demo does not show the viewer layout. This app does
not offer stock magic/original toggling or cloud sync, and uses an honest local
viewer arrangement for the unshown detail UI.

`scripts/build_gallery_harness.py` builds a separate same-signer instrumentation
APK. `scripts/verify_gallery.py --device-test` uses it to create three
deterministic PNG fixtures, hash production photos before the run, exercise only
known fixture favorite/delete operations, verify checksum/generation cleanup,
and uninstall the test APK. Production exposes no debug fixture entry point.
The Gallery pass verifies 26 checks, including rendered thumbnail pixels,
wheel/PTT browsing, favorite persistence, Cancel-default deletion, and cancellation
on focus loss or Quick Settings. The two existing photos are hash-identical after
cleanup; all ten notes, media volume and timer state are preserved. A separate
Home→Gallery→Home check confirms one continuous hardware lease. The README image
shows only a generated fixture; personal-photo overview captures stay ignored.

The device pass caught two framework-state regressions: replacing GridView's
layout parameters detached the first tile from input/accessibility, and restoring
prompt children to AUTO hid the static caption. The implementation preserves the
framework-owned layout object and restores each view's original accessibility
flag; the same device checks now cover both paths.

## Remaining differences from Rabbit firmware

This is a working reference-based navigation implementation, not a verified
one-to-one firmware replica. The timer now shows real countdown state and inline
controls; ordinary feature cards are catalog entries rather than live previews.
Magic Gallery is now a native local page; other app-specific feature surfaces
remain. The default mascot and glyphs are original static renderings, not an
idle 3D Rabbit animation. The owner can optionally install the pinned official
public mascot PNG privately with `scripts/install_mascot.py`; it is not bundled
or published with the app. Android feature screens retain their own contents and
controls. The keyboard shortcut searches installed apps, and Rabbit cloud cards
link to their public service instead of fabricating account-backed results.
Hold-to-record remains the user's chosen local replacement for Rabbit's cloud
voice gesture.

During the initial camera privacy-indicator animation, Android may own the very
top touch strip even in Home's window. An immediate header shortcut and a swipe
beginning below that strip open Quick Settings; the very top edge also works once
the transient overlay settles. Device tests distinguish those cases. Android's
[privacy-indicator guidance](https://developer.android.com/training/permissions/explaining-access)
describes this temporary overlap in immersive apps. No privacy indicator,
keyguard or system input protection is disabled.

The one-to-one goal remains open for the remaining native feature pages, exact
setup screens, Home animation and unverified visual differences. Passing the
navigation checks does not close those gaps.
