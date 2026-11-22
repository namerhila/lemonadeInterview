# Word Counter Service: Plan

## 1. Overview
A Spring Boot 4 / Java 21 service with two endpoints. `POST /words/` validates a URL, deduplicates it and starts a background job, then returns 202 right away. The job streams the file, tokenizes it, checks that it's English, and merges the counts into an in-memory map in one atomic step. `GET /words/{word}` returns the count.
The work is split into 4 vertical slices. Each one is test-first and ends with a green build and passing curl checks.

```
src/main/java/com/hila/lemonadeinterview/
  LemonadeInterviewApplication.java   # + @Bean ExecutorService (4 workers)
  controller/WordController.java      # HTTP only: parse, validate, map to 200/202
  error/BadRequestException.java
  error/GlobalExceptionHandler.java   # @RestControllerAdvice -> 400 {"error": ...}
  service/WordService.java            # lookup, submit (dedup), background job, logging
  service/WordCounter.java            # streaming tokenizer + English check -> local Map
  service/NotEnglishException.java
  repository/WordRepository.java      # counts map + processed/inProgress sets, one lock
src/test/java/com/hila/lemonadeinterview/
  WordGetTest.java  WordPostTest.java  WordRepositoryTest.java
  WordCounterTest.java  WordJobTest.java  TestFileServer.java
```

## 2. Dependencies to approve / Open questions
**Dependencies to approve: none.** Everything comes from the JDK or is already in `pom.xml`:
- Download: JDK `java.net.http.HttpClient`, which follows redirects and supports `connectTimeout`.
- JSON: Jackson, which `spring-boot-starter-webmvc` already includes.
- Tests: JUnit 5 and MockMvc, already included. To serve fixture files in tests, use the JDK's `com.sun.net.httpserver.HttpServer`. To wait for jobs, use a small polling helper instead of Awaitility.
- Manual curl fixtures: JDK `jwebserver` (Java 18+).

**Open questions**
1. **An encoded `/` (`%2F`) in GET.** Tomcat rejects `%2F` in the path by default, and Spring decodes the path before matching it. Proposal: treat words that contain `/` as a known limitation. All the other characters (`?`, `#`, `%`, `^`, `$`, `*`, `.`) work when they're encoded.
2. **The 60s read timeout.** `HttpClient` has no per-read timeout. Proposal: use `HttpRequest.timeout(60s)`, which bounds the wait for response headers. A body stream that stalls later isn't bounded.

## 3. TDD rules (every slice)
- Write the slice's tests first, run them, and confirm they fail for the expected reason. Then write the code that makes them pass.
- Never delete or change a test to make it pass. If a test fails, fix the code.
- A slice is done only when `./mvnw test` is fully green (including earlier slices) and its curl checks give the expected output. Don't start the next slice before that.
- The tests share one Spring context, so each test uses its **own unique words and URLs**.

Run the app with `./mvnw spring-boot:run` (port 8080). Fixture files for the curl checks are served with `jwebserver -d /tmp/wc -p 8000`.

---

## Slice 1: Skeleton, error handler, repository, GET
**Goal:** `GET /words/{word}` validates the word and returns its count, using the central 400 handler.

**Files:** `WordController`, `BadRequestException`, `GlobalExceptionHandler`, `WordService`, `WordRepository`, `WordGetTest`.

**Tests first (`WordGetTest`, MockMvc; seed the counts with `repository.addCounts(Map)`)**
- `unknownWord_returnsZero`: `/words/zzqq` → 200 `{"word":"zzqq","count":0}`
- `lookupIsCaseInsensitive`: seed `casecheck=2` (not `country`, which slice 3 counts). `/words/COUNTRY` and `/words/country` both → 2
- `encodedRegexWord_isDecoded`: seed `^a.*b$=1`. `/words/%5Ea.*b%24` → 1
- `word30Bytes_ok` / `word31Bytes_returns400`: 30 vs 31 × `a`. Also 16 Hebrew letters (32 bytes, 16 chars) → 400
- `emptyWord_returns400`: `GET /words/` → 400 `{"error":...}`
- `wordWithSeparator_returns400`: `a-b`, `a,b`, `a%20b`, `a%09b` → 400

