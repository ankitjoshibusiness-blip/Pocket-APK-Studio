# Live UI Preview — Phase 1–5 status

Last reviewed: 2026-11-25  
Branch: `main`

This tracker deliberately separates source-derived approximations from a real compiled Android runtime. A successful Pocket APK Studio CI build proves that the Studio app compiles and packages; it does **not** prove that an arbitrary imported project builds or that its screen was validated on a phone.

## Status summary

| Phase | Status | What is implemented | What is still approximate or missing |
|---|---|---|---|
| 1. Basic Compose components | Partial | Kotlin source scanning recognizes common Compose calls including Text, buttons, text fields, switches, checkboxes, radio buttons, progress indicators, images, icons, chips, dialogs, and common containers. It builds a visible native-View representation. | This is source inspection, not Compose execution. Unsupported or custom composables may be omitted; modifiers, measurement, state and semantics are not identical to Compose. |
| 2. Advanced layouts, modifiers, images, icons, fonts and themes | Partial | Nested Column/Row/Box-like structures, common Card/Surface/AppBar/navigation elements, spacing, padding, width/height/fill/weight, alignment, common typography, local font files, raster drawable resources, common icon glyphs, color constants and light/dark color-scheme extraction are handled. The preview offers System/Dark/Light theme selection. | Lazy and flow layouts are simplified to native containers; complex constraints, custom layouts, many modifier chains, vector/remote image resources, dynamic theme expressions and advanced typography are not fully reproduced. The renderer is not a complete Compose layout engine. |
| 3. Interactive controls | Partial | Generated preview controls respond locally: buttons/chips provide preview feedback, text fields accept input, switches and checkboxes toggle, single-value sliders move, the two handles of RangeSlider can be dragged independently, date/time pickers open, dropdowns show extracted options, dialogs open, navigation/tab items can be selected, and RadioButtons within the same inferred nearest shared container enforce one selected item at a time. RangeSlider reads literal `value = start..end` and `valueRange = min..max` arguments when present; its local state clamps both thumbs and prevents the interval from crossing. | These interactions do not invoke the imported app's callbacks, business logic, ViewModel, navigation, network or `remember` state. Radio-group boundaries are inferred from the generated view tree and may not match complex Compose state groups; when RangeSlider values are expressions rather than literals, a default visual interval is used. Custom range control semantics and full accessibility behavior still differ from Compose. |
| 4. Device sizes, orientation and responsive preview | Partial | Compact, Phone and Tablet presets; Rotate; custom width/height from 240–1200 dp by 320–2400 dp; horizontal/vertical scrolling; viewport and source selection are retained across recreation. Native generated views are remeasured at the selected viewport size. | This is a configurable viewport, not an emulator. It does not fully emulate device density, font scale, system bars, cutouts, fold posture, per-device resources or Android configuration changes. Since the UI is approximated with Views, reflow can differ from real Compose. |
| 5. Real Jetpack Compose runtime rendering | Partial; real-device handoff exists | **Build & Run · Exact Device Preview** packages the imported project and sends it through the local Gradle build/install flow. After installation, **Run Exact Device Preview** launches the compiled app on the same phone. That launched app runs the actual Android/Compose runtime, including app state and callbacks. Build logs and errors are surfaced by the build workflow; the source preview has Refresh and visible failure messages. | The compiled app is launched as a separate installed app; its live Compose UI is not embedded in the source-preview viewport. Refresh re-renders source approximations; it is not Compose hot reload. Import-specific runtime validation requires a successful local build/install and on-device launch. |

## Current verification

- The source-preview theme improvement was committed as `a1d68cb64868bb0fbb060aca87099020b91949e7` on 2026-11-24.
- GitHub Actions run [#311](https://github.com/ankitjoshibusiness-blip/Pocket-APK-Studio/actions/runs/45358430946) completed successfully, including the APK callback unit-test step and debug APK packaging/upload.
- GitHub Actions run [#313](https://github.com/ankitjoshibusiness-blip/Pocket-APK-Studio/actions/runs/45359172169) completed successfully with the theme-mode helper tests and debug APK packaging/upload.
- GitHub Actions run [#314](https://github.com/ankitjoshibusiness-blip/Pocket-APK-Studio/actions/runs/45359622359) completed successfully. `testDebugUnitTest` passed (including the radio selection state tests), and the debug APK was packaged and uploaded. The tests cover the pure selection-state helper; the generated-view grouping still needs practical on-device confirmation.
- This CI run validates the Pocket APK Studio app itself. It does not execute an imported customer's Compose app on a physical device.
- GitHub Actions run [#319](https://github.com/ankitjoshibusiness-blip/Pocket-APK-Studio/actions/runs/45580392235) on 2026-11-25 validated the two-thumb RangeSlider implementation and its state tests; `testDebugUnitTest`, `assembleDebug`, and both artifact uploads succeeded.
- Final tracker commit `f7298ffbda8d20e5e21afcbe7c3fe177ebe91f57` was verified by run [#320](https://github.com/ankitjoshibusiness-blip/Pocket-APK-Studio/actions/runs/45580439389) on 2026-11-25. The local APK-engine validation, unit-test task, debug APK build, build-log upload and APK upload all completed successfully.


- **2026-11-26:** Phase 2 image preview now searches Android `drawable` and `mipmap` resource folders, including XML drawable resources. Native Android drawable XML (including supported vector drawables) is inflated in the source preview; raster decode failures and missing/unsupported resources show an explicit placeholder. This remains a best-effort preview: XML drawables that depend on resource IDs or theme attributes from the imported app may not inflate in Pocket APK Studio's own Resources and will fall back to the placeholder. Physical-device visual confirmation is still required.

## Next work

1. Keep improving Phase 2 fidelity without claiming complete Compose rendering (especially `@Preview` configurations, modifier combinations, drawable/vector resources and custom layouts).
2. Continue Phase 3 control semantics: improve inferred radio-group boundaries, accessibility actions and remaining simulated control behavior; RangeSlider now has two locally draggable handles but does not execute Compose callbacks.
3. Improve Phase 4 configuration fidelity and show the limitations of a viewport that is not an emulator.
4. Continue Phase 5 runtime handoff/error diagnostics. Do not label the source pane a true runtime renderer until the imported app is actually compiled and executed; a separate installed-app launch remains the verified path.
5. Mark a phase complete only after feature coverage and practical validation, not from compilation alone. Physical-device confirmation may still be required.

## 2026-11-25 progress

- Added `PreviewRangeSliderView`, a custom native-View approximation with independently draggable start/end handles, a highlighted selected interval, parsed literal range/value arguments, clamping and a no-crossing rule.
- Added `PreviewRangeSliderStateTest` coverage for custom ranges, input ordering, clamping, thumb movement boundaries, nearest-thumb selection and invalid numeric input. These tests exercise the state helper, not the visual control on a physical device.
- RangeSlider remains simulated source preview behavior: it does not run the imported Compose `onValueChange` callback or update the imported app's state. A compiled app launched through Build & Run remains the exact-runtime check.
