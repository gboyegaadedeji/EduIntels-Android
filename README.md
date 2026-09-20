# EduIntels Android WebView App v1.0

A native Android wrapper for **https://eduintels.ng/**. It does not depend on the browser/PWA install prompt.

## What it supports

- Persistent WebView login/session cookies
- JavaScript, DOM storage and modern web app features
- Secure HTTPS-only networking
- EduIntels links stay inside the app
- Paystack checkout stays inside the app
- External links, telephone, email, WhatsApp and Android intent links open in the appropriate app
- File uploads from device storage
- Camera capture for image upload fields
- Authenticated downloads using the current WebView cookies
- Native app launch/preloader experience
- Page loading progress indicator
- Native offline/retry screen
- Full-screen HTML5 video
- Back-button WebView history
- Deep-link handling for eduintels.ng links
- Hides browser/PWA install prompts inside the installed Android app
- Preserves EduIntels branding and app icon

## Build an installable APK in Android Studio

1. Install Android Studio.
2. Open this folder as a project.
3. Allow Gradle sync to finish and install Android SDK 35 if Android Studio requests it.
4. For a test APK, choose **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. Android Studio creates the debug APK at:
   `app/build/outputs/apk/debug/app-debug.apk`
6. Copy `app-debug.apk` to an Android phone and open it to install. Android may ask you to allow installs from that source.

## Build a signed production APK

Use **Build > Generate Signed App Bundle or APK > APK**. Create and securely retain your own release keystore. Do not lose the release keystore: future updates must be signed with the same key.

Recommended production identifiers:

- Application ID: `ng.eduintels.app`
- App name: `EduIntels`
- Website: `https://eduintels.ng/`

## Important security notes

This app deliberately does not bypass TLS/SSL errors. It also disables HTTP cleartext traffic. School data remains on EduIntels servers and is not packaged into the APK.

The wrapper retains website sessions through Android WebView cookies. Sensitive school pages are not copied into the APK.

## Google sign-in

Google can restrict OAuth login inside embedded WebViews. Standard EduIntels username/password login works inside the app. If Google login is a required app feature, implement native Google Sign-In / Credential Manager as a second phase instead of weakening WebView security.

## Push notifications

Web push does not behave like Chrome push inside a basic Android WebView. Native Firebase Cloud Messaging can be added later if EduIntels needs reliable parent, staff or school notifications from the APK.

## Build without installing Android Studio locally

A GitHub Actions workflow is included at `.github/workflows/build-apk.yml`.

Push this project to a GitHub repository, open **Actions > Build EduIntels APK > Run workflow**, and GitHub will build `app-debug.apk` as a downloadable workflow artifact.

This is useful when you want a test APK without configuring an Android SDK on your own computer.