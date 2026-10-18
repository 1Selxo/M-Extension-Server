# Server performance

This page covers the server performance harness, the optimizations it
justified, and the equivalence tests that guard them. Every optimization
here only changes speed: responses, ordering, headers and side effects are
unchanged.

## Running the harness

```sh
./gradlew :server:perf       # both modes, JIT first
./gradlew :server:perfJit    # normal HotSpot JIT
./gradlew :server:perfInt    # server JVM runs with -Xint
```

`-Xint` is the closest desktop stand-in for the iOS runtime, which is
OpenJDK Zero without a JIT. Read the `perfInt` numbers as "relative cost on
iOS", not as absolute iOS timings.

Results go to `server/build/perf/results-<mode>.json`, followed by a
Markdown table on stdout. Options (Gradle properties):

| Property | Meaning | JIT default | `-Xint` default |
|---|---|---:|---:|
| `-Pperf.warmup=N` | unmeasured calls per endpoint | 15 | 3 |
| `-Pperf.iterations=N` | measured calls per endpoint | 40 | 10 |
| `-Pperf.startupRuns=N` | fresh server processes for the startup metric | 5 | 3 |
| `-Pperf.installRuns=N` | APK conversions (first one plus later ones) | 8 | 4 |
| `-Pperf.latencyMs=N` | fixed delay the fake upstream adds to each response | 10 | 10 |
| `-Pperf.only=a,b` | run only the named endpoints (`startup` included) | all | all |
| `-Pperf.dump=<dir>` | write normalized responses for diffing | off | off |
| `-Pperf.jfr=true` | record a JFR profile of the server (1 ms sampling, stack depth 1024) | off | off |
| `-Pperf.serverJvmArgs=a\|b` | extra JVM flags for the server process, separated by `\|` | none | none |

The harness runs against the shadow JAR, the same layout the desktop and iOS
builds ship. Add `-PiosRuntime=true` to measure the iOS variant of the JAR.

### How it works

- **Server.** A child JVM runs `PerfServerMain` (`server/src/perf`), which
  starts the server through `EmbeddedBridge.start(0, …)`, exactly as the iOS
  bridge does. The child gets a fresh root directory on every run.
- **Parent.** The parent JVM drives the child over stdin. `reset` clears the
  loaded extensions so the next request has to convert the APK again.
- **Fake upstream.** `FakeUpstream` is a local HTTP server that adds a fixed
  delay before every response. It serves deterministic listing, details,
  300-chapter, page-list and image payloads. The harness counts upstream calls
  per request and reports "processing" time: median minus latency × calls.
- **Fixture extension.** `server/src/perfExtension` is a real
  `ParsedHttpSource` with `ConfigurableSource`. It has every filter kind, three
  preference kinds, Jsoup parsing, date parsing and JSON page lists. It is
  compiled for JVM 8, dexed with d8 and packaged by
  `tool/perf/build_fixture_apk.sh`. The resulting APK is committed at
  `server/src/perf/resources/perf-extension.apk`. To rebuild it you need an
  Android SDK; then run `./gradlew :server:perfFixtureApk`.
- **Requests.** The client sends the requests Mangatan sends, with the same
  headers and gzip negotiation. That includes the stored preferences and the
  `__mangatan_bridge_context__` entry.
- **Metrics.**
  - Each endpoint reports median and p90 (nearest rank) after warm-up.
  - Three cold-start metrics use fresh processes:
    - *startup to first request*: process spawn to the first `200` from
      `/capabilities`;
    - *startup to first extension response*: spawn to the response of the
      first APK request, sent right after `/capabilities` as Mangatan does;
    - *install in a cold process*: that APK request alone.
  - Install is the first request for a new APK (conversion plus load). The
    first conversion in a process is reported separately from later ones.
- **Dumps.** The dumps normalize the variable parts of a response so two
  builds can be diffed: dates, the port, proxy tokens (renumbered in order of
  appearance) and image bodies (replaced by SHA-256 and size).

No timing budget is hard-coded anywhere; the harness only reports numbers.

## Results

**Machine:** an Apple Silicon Mac with JBR 21. It was shared with other work,
so contention was real.

