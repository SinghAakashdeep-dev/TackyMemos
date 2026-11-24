# Tacky Memos

Sticky-note style memos that live on your Windows desktop and your Android home screen, synced between the two.

| Part | Folder | Tech |
|---|---|---|
| Windows desktop app | `desktop/` | Kotlin + Swing on Java 17 (one `.jar`) |
| Android app + home-screen widget | `android/` | Kotlin, Jetpack Compose, Glance widgets |
| Sync backend | `firebase/` | Firebase Auth (email + password) and Cloud Firestore |

## What it does

**Windows**
- Each note is a small frameless window. Drag it by its top bar and resize it from any edge. Its position and size are saved and come back after a restart.
- Notes are hidden from the taskbar and Alt+Tab. A tray icon (the yellow note) has the app menu, and clicking it brings all notes to the front.
- **Right-click a note:** tear off a new note or checklist (it appears under your mouse pointer), convert between text and checklist, change color, toggle *Always on top*, or delete.
- `Ctrl+N` tears off a new note and `Ctrl+Shift+N` a new checklist. The `+` button on a note's bar does the same, and `≡` opens the menu.
- **Quick checkboxes in any note:** type `(*)` and it turns into a ☐. Click the box to tick it (☑), which also strikes through that line. Converting the note to a checklist keeps what's ticked.
- Notes have rounded corners and a soft shadow, and each rests at a slight tilt like a real sticky note. It straightens while your mouse is over it or you are typing in it.
- Notes are written in a handwriting font (Ink Free, which comes with Windows).
- Checklists: type in *Add item* and press Enter. Click a box to tick an item. Hover a row and press × to remove it. Enter splits an item, Backspace at the start of an item merges it into the one above, and the arrow keys move between items.
- Tray menu: new note/checklist, bring notes to front, always on top, **Start with Windows**, sync sign-in, quit.

**Android**
- **Tacky Memos** widget: each widget shows one note and can be placed anywhere on the home screen and resized.
- Checklist widgets let you tick items right on the home screen. Lines made with `(*)` in a text note show as tickable boxes on the widget too. In the editor, tap a ☐ to tick it. Text uses Android's built-in handwriting-style (cursive) font. Tap the note to edit it, add or remove items, change color, convert it or delete it.
- **Tear off:** tap `+` on a widget. That creates a new note and asks your launcher to place a new widget for it, which you drop wherever you like.
- The app itself lists all notes and has sign-in, plus *Add to home screen* for any note.

**Sync**
- Both apps sign in with the same email and password and share `users/{uid}/notes/{noteId}` in Firestore.
- Conflicts: the most recent edit to a note wins (whole note, last writer wins).
- Windows checks for changes every 20 seconds and pushes your edits about 1.5 s after you stop typing.
- Android updates instantly while the app is open, and otherwise every 15 minutes in the background (Android's minimum) and shortly after you edit.
- Both work offline and catch up when back online.

## One-time setup: Firebase (free)

1. Go to <https://console.firebase.google.com>, choose **Add project**, and create it (Google Analytics is not needed).
2. **Build → Authentication → Get started → Sign-in method → Email/Password → Enable.**
3. **Build → Firestore Database → Create database** (production mode, any region).
   Open the **Rules** tab, paste the contents of [`firebase/firestore.rules`](firebase/firestore.rules), and **Publish**.
4. **Windows config:** Project settings (gear icon) → *Your apps* → add a **Web** app (`</>`).
   Copy `apiKey` and `projectId` into `desktop/dist/firebase-config.json` (start from `desktop/firebase-config.example.json`).
   You can also put it in `%APPDATA%\TackyMemos\firebase-config.json`. Then restart the app and use tray → *Sign in to sync…*.
5. **Android config:** in the same place, add an **Android** app with package name `com.stickynotes.app` (the internal ID is unchanged from the app's earlier name).
   Download `google-services.json` and put it at `android/app/google-services.json`. Then rebuild (see below).

## Windows: build and run

**Run it:** double-click **`Tacky Memos.lnk`** in this folder. Then turn on **Start with Windows** in the tray menu so your notes are there after every reboot.

Rebuild after code changes (needs JDK 17; Gradle downloads itself):

```
cd desktop
gradlew.bat jar
```

That writes `desktop/dist/TackyMemos.jar`, which the shortcut starts with `javaw.exe -jar`.

> **Why Java and not Electron, Tauri or .NET?** This PC has Windows **Smart App Control** on. It blocks unsigned programs built locally. Electron's `electron.exe` and even a .NET app's own `.dll` were refused here, and a Tauri `.exe` would be too. A `.jar` run by the signed Java runtime is allowed, so the desktop app is a single jar. If you ever turn Smart App Control off, nothing needs to change.

Data lives in `%APPDATA%\TackyMemos\notes.json` (tray → *Open data folder*). Notes from when the app was called Sticky Notes (`%APPDATA%\StickyNotes`) are copied over the first time it starts. Your sync sign-in (a Firebase refresh token, never your password) is stored in `account.json` in the same folder, readable only by your Windows user.

**Self-test:** `STICKY_PROFILE=test STICKY_SELFTEST=<folder> java -jar desktop/dist/TackyMemos.jar` drives the real note windows (tear-off, checklist editing, convert, color, positions, delete). It writes `report.txt` and a picture of each note to that folder.

## Android: build and install

Requires JDK 17 and the Android SDK (platform 35). `android/local.properties` points at the SDK.

```
cd android
gradlew.bat assembleDebug
```

The APK is `android/app/build/outputs/apk/debug/app-debug.apk`. Copy it to the phone and open it to install (allow *Install unknown apps* for your file manager when asked). Alternatively, with USB debugging on, run `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

Then:
1. Open **Tacky Memos** and sign in with the same account you use on the laptop.
2. Long-press the home screen → **Widgets** → **Tacky Memos**, drag it out, and pick a note (or *New note* / *New checklist*).

Without `google-services.json` the app still builds and works on its own; sync is just off.

## Testing sync locally (no Firebase project needed)

The Firebase emulators can stand in for the real backend:

```
cd firebase
npx firebase-tools emulators:start --only auth,firestore --project demo-stickynotes
```

Then start the desktop app with `STICKY_EMULATOR=127.0.0.1` set. `STICKY_PROFILE=<name>` gives a separate data folder, so you can run two copies as two "devices".

## Known limits

- Notes are normal windows, so Win+D ("show desktop") hides them until you click the tray icon. True "pinned to the wallpaper" mode isn't implemented; *Always on top* is the alternative.
- Android widgets can't take typing directly (an Android restriction), so tapping a note opens a small editor. Ticking items works on the widget itself.
- The phone gets laptop edits instantly only while the app is open, otherwise within about 15 minutes. Instant updates while the phone is idle would need push messages from a Cloud Function, which requires Firebase's paid Blaze plan.
- If the same note is edited on both devices before they sync, the later edit replaces the whole note. Edits are not merged item by item.
- iPhone is not supported. The widget would need a Mac to build.
