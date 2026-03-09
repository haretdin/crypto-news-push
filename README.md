# crypto news push

Never trade the news late again.

`crypto news push` delivers the fastest and most actionable crypto news directly to your phone. It monitors the crypto ecosystem across exchange announcements, project updates, market-moving headlines, and other breaking signals, then surfaces the items that matter as soon as they happen.

No noise. No delays. Just the signals that matter.

Whether you are a trader, investor, or builder, staying early means staying profitable. With real-time alerts and curated delivery, you can react to market-moving events before the crowd even knows they happened.

Be early. Stay ahead. Trade with information.

## Overview

This is an open-source Android app built with Kotlin and Jetpack Compose. It combines a real-time WebSocket feed with RSS polling for exchange announcements, stores recent items locally, and delivers high-priority on-device notifications.

## Features

- Real-time WebSocket news feed
- RSS polling for exchange announcements
- Recent-news-first home feed
- Local persistence with Room
- High-priority local notifications with sound, vibration, and heads-up behavior
- Foreground-service based delivery for timely updates

## Stack

- Kotlin
- Jetpack Compose
- Room
- OkHttp WebSocket
- Android notification channels and foreground services

## Project Layout

- `app/`: Android app module
- `app/src/main/java/com/cryptonews/push/`: application source
- `gradle/` and `gradlew`: Gradle wrapper
- `512.png`: source icon asset for launcher icons

## Build

Requirements:

- Android Studio
- Android SDK
- JDK 17

Optional local configuration:

- Create `local.properties` for local-only values such as API keys used in custom builds
- A template is included as `local.properties.example`

Build commands:

- `./gradlew assembleDebug`
- `./gradlew assembleRelease`

## Privacy

Privacy details are documented in [PRIVACY.md](PRIVACY.md).

Summary:

- No account system
- No first-party backend required by default
- News is fetched directly from configured upstream providers
- Notifications are generated locally on-device after content is received
- Optional third-party API integrations, if enabled in a custom build, communicate directly with that provider

## License

This project is open source and licensed under the GNU General Public License v3.0 or later.

See [LICENSE](LICENSE) for the full license text.

## Copyright

Copyright (C) 2026 crypto news push contributors.

## Contact

haretdin@proton.me