**Method.** To control for that contention, every comparison below runs the
builds interleaved: base, change, base, … in alternating order. Each build is
a scratch copy of `HEAD` plus the harness, with or without the change. Small
differences (under about 1 ms in JIT mode, or about 5% overall) are noise.

### Request endpoints

Endpoint figures are medians in ms, averaged over two interleaved full runs.
"Base" is the code before this work; "after" has all changes.

| Endpoint | JIT base | JIT after | `-Xint` base | `-Xint` after |
|---|---:|---:|---:|---:|
| extensionInfo | 6.0 | 1.3 | 16.4 | 2.3 |
| sourcesManga | 2.8 | 0.9 | 15.4 | 2.0 |
| headersManga | 3.0 | 1.0 | 15.4 | 1.6 |
| filtersManga | 3.8 | 1.1 | 21.0 | 3.2 |
| supportLatestManga | 2.4 | 0.8 | 14.7 | 2.2 |
| preferencesManga | 2.4 | 0.8 | 16.0 | 2.0 |
| getPopularManga (1 upstream call) | 18.0 | 15.3 | 62.9 | 48.8 |
| getLatestManga (1) | 16.8 | 14.1 | 61.9 | 47.9 |
| getSearchManga (1) | 17.3 | 14.5 | 77.0* | 37.6 |
| getDetailsManga (1) | 15.4 | 14.1 | 68.5 | 53.3 |
| getChapterList (1, 300 chapters) | 17.3 | 15.6 | 205.5 | 184.0 |
| getPageList (1) | 13.3 | 13.8 | 30.7 | 17.5 |
| image proxy, page / poster (1) | 13.2 / 12.3 | 13.7 / 13.0 | 15.4 / 13.8 | 14.3 / 14.3 |
| health, capabilities | ~1 | ~1 | ~1 | ~1 |

\* The two base rounds measured 54 and 100 ms. In a clean round the base
was about 50 ms.

Each upstream call includes the fake upstream's fixed 10 ms latency. Under
`-Xint`, every Dalvik call used to pay about 13–14 ms of fixed overhead, and
that overhead is now about 1–2 ms.

### APK conversion (install)

Install times are in ms. Each build ran 3 times, with 1 first conversion and
9 later conversions per run.

| | JIT base | JIT after | `-Xint` base | `-Xint` after |
|---|---:|---:|---:|---:|
| first conversion in a process (3 samples) | 675 / 492 / 389 | 477 / 2148 / 557 | 1622 / 1192 / 1122 | 1192 / 1218 / 1120 |
| later conversions, median (n = 27) | 59 | 44 | 232 | 162 |
| later conversions, p90 | 105 | 91 | 454 | 372 |

The fixture APK is small, about 12 KB. Real extensions reference many more
host classes, so the host-class cache saves more for them than it does here.

### Startup to first request

No change was expected and none was measured beyond noise:

- JIT: base 525 and 543 ms; after 580 and 349 ms.
- `-Xint`: base 662 and 723 ms; after 915 and 629 ms.

### Ablation

Each column below removes one change from the full set. The figures are
`-Xint` medians in ms.

| | all changes | without shared mapper | without one-pass encoding | without host-class cache |
|---|---:|---:|---:|---:|
| extensionInfo | 2.3 | 13.8 | 2.0 | 1.9 |
| filtersManga | 3.2 | 18.4 | 2.1 | 1.8 |
| getPageList | 17.5 | 29.1 | 17.8 | 16.2 |
| getChapterList | 184.0 | 185.5 | 182.5 | 176.7 |
| later conversions (median) | 162 | — | — | 208 |

The full runs could not resolve the encoding change, so it got a targeted
A/B. That run used 3 interleaved rounds of 30 iterations each, under
`-Xint`:

| Endpoint | with one-pass encoding | without |
|---|---:|---:|
| getChapterList (≈39 KB JSON) | 169.7 / 170.8 / 168.7 | 177.2 / 176.1 / 177.0 |
| getPopularManga (≈10 KB) | 43.0 / 43.7 / 43.0 | 45.3 / 45.0 / 45.0 |
| filtersManga (≈1.7 KB) | 1.7 / 1.7 / 1.7 | 2.0 / 2.0 / 2.0 |

A microbenchmark under `-Xint` agrees with the A/B. NanoHTTPD's US-ASCII
`canEncode` plus a second encode costs about 0.18 ms per KB. The single
UTF-8 encode with a byte check costs about 0.011 ms per KB.

