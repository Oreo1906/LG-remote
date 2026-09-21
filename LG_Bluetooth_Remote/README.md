# LG Bluetooth Remote (experimental)

A local-only Android Bluetooth HID remote for LG TVs that accept Bluetooth keyboard/mouse/controller input. It does **not** use Internet, mobile data, Wi-Fi, location, or LG cloud services.

## What it tries to provide
- Bluetooth keyboard-style D-pad, Enter/OK, Back, Home/Menu
- Consumer-control Volume +/−, Mute, Play/Pause, Previous/Next
- Experimental Channel +/−
- Bluetooth mouse-style touchpad pointer + tap-to-click

## Requirements
- Android 9 (API 28) or newer
- Phone firmware must expose Android's `BluetoothHidDevice` profile
- LG TV must support Bluetooth input devices (keyboard/mouse/controller)

## Pairing when your normal remote is broken
1. Install/open the app and grant Nearby Devices/Bluetooth permissions.
2. Tap **Make discoverable**.
3. On the LG TV, open its Bluetooth input-device/controller/keyboard pairing screen. If needed, temporarily use LG ThinQ while your phone is close enough to your home Wi-Fi, or use the TV's physical joystick/button.
4. Select the phone / **LG BT Remote** if shown and complete pairing.
5. Back in the app: **Select TV** → choose the paired TV → **Connect to TV**.
6. Test the D-pad and touchpad first.

## Important limitations
This is not an implementation of LG's proprietary Magic Remote protocol and does not provide gyroscope/air-mouse or voice-command emulation. Button behavior varies by TV/webOS model. Some phones/OEM Android builds disable Bluetooth HID Device mode even on supported Android versions.

## Build locally
Open the project in Android Studio (JDK 17+) and run `assembleDebug`, or use the included GitHub Actions workflow.
