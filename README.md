# Jarvis (Android)

Say "Jarvis" (or "Jarvis, open YouTube") and it listens, thinks with the Claude API,
runs actions on your phone, and answers out loud. You can also type commands.

## Build
1. Open this folder in Android Studio (Giraffe or newer). It will download Gradle 8.2 and sync.
2. Run on a real phone (a microphone is needed).
3. In the app: grant the microphone and notification permissions, then tap **API key** and paste
   your Anthropic API key (console.anthropic.com).
4. Tap **Accessibility** and turn on "Jarvis" (needed for Home/Back, scroll, tap, type).
5. Tap **Overlay** and allow it (Android 10+ blocks background services from opening apps otherwise).
6. For offline wake-word detection: Settings > System > Languages & input >
   On-device speech recognition > download your language.

## What it can do
Open apps, links and searches; set alarms and timers; start navigation; open the dialer or an
SMS draft (you press call/send); flashlight; volume; open settings pages; Home/Back/Recents,
notifications, screenshot, lock screen; scroll, tap a labelled button, type into a field;
answer questions (with web search when your API account allows it).

## What it can't do
- Switch Wi-Fi/Bluetooth/airplane mode directly (Android doesn't allow it); it opens the settings page.
- Send, pay, buy, delete or confirm for you: those taps are blocked in code on purpose.
- Know everything: it knows what the model knows plus web search.
- Retrain itself: "learning" = it saves short facts about you and recent turns and includes them
  in each request. See or clear them with the **Memory** button.

## Privacy
Each request sends to Anthropic: what you said, your remembered facts, the last few turns.
Screen contents are never read out of the app. The listening notification must stay visible.

## Notes
- The API key is stored on the phone, which is fine for personal use. Before sharing the app,
  move the key to your own server and call that instead.
- The old coding-agent backend (Flask) is no longer used.
- Each speech-recognition restart may play a small system beep on some phones. A dedicated
  wake-word engine (Picovoice Porcupine) avoids that and saves battery.
- Not compiled or tested on a device yet; expect to fix a few build errors on first sync.
