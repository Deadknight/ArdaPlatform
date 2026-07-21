# ARDA Platform Vehicle Energy integration

This revision hosts the Google Maps OEM vehicle-energy provider inside
`ArdaPlatform`; the standalone `com.arda.mapsenergy` package is no longer
needed.

## Included

- Fixed Maps authority: `content://google_maps_energy`
- Raw model endpoint: `/model`
- Forecast sink: `/forecast`
- Energy tab in the existing Compose UI
- Fuel-mode selector (`UNLEADED` / `ELECTRIC`)
- Manufacturer -> model -> variant catalog backed by
  `https://aap.opencharge.top/`
- Raw model download and install
- Battery percentage patching without rebuilding the remaining model fields
- Signature-protected cross-user admin bridge through the existing
  ArdaPlatform provider

The downloaded payload may be either the raw Maps model or an outer
`SensorBatch` containing only field 23. The outer wrapper is removed while the
inner bytes are preserved.

## Important fuel-type boundary

The UI fuel selector is a persisted ARDA configuration and an admin API gate.
It does **not** write `INFO_FUEL_TYPE` itself. On this AAOS build,
`INFO_FUEL_TYPE` is a static, read-only VHAL property. Google Maps opens the
model only when `CarInfoManager.getFuelTypes()` sees exactly `[ELECTRIC]` at
Maps startup. The production VHAL must therefore start with the corresponding
fuel-type configuration, or a dedicated VHAL-side privileged control must be
added separately.

## Remove the standalone PoC before installing this build

The fixed authority can have only one owner:

```bash
NET="192.168.1.185:5555"

adb -s "$NET" root
adb -s "$NET" remount
adb -s "$NET" shell rm -rf /system/product/app/ArdaMapsEnergyProvider
adb -s "$NET" reboot
```

Build the updated ArdaPlatform in Android Studio, then install it as the
existing product/system application. A normal `/data/app` installation does
not satisfy Maps' system-only provider lookup.

## Runtime checks

```bash
NET="192.168.1.185:5555"

adb -s "$NET" shell pm path --user 10 \
  com.github.deadknight.ardaplatform.debug

adb -s "$NET" shell dumpsys package providers | \
  grep -A2 -B1 google_maps_energy

adb -s "$NET" shell content call \
  --user 10 \
  --uri content://google_maps_energy \
  --method status
```

After a model is applied and Maps is cold-started with pure-electric VHAL
state, the provider log must include:

```text
MODEL_OPEN_READ caller=uid=...,package=com.google.android.apps.maps bytes=...
```

## Battery percentage API

The canonical externally callable API is the existing signature-protected
ArdaPlatform admin provider. For the current debug application ID:

```bash
NET="192.168.1.185:5555"

adb -s "$NET" shell content call \
  --user 10 \
  --uri content://com.github.deadknight.ardaplatform.debug.provider \
  --method vem_set_battery_percent \
  --extra user_id:i:10 \
  --extra percent:i:74
```

Read status:

```bash
adb -s "$NET" shell content call \
  --user 10 \
  --uri content://com.github.deadknight.ardaplatform.debug.provider \
  --method vem_status \
  --extra user_id:i:10
```

Set the ARDA fuel-mode selection:

```bash
adb -s "$NET" shell content call \
  --user 10 \
  --uri content://com.github.deadknight.ardaplatform.debug.provider \
  --method vem_set_fuel_type \
  --extra user_id:i:10 \
  --extra fuel_type:s:ELECTRIC
```

The percentage setter changes only:

```text
root.field1.field3.field1 = battery level Wh
```

It derives the new level from the unchanged capacity at:

```text
root.field1.field4.field1 = battery capacity Wh
```

and then notifies `content://google_maps_energy/model`.

## Provider methods

`google_maps_energy` implements:

- `status`
- `notify_model`
- `set_battery_percent`
- `set_fuel_type`
- `install_inner_hex`
- `install_inner_bytes`
- `clear_model`

Administrative consumers should normally use the signature-protected
ArdaPlatform provider instead of calling the Maps-facing authority directly.