**Implementation**
1. `WordRepository`: `HashMap<String,Long> counts`. Every method is `synchronized` on one lock, so a file's counts become visible all at once. Methods: `count(word)` and `addCounts(Map)`.
2. `WordService.count(word)`: lowercase the word, then call `repository.count`.
3. `WordController`: `@GetMapping("/words/{word}")` checks that the word isn't empty, is at most 30 UTF-8 bytes, and has no whitespace, `-` or `,`. If a check fails, it throws `BadRequestException`. It returns `{"word":<lowercased>,"count":N}`. A separate `@GetMapping("/words/")` always throws `BadRequestException("word is empty")`.
4. `GlobalExceptionHandler`: maps `BadRequestException` to 400 `{"error": msg}`.

**curl**
```
curl -i localhost:8080/words/country            # 200 {"word":"country","count":0}
curl -i localhost:8080/words/COUNTRY            # 200 {"word":"country","count":0}
curl -i localhost:8080/words/%5Ea.*b%24         # 200 {"word":"^a.*b$","count":0}
curl -i localhost:8080/words/$(printf 'a%.0s' {1..31})  # 400 {"error":"..."}
curl -i localhost:8080/words/a-b                # 400
curl -i localhost:8080/words/                   # 400
```
**Done when:** ☐ all tests are green ☐ the curl output matches ☐ no controller builds an error body itself

---

## Slice 2: POST /words/, synchronous part
**Goal:** `POST /words/` validates the input, deduplicates atomically and returns 202 or 200 skipped. The job is still a no-op stub.

**Files:** `WordController`, `GlobalExceptionHandler`, `WordService`, `WordRepository`, `LemonadeInterviewApplication`, `WordPostTest`, `WordRepositoryTest`.

**Tests first**
- `WordRepositoryTest.tryStart_concurrentSameUrl_onlyOneWins`: 50 threads call `tryStart(u)` on the same URL. Exactly 1 returns `true`.
- `WordRepositoryTest.tryStart_processedOrInProgress_returnsFalse`
- `WordPostTest.validUrl_returns202`: `{"url":"http://x.test/a"}` → 202 `{"status":"accepted","url":"http://x.test/a"}`
- `WordPostTest.inProgressUrl_returns200Skipped`: seed with `repository.tryStart(u)`. POST → 200 `{"status":"skipped",...}`
- `WordPostTest.processedUrl_returns200Skipped_countsUnchanged`: seed with `tryStart` + `complete(u, {w:1})`. POST → skipped, and GET `w` is still 1.
- `WordPostTest.urlIsTrimmed`: seed `http://x.test/t`. POST `"  http://x.test/t  "` → skipped
- `WordPostTest.invalidBody_returns400` (parameterized): not JSON, `{}`, `{"url":""}`, `{"url":"   "}`, `{"url":"not a url"}`, `{"url":"ftp://x.test/a"}`, `{"url":"http://"}` → 400 `{"error":...}`

**Implementation**
1. `WordRepository`: add the `processed` and `inProgress` sets. `synchronized boolean tryStart(url)` returns false if the URL is in either set; otherwise it adds the URL to `inProgress` and returns true. Add `complete(url, Map)`, which merges the counts and moves the URL from `inProgress` to `processed`, and `fail(url)`, which removes it from `inProgress`.
2. Application: `@Bean ExecutorService wordJobs()`, `Executors.newFixedThreadPool(4)`.
3. `WordService.submit(url)`: if `tryStart` returns true, submit `runJob(url)` (an empty stub) to the executor and return true.
4. `WordController`: `@PostMapping("/words/")` takes `record UrlRequest(String url)`. It trims the URL, parses it with `URI`, and requires a non-blank string, an `http` or `https` scheme and a host. Otherwise it throws `BadRequestException`. It returns 202 or 200 with `{"status":..., "url":<trimmed>}`.
5. `GlobalExceptionHandler`: also maps `HttpMessageNotReadableException` (bad JSON) to 400.

