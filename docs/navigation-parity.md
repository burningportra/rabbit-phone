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
- Opened cards sit above the remaining hand. Swiping an opened card left closes
  its navigation entry; it must not delete recordings, photos or app data.
- A card's top-left Back control returns to the stack. Bottom-edge upward
  navigation returns Home without activating another card.
- Top-edge downward navigation opens quick settings: brightness, media volume,
  then camera, text input, lock and settings controls in that order.
- The Home side-button press sleeps; double press opens Camera; the previously
  chosen hold-to-record behavior remains. Wheel haptics and fail-open hardware
  ownership survive every transition.
- Launching an Android app preserves return navigation and selected-card
  identity. Unavailable cloud features must never produce fake successful output.

## Reference limits and completion gate

The official launch footage shows the first eight cards as Camera, Magic Gallery,
Timer, Translator, Recorder, r-cade, Reminders and Alarm. Later releases allow
customized ordering and a separate creations hand, so this launch-state sequence
is a visual fixture, not proof of every account's current catalog.

Observed Home-to-stack reveal settles between 231.4 and 231.7 seconds in the
30 fps official demo. Quick Settings is unobstructed at 178.6 seconds. Exact
interpolation, system-edge ownership and all return paths still require direct
implementation comparison; source compilation or a colorful mockup alone do
not establish one-to-one navigation parity.

## Verified on the connected R1

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
Camera releases. The legacy Camera Activity redirects into that screen. Camera's
Back control returns to the stack; double press retains the original Home/stack
return behavior. Focus loss and pause still release the input grab and camera.

## Remaining differences from Rabbit firmware

This is a working reference-based navigation implementation, not a verified
one-to-one firmware replica. Expanded cards currently show category art rather
than live app content; the mascot and glyphs are original vector approximations.
Android feature screens retain their own contents and controls. The keyboard
shortcut searches installed apps, and Rabbit cloud cards link to their public
service instead of fabricating account-backed results. Hold-to-record remains
the user's chosen local replacement for Rabbit's cloud voice gesture.

During the initial camera privacy-indicator animation, Android may own the very
top touch strip even in Home's window. An immediate header shortcut and a swipe
beginning below that strip open Quick Settings; the very top edge also works once
the transient overlay settles. Device tests distinguish those cases. Android's
[privacy-indicator guidance](https://developer.android.com/training/permissions/explaining-access)
describes this temporary overlap in immersive apps. No privacy indicator,
keyguard or system input protection is disabled.

The one-to-one goal remains open for live card previews and the remaining visual
and feature-screen differences. Passing the navigation checks does not close
those gaps.
