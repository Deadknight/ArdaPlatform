# ARDA Platform video route UI

Based on the uploaded `platform(20260830-100306).zip`.

Changed/new files:
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/github/deadknight/ardaplatform/ArdaVideoRoute.kt`
- `app/src/main/java/com/github/deadknight/ardaplatform/ArdaPlatformUi.kt`
- `app/src/main/res/values/strings.xml`

Behavior:
- Default selected route: HDMI / Direct PCT.
- Optional fallback: TXP / production HWC writeback.
- Selection is persisted in `persist.sys.arda.video_route`.
- Active route is reported by SurfaceFlinger in `sys.arda.video_route.active`.
- Route changes require reboot. The UI exposes an explicit confirmation dialog and reboot action.
- The app uses `/system/bin/getprop` and `/system/bin/setprop`, not hidden APIs.
- AOSP SELinux and privapp integration is required before runtime use.
