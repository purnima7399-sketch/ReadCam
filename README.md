# ReadCam

A notes app for scripts that also records you. Open a script and you get the whole passage on screen as static text you scroll yourself, a small live selfie preview in a corner (like your own tile in Google Meet), and recording from the front camera at the best quality it supports (4K on a Pixel 10).

## Using it

- **New script** on the home screen, or share text from Google Keep (or any app) to ReadCam and it becomes a script.
- Long-press a script in the list to delete it.
- **Read & record** opens the recording screen:
  - Scroll the text with one finger; pinch with two fingers (or tap A− / A+) to change the size.
  - Drag the selfie preview to whichever corner you like. It snaps into place and remembers your choice.
  - Tap the red button (or press either volume button) to start. A 3-2-1 countdown runs first. Tap again to stop.
  - The text and preview are not in the video; you get clean camera footage with sound.
- Videos save to your gallery under **Movies › ReadCam**. The label under the timer shows the quality in use (4K, 1080p…).

## Building the APK

**With Android Studio (on a computer):** open this folder, wait for it to finish syncing, connect the phone with USB debugging on, and press Run. Or use Build › Build APK(s) to get `app/build/outputs/apk/debug/app-debug.apk`.

**With GitHub (no software to install):** push this folder to a GitHub repository. The included workflow builds the APK automatically; download it from the repository's Actions tab (the run's "ReadCam-apk" artifact) and open it on the phone to install.
