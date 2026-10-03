# NexoinVault

NexoinVault is an Android app for storing personal images and videos in a passcode-protected, encrypted vault on the device. Firebase is used for account sign-in and a small user profile record; the media vault itself is stored in the app's private local storage.

> The app is under active development. Security-sensitive applications should be independently reviewed before relying on them for highly sensitive data.

## Features

- Email/password account registration and sign-in with Firebase Authentication.
- Firebase Realtime Database user profile records under `users/{uid}`.
- A local six-digit passcode to unlock the vault after account sign-in. Existing installations can use the legacy full-password unlock path.
- Separate **Images** and **Videos** vault folders.
- Import one or more media files with Android's document picker, or import supported URIs from the clipboard.
- Per-file AES-GCM encryption in app-private storage. The vault master key is protected by passcode-derived key material; the unwrapped key is held in process memory while the vault is unlocked.
- Image and video previews, plus a password-change flow.
- Migration support for files saved by older app versions.

Media is not uploaded to Firebase by the vault storage code. Firebase account/profile data and the encrypted local media vault are separate parts of the app.

## Requirements

- Android Studio or a compatible Android build environment.
- JDK **23** (the repository's Gradle daemon toolchain is configured for Oracle JDK 23).
- Android SDK Platform **35**.
- Android device or emulator running Android **7.0 (API 24) or later**.
- A Firebase project configured for the Android application ID `com.example.nexoinvaulit`.

The app uses Java 8 language compatibility and targets Android API 34; the Android SDK Platform 35 is used to compile it.

The checked-in `gradle.properties` contains a Windows-specific `org.gradle.java.home` path. Before building on another machine, update it to point to that machine's JDK 23 installation.

## Firebase setup

The app applies the Google Services Gradle plugin and uses Firebase Authentication and Realtime Database.

1. Create or select a Firebase project and add an Android app with application ID `com.example.nexoinvaulit`.
2. Put that Firebase project's `google-services.json` in `app/google-services.json`.
3. Enable **Email/Password** in Firebase Authentication.
4. Create a Realtime Database and configure rules so each signed-in user can access only their own `/users/{uid}` record.
5. Sync the project in Android Studio.

Use a Firebase project you control. Do not allow public reads or writes to the Realtime Database. Firebase account password resets should not be treated as a recovery mechanism for the local vault passcode or its encryption key.

## Build

From the repository root, after configuring the local JDK 23 path and installing Android SDK Platform 35:

```bash
# Linux / macOS
bash ./gradlew :app:assembleDebug

# Run local JVM unit tests
bash ./gradlew :app:testDebugUnitTest
```

On Windows:

```bat
gradlew.bat :app:assembleDebug
gradlew.bat :app:testDebugUnitTest
```

The debug APK is written to `app/build/outputs/apk/debug/`. To run instrumentation tests, start an Android emulator or connect a device and run:

```bash
bash ./gradlew :app:connectedDebugAndroidTest
```

Open the repository root in Android Studio for IDE-based sync, build, and run workflows.

## Project layout

```text
app/
  src/main/java/com/example/nexoinvaulit/
    LoginActivity.java          # Local vault unlock flow
    SetupActivity.java          # First-run passcode setup
    PasswordUtils.java          # Credential verification and wrapped-key handling
    VaultSession.java            # In-memory unlocked vault key
    VaultStorageManager.java     # Encrypted media storage and migration
    folder.java                 # Images/Videos folder screens and import
    ViewerActivity.java          # Media preview
    ChangePasswordActivity.java  # Local vault password/passcode change
  src/main/res/                  # Layouts, themes, icons, and strings
```

## Security and privacy notes

- Imported media is encrypted with AES-GCM and saved under the app's private files directory. Android's app sandbox provides an additional access boundary.
- Media must be decrypted temporarily for preview. Treat an unlocked device and any cached plaintext copy as sensitive.
- The six-digit passcode is convenient but has limited entropy. Use a strong device lock and keep the app and device updated.
- Clearing app data or uninstalling can remove the local vault. Keep any important originals separately; the app does not provide cloud backup of vault media.
- The repository's tests are currently minimal and do not constitute a security audit.

## License

No `LICENSE` file is included in this repository. Contact the project owner before redistributing or reusing the code.