## Optimizations

### 1. One shared Jackson mapper

**Change.** `DalvikHandler` and `YouTubeHandler` now share
`JsonResponses.mapper` instead of building a mapper per handler, which meant
per request.

**Why it is faster.** A new `ObjectMapper` with the Kotlin module re-runs
reflection-based introspection of every serialized and deserialized type,
including Kotlin metadata parsing. Under the interpreter that cost about
13 ms on every request.

**Why it is safe.** A configured mapper is thread-safe, and no code mutates
its configuration after creation.

**Tests.** `JsonResponsesTest` checks that the shared mapper writes exactly
the same JSON as a fresh mapper. It covers every response type the handlers
emit:

- manga pages, chapters, pages and filter lists;
- preference maps, error maps and toasts;
- scalars and null.

It runs the check twice, so the second pass hits warm caches. It also checks
that `DataBody` requests read identically.

**Logger.** `DalvikHandler`'s logger moved to a companion object so it is not
recreated per request. kotlin-logging derives the same logger name, because
it strips everything after `$`.

### 2. Encoding JSON responses once

**Change.** `NanoHTTPD.newFixedLengthResponse(status, mime, text)` checked the
whole text with a US-ASCII `CharsetEncoder` before encoding it.
`JsonResponses.fixedLength` encodes to UTF-8 once and derives the same
decision from the bytes.

**How the check works.** The text is US-ASCII exactly when there is one byte
per character and no byte is a replacement `?` for a character that was not
`?`. An unpaired surrogate encodes to `?`. A non-ASCII text still gets
`; charset=UTF-8` and UTF-8 bytes, exactly as before.

**Tests.** `JsonResponsesTest` compares the complete wire output, minus the
`Date` header, against NanoHTTPD's own method. The comparison runs with gzip
on and off, for several statuses, and for these texts:

- ASCII and `?`;
- Latin-1, CJK and emoji;
- lone surrogates, control characters;
- a 70K-character body.

A second test checks the ASCII decision against `canEncode` for every `char`
from U+0000 to U+FFFF.

### 3. Process-wide cache of host class metadata during conversion

**Change.** `BytecodeEditor`'s `ClassHierarchy` resolves classes that are not
in the extension, such as Android compatibility, Kotlin and JDK classes. It
used to read their bytecode with `getResourceAsStream` on every lookup in
every conversion. These classes come from the server's own immutable class
path. The parsed name, superclass, interfaces and flags are now cached
process-wide in a `ConcurrentHashMap`.

**What is unchanged.** Extension classes are still resolved first from the
conversion's own map. Each lookup is still recorded in that per-conversion
map, because later candidate searches iterate it in insertion order.

**Tests.** `BytecodeEditorEquivalenceTest` runs `fixAndroidClasses` on a
committed raw dex2jar output of the fixture
(`server/src/test/resources/perf-fixture-dex2jar.jar`). It requires the
SHA-256 of every output entry (name, size and bytes, in order) to equal the
digest recorded from the code before the change. The test runs twice to
cover the warm cache.

Whole-APK conversion cannot be pinned this way. dex2jar's register
allocation is not deterministic between runs, even with unchanged code.

### 4. A shortcut for `setUrlWithoutDomain`

**Change.** `HttpSource` and `AnimeHttpSource` implement Mihon's
`getUrlWithoutDomain` by building a `java.net.URI` and joining its path,
query and fragment. Extensions call it for every manga, chapter, anime and
episode they parse, and under the interpreter the `URI` parse took about
32 µs per URL. `mextensionserver.util.urlWithoutDomain` now handles common,
unambiguous inputs directly and passes everything else to the original
code, which is kept verbatim.

**When the shortcut applies.** The input must start with `/` (but not `//`)
or with `scheme://` followed by a non-empty authority. Every character must
be one that `URI` accepts unescaped in an authority, path, query and
fragment alike, with at most one `#`. So there is no `%`, no `[` or `]`, no
space and no non-ASCII character. For such inputs `URI` cannot fail and
decodes nothing, so the joined result is exactly the text after the
authority. That is what the shortcut returns.

