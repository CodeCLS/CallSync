# CallSync

An Android app that records your phone calls, transcribes them (German + English), and saves each one as a page in a Notion database.

## How it works

1. When a call connects, CallSync shows a large notification with two buttons: **● Record** and **Don't record**.
2. If you tap **Record**, the call is recorded. A live transcript appears in the app and on the home-screen widget.
3. When the call ends, the recording is transcribed with Deepgram (`nova-3`, multilingual). A page is created in your Notion database with the contact, direction, duration, date and the full transcript.
4. Calls that look personal (family, friends) stay out of Notion. Calls where nobody spoke are logged as "no answer".

Settings has a switch to turn off the prompt, so every call records automatically.

## Requirements

- Android 14 or newer (API 34+)
- [Shizuku](https://shizuku.rikka.app/) installed and running. The recorder runs through Shizuku because a normal app can't capture call audio.
- A Notion account and a Deepgram API key (the free tier is enough to start)

## Install

```bash
git clone https://github.com/CodeCLS/CallSync.git
```

Open the folder in Android Studio and run the `app` configuration on your phone. You can also run `./gradlew :app:installDebug` with the phone connected over USB.

## First launch (onboarding)

The app asks for:

| Field | Where to get it |
|---|---|
| **Your name** | Appears as "recorded by …" on each Notion page |
| **Notion integration secret** | [notion.so/profile/integrations](https://www.notion.so/profile/integrations) → *New integration* (internal) → copy the secret (`ntn_…`) |
| **Notion database link** | Open the database → `•••` → *Connections* → add your integration. Then *Share → Copy link* and paste the whole link. |
| **Deepgram API key** | [console.deepgram.com](https://console.deepgram.com/signup) → API Keys |

The database needs a `Name` (title) property and a `Date` property. The **Test connection** button checks that the integration can see the database.

Then grant the permissions (microphone, phone, call log, notifications) and Shizuku in **Settings**. Once every status light is green, CallSync is armed.

You can change these values later under **Settings → Edit name, Notion & Deepgram**. They are stored only on the phone.

## Developer fallback keys (optional)

When building, you can put keys in `local.properties` (gitignored) instead of typing them into the app:

```properties
NOTION_TOKEN=ntn_...
NOTION_DATABASE_ID=...
TRANSCRIBE_API_KEY=...
```

Values entered in the app override these. **Don't share an APK built with these keys**: the keys are compiled into it.

## Project layout

- `service/CallRecordingService.kt`: watches the call state, shows the Record / Don't record prompt, starts and stops recording
- `recording/`: the Shizuku-side recorder and the live Deepgram transcriber
- `sync/`: post-call transcription, personal-call filter, Notion page creation (WorkManager, retries until it succeeds)
- `data/AppSettings.kt`: the values entered during onboarding
- `widget/`: home-screen widget

## Legal

Recording calls without the other party's consent is illegal in many places, including Germany (§ 201 StGB). Tell the other person, or get their consent, before you tap Record.
