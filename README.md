# bip158

Kotlin Multiplatform [BIP-158](https://github.com/bitcoin/bips/blob/master/bip-0158.mediawiki) compact block filters (Golomb-coded sets).

This is a close port of the TypeScript [`bip158`](https://github.com/GladosBlueWallet/bip158) library. Public function names, parameter names, and behavior match that API so a wallet similar to Blueberry can consume it on Android and iOS.

## Targets

- **Android** and **iOS** (primary)
- JVM and linuxX64 (bonus)

## Public API

```kotlin
import bip158.buildBasicFilter
import bip158.matchAnyBasicFilters
import bip158.filterHash
import bip158.filterHeader
import bip158.hexToBytes
import bip158.bytesToHex

val filter = buildBasicFilter(blockHashDisplay, elements)
val hits = matchAnyBasicFilters(listOf(filter), listOf(blockHashDisplay), watchlist)
val header = filterHeader(filterHash(filter), prevHeader)
```

Also exported: `buildGcs` / `matchGcs` / `parseGcs` / `serializeGcs` / `deserializeGcs`, `basicFilterElements`, `decodeBlockTransactions`, CompactSize encode/decode, and `sha256d`.

## Tests

```bash
./gradlew :library:jvmTest
./gradlew :library:linuxX64Test
./gradlew :library:testAndroidHostTest
# on macOS:
./gradlew :library:iosSimulatorArm64Test
```

Official BIP-158 testnet-19 vectors live in `testdata/bip158/`.