**Tests.** `UrlWithoutDomainTest` compares the outcome of the new function
with a verbatim copy of the old one: the result, or the exception class. It
covers about 40 hand-picked edge cases, including IPv6, empty authorities,
`//`, opaque URIs, escapes, a double `#` and bad schemes. It also covers
200,000 seeded random inputs built from risky prefixes and characters; the
shortcut handles over 30,000 of them. Each of these mutations fails the
test:

- accepting `%`;
- accepting `[` and `]`;
- dropping the single-`#` rule;
- accepting an empty authority;
- accepting `//`;
- dropping the scheme's first-letter check;
- dropping the fragment in the fallback.

**Figures.**

- Microbenchmark, `-Xint`, 300 chapter URLs of the fixture's shape: `URI`
  9.6 ms, shortcut 1.5 ms. JIT: both are well under 1 ms.
- Harness, `-Xint`, iOS JAR, 8 interleaved rounds, mean of medians:
  - `getChapterList` (300 chapters): 178.2 → 169.6 ms. The paired median
    difference is −9.4 ms, and the change was faster in 6 of 8 rounds.
  - `getPopularManga` (48 entries): 45.6 → 44.3 ms. That matches the expected saving of about 1.3 ms, but it is within noise.
- JIT: no difference beyond noise (`getChapterList` 16.6 vs 16.7 ms).

The gain grows with list length; real chapter lists often have more than
1,000 entries.

### Response equivalence end to end

With `-Pperf.dump`, the harness writes every normalized response. The dumps
cover status, headers, body, the extension id header and image digests. The
base and every variant produced identical dumps, in both modes, except for
one known case: the order of `isIncluded`, `isExcluded` and `isIgnored` in
TriState filters.

That order varies between server processes in the base build as well. In
5 full base runs, 1 produced a different order. These properties come from
methods, and `Class.getDeclaredMethods` has no specified order: HotSpot sorts
methods by internal symbol address. The order is fixed within a process,
which was already true with a mapper per request. It is not affected by
these changes.

## Measured but not changed

| Item | Finding | Why unchanged |
|---|---|---|
| Image proxy builds an OkHttp client per fetch (`newBuilder().build()`) | Processing time for page images equals poster images, which build none: about 2–5 ms under `-Xint` | Negligible |
| NanoHTTPD writes POST bodies over 1 KB to a temp file | About 0.2% of request samples | Negligible |
| Gzip of JSON responses | Real cost for large chapter lists | Turning it off or changing it alters response headers |
| Extension parsing (Jsoup, ICU `SimpleDateFormat`) | Dominates `getChapterList` under `-Xint`: see the profile below | Library code. `setUrlWithoutDomain`, which is server-owned, is optimization 4 |
| Persisting converted JARs across launches | Would remove repeat conversion entirely | Adds persistent state, invalidation and stale-cache risk; a new side effect |
| `ClassHierarchy` runs ASM `Analyzer` passes 5 times per class (`findReturnTypeRedirects`, `findArgumentTypeRedirects` ×3, `collectLazyValueTypes`) | The largest server-owned share of conversion after the cache (`findArgumentTypeRedirects` alone about 13%) | Reusing frames means holding every method's frames for the whole APK at once, a large memory cost on iOS. Each pass also depends on redirects found by the previous one. |
| Remaining conversion time | About 46% dex2jar, about 7% asset extraction | Library code |
| `setPreferences` file writes, per-request handler construction | Small | Required side effect, or cheap |

### Where steady-state request time goes

A JFR profile of `getChapterList`, `getDetailsManga` and `getPopularManga`
under `-Xint` (iOS JAR, 3,910 samples, full stacks) shows where the time
goes:

| Share of request samples | Code |
|---:|---|
| ~94% | extension work inside `MihonInvoker`: Jsoup parsing and selection, ICU date parsing, `setUrlWithoutDomain` (before optimization 4, 8%) |
| 1.6% | `DataBody` read |
| 1.0% | response serialization |
| 0.5% | `JsonResponses.fixedLength` |
| 0.3% | NanoHTTPD response writing |
| ~1% | OkHttp interceptor chain |

