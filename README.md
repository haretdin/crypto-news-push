# crypto news push

Never trade the news late again.

`crypto news push` is an open-source Android app that delivers fast, actionable crypto and tech news directly to your phone. It combines a live BWEnews WebSocket feed with multiple crypto and technology RSS sources, stores incoming items locally, and pushes important updates as Android notifications the moment they arrive.

No noise. No delays. Just the signals that matter.

Whether you are a trader, investor, or builder, staying early means staying profitable. With real-time alerts and curated multi-source updates, you can react to market-moving events before the crowd even knows they happened.

Be early. Stay ahead. Trade with information.

## Features

- Live BWEnews WebSocket connection with `ping` / `pong` keepalive
- Multi-source RSS polling for higher news frequency
- Local notifications with sound, vibration, and high-priority delivery
- Unified feed UI for crypto news and exchange announcements
- Pull-to-refresh support
- Automatic background refresh every 60 seconds
- Local Room database for recent items
- Clickable source links in the app
- Native Android app built with Kotlin and Jetpack Compose

## Sources

Current app sources include:

- BWEnews WebSocket: `wss://bwenews-api.bwe-ws.com/ws`
- BWEnews RSS: `https://rss-public.bwe-ws.com/`
- CoinDesk RSS
- Cointelegraph RSS
- TechCrunch RSS
- Google Blog RSS
- Microsoft Blog RSS

## Tech Stack

- Kotlin
- Jetpack Compose
- Room
- OkHttp
- Kotlin Serialization
- Android foreground service

## Setup

1. Open the project in Android Studio.
2. Create `local.properties` if needed.
3. Add your DeepSeek API key only if you plan to re-enable translation-related code in your own fork:

```properties
DEEPSEEK_API_KEY=your_deepseek_api_key
```

4. Run the `app` module on an Android 8.0+ device or emulator.
5. Grant notification permission on Android 13+.

## Build

Debug build:

```bash
./gradlew assembleDebug
```

Release build:

```bash
./gradlew assembleRelease
```

Release signing files are intentionally not included in this public repository.

## Privacy and Terms

See the dedicated policy pages:

- [Privacy Policy](privacy-policy.md)
- [Terms and Conditions](terms-and-conditions.md)

## License

This project is open source under the GNU General Public License v3.0.

See [LICENSE.md](LICENSE.md) and [LICENSE](LICENSE).

## Copyright

See [COPYRIGHT.md](COPYRIGHT.md).

## Contact

- haretdin@proton.me
