# KMU Results Scanner — Android app

An installable Android app for the KMU Results Scanner. It opens the scanner
(a single HTML page) full-screen, like a real app. No sign-in, no server,
no account, no paid service.

## What the app does

- Opens the scanner from inside the app (the HTML file lives in the app).
- **Choose folder**: pick a whole folder of result PDFs on the phone. The app
  hands every PDF in the folder (including subfolders) to the scanner.
- **Choose files**: pick PDF files one by one, or many at once.
- **Downloads**: the scanner's report buttons (PDF, DOC, TXT, CSV, HTML) save
  straight into the phone's **Downloads** folder.
- Works offline. The only thing that needs the internet is the page font.

## The scanner HTML — easy to swap

The scanner page is ONE file:

```
app/src/main/assets/kmu-results-scanner.html
```

To update the app with a new version of the scanner, replace that one file
with the new exported HTML (same name, same place). Nothing else has to
change. Push the change and GitHub Actions builds a new APK by itself.

> The file in the app now is the redesigned, animated scanner (74,998 bytes):
> teal hero with a moving scan beam, pulsing picker rings, animated progress
> bar, count-up stats, rising result cards, a pulsing DUPLICATE tag, no
> instructions on the page, full folder location for every file, and the
> stronger PDF reader (ToUnicode font maps).

## How the APK is built (GitHub Actions)

Every push to `main` runs `.github/workflows/build-apk.yml`:

1. Sets up JDK 17 and Gradle 8.7.
2. Builds the debug APK (`./gradlew :app:assembleDebug`).
3. Uploads it as a build artifact.
4. Puts it on the **Releases** page under the tag `latest`, so there is one
   stable download link:

```
https://github.com/tufailahmad14082001/kmu-results-scanner-app/releases/download/latest/KMU-Results-Scanner.apk
```

## How to install it on the phone

1. Open the link above on the phone and tap the APK file to download it.
2. Open the downloaded file and tap **Install**.
3. If the phone asks, allow **"Install unknown apps"** for the browser,
   then go back and tap Install again.
4. Open **KMU Results Scanner** from the apps list.

It is a debug APK (not signed with a store key), so it installs directly
from the file — no Play Store needed.

## Building it on your own computer

```bash
./gradlew :app:assembleDebug
```

The APK is then at `app/build/outputs/apk/debug/app-debug.apk`.
You need JDK 17 and the Android SDK (API 34, build-tools 34.0.0).

## Honest limits

- The folder picker and the blob downloads are handled inside the app, but
  they still need a real test on the phone (Tecno Camon 30). If picking a
  folder does not work there, use **Choose files** and pick all the PDFs
  inside the folder at once.
- The scanner reads text from inside a PDF only when the PDF has a real
  text layer. Photo/scanned PDFs are listed from their file names.
- The Share button in the scanner does not work inside the app's WebView;
  the page says so itself and the Copy button is the fallback.
