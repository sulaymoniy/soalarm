## Why I made SoAlarm

As of 14 November 2026, I've used Alarmy for 648 days, and I still haven't become an early bird.

The alarm went off every morning and I turned it off every morning, but the habit never stuck. Somewhere along the way I got really good at stopping alarms while still half asleep. My body learned the trick, and the rest of me just went back to sleep.

So I decided to build my own alarm, with Claude's help. It's my first app ever.

SoAlarm has one rule: **to stop the alarm, you have to wash your hand with soap.** Not tap a button or shake the phone. You actually have to get up, walk to the sink, turn on the water and lather up while the camera watches. One hand is enough, so you can hold the phone in the other.

By the time you're done, cold water has hit your skin, you're standing up, and you're awake. There's no lying in bed to fake it.

The alarm plays one song, *Orkney*. It starts quiet and slowly gets louder, so you wake up gently, but there's still only one way to make it stop.

That's the whole idea. I hope it works for you better than everything else worked for me.

# SoAlarm ⏰💧

An Android alarm clock that only stops when you **wash your hand with soap for 20 seconds**. One hand is enough, so you can hold the phone in the other.

- Set alarms with a time, repeat days and an optional label.
- When it rings, the alarm screen opens over the lock screen, and the song fades in from quiet to full volume over 60 seconds, with vibration.
- No snooze and no dismiss button. Point the camera at your hand, rub your fingers and palm with soap, and watch the ring fill up. Washing both hands together works too.
- If the camera isn't available, you solve 3 quick math problems instead, so you're never stuck.
- No sink nearby? After 3 minutes of ringing, a "Can't wash right now?" button lets you solve 5 harder problems instead.
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

The ring only fills while **lather is on your hand AND you're rubbing**, both at the same time.

**Test it without an alarm:** in the app, tap **⋮ → Test wash check**. The camera screen opens with a debug overlay:

- **Blue** = the hand mask (only these pixels are checked for soap; the background is ignored)
- **Green** = pixels counted as foam, **yellow** = bubble highlights
- Live values with ✅/❌ for each condition: hand visible, skin colour learned, foam % (vs. your hand's "before" level), rubbing frequency and amplitude, seconds counted

All the numbers are in one file:
[`app/src/main/java/com/sulaymoniy/soalarm/ring/WashConfig.kt`](app/src/main/java/com/sulaymoniy/soalarm/ring/WashConfig.kt)

| Constant | What it does |
|---|---|
| `WASH_SECONDS_REQUIRED` | Seconds of "soapy hand + rubbing" needed (20) |
| `FOAM_COVERAGE_THRESHOLD` | **Soap sensitivity:** share of the hand that must be newly covered by foam (12%). Raise it if a dry hand passes; lower it if real lather isn't detected |
| `BUBBLE_MIN_CONTRAST`, `FOAM_MIN_BUBBLES` | How "bubbly" foam must look. Raise them if smooth white things count; lower them if lather is missed |
| `FOAM_BRIGHTER_THAN_SKIN`, `FOAM_SATURATION_VS_SKIN` | How much whiter than your own skin foam must be |
| `RUB_MIN_HZ`, `RUB_MAX_HZ` | Rubbing rhythm: back-and-forth 1–4 times per second |
| `RUB_MIN_AMPLITUDE` | How far your fingers must move against your palm. Lower it if real rubbing isn't detected |
| `MANUAL_FALLBACK_AFTER_MS` | After the alarm has rung this long (3 min), a **"Can't wash right now? Solve math instead"** button appears |
| `MANUAL_FALLBACK_PROBLEMS` | How many harder math problems that button asks for (5) |

Good light helps. Show your hand to the camera dry first, then lather up: SoAlarm compares the foam with how your hand looked before.

**App icon:** the source art is `design/app_icon_source.webp`. The launcher icon layers live in `app/src/main/res/mipmap-*`, and `design/play_store_icon_512.png` is the 512×512 icon for the Play Store listing.

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