**curl**
```
curl -i -XPOST localhost:8080/words/ -H 'Content-Type: application/json' -d '{"url":"https://norvig.com/big.txt"}'  # 202 {"status":"accepted",...}
curl -i -XPOST localhost:8080/words/ -H 'Content-Type: application/json' -d '{"url":"https://norvig.com/big.txt"}'  # 200 {"status":"skipped",...}
curl -i -XPOST localhost:8080/words/ -H 'Content-Type: application/json' -d '{bad'                                   # 400
curl -i -XPOST localhost:8080/words/ -H 'Content-Type: application/json' -d '{"url":"ftp://a.b/c"}'                   # 400
```
**Done when:** ☐ the tests from slices 1–2 are green ☐ the curl output matches

---

## Slice 3: Background job (streaming, tokenizing, merging)
**Goal:** the real job streams the file, counts into a local map, and merges atomically. On failure, it frees the URL and logs the error.

**Files:** `WordCounter`, `WordService`, `WordCounterTest`, `WordJobTest`, `TestFileServer` (JDK HttpServer with fixed paths, plus `/slow`, `/404` and `/broken`).

**Tests first**
- `WordCounterTest` (calls `count(Reader, chunkSize)`):
  - `dashAndCommaSplit`: `Count-ry a,b` → `count=1, ry=1, a=1, b=1`
  - `lowercases`: `Country COUNTRY` → `country=2`
  - `keepsNumbersRegexPunctuation`: `42 3.14 ^a.*b$ \d+ country. country` → each counted separately (a regex with `-` is split, per spec rule 2.1)
  - `dropsEmptyTokens`: `a--b ,,` → `a=1, b=1`
  - `crlfAndTabsAreWhitespace`: `a\r\nb\tc` → 3 words
  - `wordAcrossChunkBoundary`: chunkSize=3, `country country` → `country=2`
  - `lastWordWithoutTrailingNewline`: `x y` → `y=1`
  - `emptyOrSeparatorsOnly_returnsEmptyMap`: `""` and ` -,\n` → `{}`
- `WordJobTest` (`@SpringBootTest` + MockMvc + `TestFileServer`; `awaitCount(word, n)` polls GET every 50ms for up to 5s):
  - `countryTwice_getReturns2`: file `country country` → `/words/country`=2
  - `mixedCase_endToEnd`: file `Mixcase MIXCASE` → `/words/MiXcAsE`=2
  - `numbersAndRegex_lookupEncoded`: file `4242 ^q.*z$` → `/words/4242`=1, `/words/%5Eq.*z%24`=1
  - `post_returns202BeforeDownloadFinishes`: `/slow` waits 3s. The POST answers in under 1s.
  - `unreachableOr404_canRetry`: `http://localhost:1/x` and `/404` → 202. Poll the POST until it returns 202 again (the URL was freed).
  - `brokenStream_mergesNothing`: `/broken` sends part of the body, then the handler throws so the server drops the connection. The URL becomes retryable, and its words stay 0.
  - `concurrentDifferentUrls_noLostCounts`: 10 URLs, each with `race` ×1000, POSTed in parallel → `/words/race`=10000

**Implementation**
1. `WordCounter.count(Reader, chunkSize)`: read `char[chunkSize]`, replace `-` and `,` with spaces, prepend the leftover tail, split on whitespace, and keep the last token as the new tail if the chunk doesn't end with whitespace. Lowercase each token and add it to a local `HashMap`. At EOF, count the tail.
2. `WordService.runJob(url)`: use one shared `HttpClient` (redirects `NORMAL`, connect timeout 10s) and `HttpRequest.timeout(60s)` with `BodyHandlers.ofInputStream()`. A non-2xx status throws. Wrap the body in `InputStreamReader(UTF_8)` and call `WordCounter.count(reader, 8192)`. On success, call `repository.complete(url, map)`. On any exception, call `repository.fail(url)` and `log.warn("job failed url={} reason={}", url, e.toString())`.
3. Never buffer the whole body. Memory then grows with the number of distinct words, not the file size.