Within Jsoup, about 14% of samples parse CSS queries again on every
`select`/`selectFirst` call: Jsoup 1.21.2 has no query cache. About 7% clear
a 2K-character buffer with `Arrays.fill` in `CharacterReader.close`, which
is not intrinsified without a JIT. Both are inside the Jsoup library that
extensions rely on, so they are left alone. A query cache would also share
mutable evaluator state across threads.

### Background JSON warm-up (measured, not adopted)

The first `DataBody` read in a process costs about 390 ms under `-Xint`,
and creating the mapper costs about 140 ms. Most of that is Jackson
deserializer construction and kotlin-reflect initialization for `DataBody`
and every nested type. A variant ran this read on a daemon thread once the
server was listening, and the first request waited for it. Equivalence and
mutation tests were written for it.

Interleaved, iOS JAR, 5–6 rounds × 5 fresh processes, medians in ms:

| Metric | `-Xint` base | `-Xint` warm-up | JIT base | JIT warm-up |
|---|---:|---:|---:|---:|
| startup to first request | 407 | 485–490 | 278–282 | 316–340 |
| startup to first extension response | 1266–1267 | 1258–1270 | 656–695 | 640–709 |
| install in a cold process | 857–862 | 766 | 378–386 | 322–325 |

The APK request itself got about 90 ms faster, but `/capabilities` got
about 80 ms slower. Its class loading competes with the warm-up thread. The
time Mangatan waits for, from spawn to the first extension response, did
not improve. Starting the warm-up on the first request instead was worse.
The change was removed.

## Bottleneck assessment

After optimization 4, nothing server-owned is a large share of a common
flow. The figures below are medians from the iOS JAR.

| Flow | `-Xint` | JIT | Where the time goes |
|---|---:|---:|---|
| Spawn to first `/capabilities` | ~405 ms | ~275 ms | JVM boot and class loading; the server's own start-up is tiny |
| Spawn to first extension response | ~1,260 ms | ~650 ms | the above, plus the cold install below |
| Install in a cold process | ~860 ms | ~380 ms | first `DataBody` read (Jackson and kotlin-reflect, ~390 ms `-Xint`), dex2jar, `BytecodeEditor`, first class loads of the extension |
| Later conversions | ~150 ms | ~25 ms | about half dex2jar; `BytecodeEditor`'s analyzer passes are the largest server-owned part (see the table above) |
| Listing, details, chapter list | 35–170 ms | 14–17 ms | ~94% extension work in Jsoup and ICU; server code ~3% |
| Page list, image proxy | 14–17 ms | 13–14 ms | mostly the fake upstream's 10 ms latency |

The first-read cost is library initialization that a background warm-up
could only move, not remove (see above). Conversion is a one-off per APK
per process, and loaded extensions survive iOS suspension.

**Class-data sharing (AppCDS): measured, recorded as an idea.** A static
archive built from the server's own class list roughly halves start-up on
the desktop JDK. These are 2 rounds of 5 fresh processes, measured from
spawn to the first `/capabilities`:

| | without archive | with archive |
|---|---:|---:|
| JIT | 272–290 ms | 135–136 ms |
| `-Xint` | 403–409 ms | 217–221 ms |

It is not adopted, for three reasons:

- An archive is tied to the exact JVM binary that dumps it. The iOS runtime
  is a static OpenJDK Zero build started by Mangatan's native bridge, so a
  dump would have to run on the device and be stored in the app container.
  That adds persistent state and invalidation, plus VM options in the other
  repository.
- CDS support in the mobile Zero port is unverified.
- The bundled JBR ships no base archive, so the desktop figures include JDK
  classes that a stock JDK would already share.

For desktop launches, `-XX:+AutoCreateSharedArchive
-XX:SharedArchiveFile=<file>` (JDK 19+) could be added to the launcher
later. It writes a cache file, which is a new side effect.

## Findings outside performance

- **Generated kotlinx serializers.** The generated `$serializer` classes call
  interface default methods with `invokespecial`. After d8 and dex2jar,
  loading them fails with `IncompatibleClassChangeError … must be
  InterfaceMethodref`. The fixture therefore avoids `@Serializable` classes.
  Released extensions load, so this seems specific to the fixture's
  toolchain. It was not investigated further or changed here.
- **Fixture min-api.** A d8 build at min-api 21 desugars default methods into
  `$-CC` companions that the server cannot resolve. The fixture uses
  min-api 24.
