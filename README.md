# SoAlarm ⏰💧

An Android alarm clock that only stops when you **wash your hands with soap for 20 seconds**.

- Set alarms with a time, repeat days and an optional label.
- When it rings, the alarm screen opens over the lock screen, and the song fades in from quiet to full volume over 60 seconds, with vibration.
- No snooze and no dismiss button. Point the camera at your hands, rub them together with soap, and watch the ring fill up.
- If the camera isn't available, you solve 3 quick math problems instead, so you're never stuck.
- Everything runs on your phone. No internet, no ads, no analytics. See [PRIVACY_POLICY.md](PRIVACY_POLICY.md).

Built with Kotlin, Jetpack Compose (Material 3), Room, CameraX and MediaPipe Hand Landmarker.
Works on Android 8.0 (API 26) through Android 17 (API 37).

---

## 1. Open the project in Android Studio

1. Install **Android Studio** (any recent version) from <https://developer.android.com/studio>.
2. Start Android Studio and choose **File → Open…**.
3. Select the `soalarm` folder (the one that contains `settings.gradle.kts`) and click **OK**.
4. If asked "Trust project?", click **Trust Project**.
5. Wait for the **Gradle sync** to finish. The progress bar is at the bottom right. The first sync downloads libraries and can take a few minutes.

> The first build also downloads the hand-tracking model (`hand_landmarker.task`, about 7.5 MB) from Google's MediaPipe model storage into `app/src/main/assets/`. That happens once, automatically.

## 2. Run it on your phone (USB debugging)

**On your phone (one time only):**

1. Open **Settings → About phone**.
2. Tap **Build number** 7 times until it says "You are now a developer". (On Xiaomi: *MIUI version*; on Samsung: *Software information → Build number*.)
3. Go back to **Settings → System → Developer options** (sometimes just **Settings → Developer options**).
4. Turn on **USB debugging**.

**Then:**

1. Connect the phone to the computer with a USB cable.
2. On the phone, tap **Allow** on the "Allow USB debugging?" pop-up. Tick "Always allow from this computer".
3. In Android Studio, pick your phone in the device drop-down at the top.
4. Click the green **▶ Run** button (or press `Shift + F10`).
5. SoAlarm opens on your phone. 🎉

**First use on the phone:**

1. Tap **+**, choose a time, and tap **Save**. You'll see "Rings in X h Y min".
2. Allow **Notifications** and **Camera** when asked.
3. In the blue card at the top, tap **Allow** for any remaining item (battery, full-screen alarms, exact alarms).
4. Test it: make an alarm for 2 minutes from now and lock your phone.

> **Tip (Xiaomi, Huawei, Oppo, Vivo, OnePlus…):** these phones kill background apps aggressively. Also go to **Settings → Apps → SoAlarm** and turn on **Autostart**, and set battery to **No restrictions**.

## 3. Tuning the hand-washing check

All the numbers are in one file:
[`app/src/main/java/com/sulaymoniy/soalarm/ring/WashConfig.kt`](app/src/main/java/com/sulaymoniy/soalarm/ring/WashConfig.kt)

| Constant | What it does |
|---|---|
| `WASH_SECONDS_REQUIRED` | Seconds of washing needed (20) |
| `FOAM_SECONDS_REQUIRED` | Seconds of those that soap must be visible (5) |
| `FOAM_PIXEL_THRESHOLD` | **Soap sensitivity.** Raise it if bare hands count as soapy; lower it if real foam isn't detected |
| `RUB_SPEED_THRESHOLD` | How hard you need to rub |

Good light helps. A plain background that isn't white helps the soap check.

## 4. Publish on Google Play (signed App Bundle)

Google Play needs an **Android App Bundle (`.aab`)** signed with your own key.

### a) Create your upload key and the bundle

1. In Android Studio: **Build → Generate Signed App Bundle or APK…**
2. Choose **Android App Bundle**, then click **Next**.
3. Under *Key store path*, click **Create new…**
   - Save it **outside this project folder**, for example `C:\Users\<you>\keys\soalarm-upload.jks`.
   - Choose a strong password, and fill in the alias (e.g. `upload`) and your name.
   - ⚠️ **Back up this file and its passwords** (password manager plus a USB stick). If you lose it you can't update your app.
4. Click **Next**, choose **release**, and click **Create**.
5. When it's done, click **locate**. The file is `app/release/app-release.aab`.

> The `.gitignore` already blocks `*.jks`, `*.keystore`, `keystore.properties` and `*.aab`, so you can't commit them to GitHub by accident. Never share these files.

### b) Upload to Google Play

1. Create a developer account at <https://play.google.com/console> (one-time fee).
2. **Create app** → name "SoAlarm", app type *App*, *Free*.
3. Go to **Test and release → Production** (or start with **Internal testing**) → **Create new release**.
4. Accept **Play App Signing** (recommended), then upload `app-release.aab`.
5. Fill in the **App content** section:
   - **Privacy policy URL:** link to your `PRIVACY_POLICY.md` on GitHub (e.g. `https://github.com/sulaymoniy/soalarm/blob/main/PRIVACY_POLICY.md`).
   - **Data safety:** "No data collected" and "No data shared". Camera frames are processed on-device only.
   - **Exact alarm permission (`USE_EXACT_ALARM`):** declare that the app's core function is an **alarm clock**.
   - **Full-screen intent (`USE_FULL_SCREEN_INTENT`):** declare **Alarm**.
   - **Foreground service types:** `systemExempted` / `mediaPlayback`. Explain that it plays the alarm sound while the alarm rings, until the user stops it. A short screen recording of an alarm ringing helps the review.
   - **Battery optimization exemption:** explain that it's an alarm clock that must ring on time.
6. Add screenshots, a short and a full description, and an icon (512×512). You can export the icon from Android Studio's **Image Asset** tool.
7. Click **Review release → Start rollout**.

**For every new version:** raise `versionCode` (and `versionName`) in [`app/build.gradle.kts`](app/build.gradle.kts), then build a new signed bundle with the **same key**.

---

## Project layout

```
app/src/main/java/com/sulaymoniy/soalarm/
├── MainActivity.kt          app entry (alarm list)
├── SoAlarmApp.kt            notification channel
├── data/                    Room database: Alarm, AlarmDao, AlarmDatabase
├── alarm/                   scheduling, receivers, ringing foreground service
├── ring/                    alarm screen, hand-washing check, math fallback
└── ui/                      list, editor, permissions, theme
```

Build from the command line (Windows):

```bash
gradlew.bat assembleDebug
```