**curl** (`mkdir -p /tmp/wc; printf 'country country\nCount-ry a,b 42 ^a.*b$' > /tmp/wc/t.txt`, then start `jwebserver`)
```
curl -i -XPOST localhost:8080/words/ -H 'Content-Type: application/json' -d '{"url":"http://localhost:8000/t.txt"}'  # 202
curl -s localhost:8080/words/country     # {"word":"country","count":2}
curl -s localhost:8080/words/ry          # {"word":"ry","count":1}
curl -s localhost:8080/words/%5Ea.*b%24  # {"word":"^a.*b$","count":1}
curl -i -XPOST localhost:8080/words/ -H 'Content-Type: application/json' -d '{"url":"http://localhost:8000/t.txt"}'  # 200 skipped; counts unchanged
curl -i -XPOST localhost:8080/words/ -H 'Content-Type: application/json' -d '{"url":"http://localhost:1/x"}'          # 202; log shows "job failed"; repeat → 202
curl -i -XPOST localhost:8080/words/ -H 'Content-Type: application/json' -d '{"url":"https://norvig.com/big.txt"}'    # 202 right away
curl -s localhost:8080/words/the         # after a few seconds: large count (tens of thousands)
```
**Done when:** ☐ the tests from slices 1–3 are green ☐ the curl output matches ☐ big.txt is counted, and the heap stays small (check with `jcmd <pid> GC.heap_info` while it's running)

---

## Slice 4: English-only check
**Goal:** reject content that isn't English or isn't valid UTF-8. Nothing is merged, and the URL can be retried.

**Files:** `WordCounter`, `NotEnglishException`, `WordService`, `WordCounterTest`, `WordJobTest`, `TestFileServer`.

**Tests first**
- `WordCounterTest`:
  - `hebrewText_throwsNotEnglish`, `russianText_throwsNotEnglish`
  - `fewAccentedWords_accepted`: about 200 English words plus `café naïve` → counted, and `café`=1
  - `noLettersAtAll_accepted`: `42 3.14 ^$ []` → counted
  - `exactlyOnePercent_accepted` / `overOnePercent_rejected`: 1 vs 2 non-Latin letters in 100 letters
  - `invalidUtf8_throws`: bytes `FF FE 00 C3` → `CharacterCodingException`
- `WordJobTest`:
  - `hebrewFile_202_countsUnchanged_canRetry`: a Hebrew file with the unique word `qqheb` → 202. Poll the POST until it returns 202 again. GET `/words/qqheb` stays 0.
  - `binaryFile_202_countsUnchanged_canRetry`: same, with random bytes
  - `accentedFile_counted`: ~800 letters of plain English plus `the café is nice` → `/words/caf%C3%A9`=1 (a short text alone would be over 1%)

**Implementation**
1. In `WordCounter`: for each char, if it's a letter (`Character.isLetter`), increment `letters`. If it's a letter that isn't `a-z` or `A-Z`, also increment `nonLatin`. Once `letters ≥ 10000` and `nonLatin*100 > letters`, throw `NotEnglishException` early. At EOF, apply the same `> 1%` check.
2. `WordService`: build the reader from `StandardCharsets.UTF_8.newDecoder().onMalformedInput(REPORT).onUnmappableCharacter(REPORT)`, so invalid UTF-8 throws.
3. The job's existing catch block already calls `fail(url)` and logs. Log `NotEnglishException` as `"422 not English"`.

**curl** (`printf 'שלום עולם זה טקסט' > /tmp/wc/he.txt; head -c 4000 /dev/urandom > /tmp/wc/bin.dat; { for i in $(seq 40); do printf 'the quick brown fox jumps '; done; printf 'the café is nice'; } > /tmp/wc/fr.txt`). `fr.txt` needs the padding: `the café is nice` alone is 1 accented letter in 13 (7.7%), which is rejected.
```
curl -i -XPOST localhost:8080/words/ -H 'Content-Type: application/json' -d '{"url":"http://localhost:8000/he.txt"}'   # 202; log "422 not English"; repeat → 202
curl -s localhost:8080/words/%D7%A9%D7%9C%D7%95%D7%9D                                                                     # count 0
curl -i -XPOST localhost:8080/words/ -H 'Content-Type: application/json' -d '{"url":"http://localhost:8000/bin.dat"}'  # 202; log shows a decoding error; repeat → 202
curl -i -XPOST localhost:8080/words/ -H 'Content-Type: application/json' -d '{"url":"http://localhost:8000/fr.txt"}'   # 202
curl -s localhost:8080/words/caf%C3%A9   # {"word":"café","count":1}
```
**Done when:** ☐ the tests from slices 1–4 are green ☐ the curl output matches ☐ `./mvnw test` passes before the demo

---

## 4. Coverage map
| Spec item | Slice | Test / check |
|---|---|---|
| AC: valid POST → 202 right away | 3 | `WordJobTest.post_returns202BeforeDownloadFinishes` |
| AC: `country country` → 2 | 3 | `WordJobTest.countryTwice_getReturns2` |
| AC: `Count-ry`, `a,b` split | 3 | `WordCounterTest.dashAndCommaSplit` |
| AC: GET `COUNTRY` = GET `country` | 1 | `WordGetTest.lookupIsCaseInsensitive` |
| AC: numbers and regex counted, encoded lookup | 3 | `WordJobTest.numbersAndRegex_lookupEncoded` (decoding: `WordGetTest.encodedRegexWord_isDecoded`) |
| AC: same URL twice → skipped, counts unchanged | 2 | `WordPostTest.inProgressUrl_returns200Skipped`, `processedUrl_returns200Skipped_countsUnchanged` |
| AC: big.txt streamed and counted | 3 | curl check for big.txt + heap check |
| AC: word across chunk boundary | 3 | `WordCounterTest.wordAcrossChunkBoundary` |
| AC: Hebrew/Russian → 202, logged, map unchanged, retryable | 4 | `WordJobTest.hebrewFile_202_countsUnchanged_canRetry`, `WordCounterTest.russianText_throwsNotEnglish` |
| AC: binary/non-UTF-8 → same | 4 | `WordJobTest.binaryFile_202_countsUnchanged_canRetry`, `WordCounterTest.invalidUtf8_throws` |
| AC: accented English counted | 4 | `WordJobTest.accentedFile_counted`, `WordCounterTest.fewAccentedWords_accepted` |
| AC: unreachable URL → 202, logged, retryable | 3 | `WordJobTest.unreachableOr404_canRetry` |
| AC: invalid body / malformed URL → 400 | 2 | `WordPostTest.invalidBody_returns400` |
| AC: GET >30 bytes / empty → 400; unknown → 0 | 1 | `word31Bytes_returns400`, `emptyWord_returns400`, `unknownWord_returnsZero` |
| AC: concurrent POSTs don't lose counts | 3 | `WordJobTest.concurrentDifferentUrls_noLostCounts` |
| Edge: empty file / only separators | 3 | `WordCounterTest.emptyOrSeparatorsOnly_returnsEmptyMap` |
| Edge: no letters passes the English check | 4 | `WordCounterTest.noLettersAtAll_accepted` |
| Edge: exactly 1% limit | 4 | `exactlyOnePercent_accepted` / `overOnePercent_rejected` |
| Edge: same URL concurrently → one 202 (atomic) | 2 | `WordRepositoryTest.tryStart_concurrentSameUrl_onlyOneWins` |
| Edge: failure mid-stream merges nothing | 3 | `WordJobTest.brokenStream_mergesNothing` |
| Edge: `\r\n` and tabs | 3 | `WordCounterTest.crlfAndTabsAreWhitespace` |
| Edge: no trailing newline | 3 | `WordCounterTest.lastWordWithoutTrailingNewline` |
| Edge: mixed case end to end | 3 | `WordJobTest.mixedCase_endToEnd` |
| Edge: GET separators in word → 400 | 1 | `WordGetTest.wordWithSeparator_returns400` |
| Edge: URL trimmed before dedup | 2 | `WordPostTest.urlIsTrimmed` |
| Edge: restart loses everything | 1 | By design: everything lives in memory in `WordRepository` (no test) |
