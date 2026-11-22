# Word Counter Service — Spec

## 1. Overview
An HTTP service with two endpoints:
- `POST /words/` checks the request, accepts it, and counts the words of the URL's file **in the background**.
- `GET /words/{word}` returns how many times a word was counted.

Counts are stored in an in-memory hash map (`word -> count`) and are lost on restart. Counts add up across all URLs that were processed.

## 2. Tokenization rules (shared)
1. Change every `-` and `,` to a space. Example: `Count-ry` → `count`, `ry`.
2. Split on whitespace (spaces, tabs, `\n`, `\r`).
3. Lowercase every token, so `Country` and `COUNTRY` are the same word.
4. **Any non-empty token is a word.** That includes plain English words, numbers (`2024`, `3.14`) and regex-like strings (`^a.*b$`, `\d+`). Rule 1 still applies, so a regex with `-` or `,` is split (`[0-9]+` → `[0`, `9]+`). Other punctuation is kept, so `country.` and `country` are different words.
5. Drop empty tokens. Example: `a--b` → `a`, `b`.

## 3. English-only content
The rules above only make sense for English text, so content that isn't English is rejected (in the background job, see section 4).

- **Rule:** while streaming, count letters. Any character that is a Unicode letter but not `a-z` or `A-Z` counts as a non-English letter (for example Hebrew, Cyrillic, Chinese).
- If **more than 1%** of all letters are non-English, the content is rejected. A few accented words like `café` or `naïve` stay under the limit.
- If the body isn't valid UTF-8 (binary data, unknown encoding), it is also rejected.
- When content is rejected, nothing is merged into the global map and the URL is **not** marked as processed. The job logs the reason (status `422 not English`).
- You may stop early once at least 10,000 letters have been read and the ratio is already over 1%.

## 4. POST /words/

**Request:** `{"url": "https://norvig.com/big.txt"}` (one URL per request)

### 4.1 Synchronous part (fast, minimal validation)
1. Check the body: it must be valid JSON with a `url` string that is non-empty, well-formed, and uses http or https. If not → `400`.
2. Trim the URL. If it was already processed successfully, or is being processed right now → `200 skipped`. Don't fetch it.
3. Otherwise, mark the URL as **in progress**, start a background job, and return `202 Accepted` right away. The client doesn't wait for the file to download.

### 4.2 Background job
1. Download the body **as a stream** (in chunks or line by line). Never load the whole file into memory.
    - A word may be cut across two chunks. Keep the unfinished tail of each chunk and attach it to the next one.
    - Read until the end of the stream, then count the last word that's left over.
2. Run the English check from section 3 while streaming.
3. Count into a **local** map first. Only when the whole download succeeds and the content passes the English check, merge it into the global map in one thread-safe step. If anything fails, nothing is changed.
4. If it succeeds, move the URL from in progress to **processed**.
5. If it fails (unreachable, timed out, non-2xx, not English, not UTF-8), remove the URL from in progress so it can be retried, and **log** the error with the URL and the reason.
6. Jobs run on a small bounded worker pool (for example 4 workers), so many big files at once can't use up the server.

**Responses:**
| Case | Code | Body |
|---|---|---|
| Accepted for processing | 202 | `{"status":"accepted","url":...}` |
| Already processed or in progress | 200 | `{"status":"skipped","url":...}` |
| Bad JSON, missing, empty or malformed `url`, scheme not http/https | 400 | `{"error":...}` |

**Limits:** the download has a timeout (connect 10s, read 60s), and HTTP redirects are followed.

## 5. GET /words/{word}
1. URL-decode `{word}`. Regex characters such as `?`, `#`, `/` and `%` have to be URL-encoded by the client.
2. Check it:
    - It must not be empty after decoding.
    - It must be **30 bytes or less** in UTF-8.
    - It must not contain whitespace, `-` or `,`, because words with those can never be stored.
    - If any check fails → `400`.
3. Lowercase it, look it up, and return `200 {"word":..., "count":N}`. An unknown word returns `count: 0`.
4. While a file is still being processed, GET returns the counts from before that file. A file's counts appear all at once, when its job finishes.

## 6. Edge cases
- The file is empty, or has only separators → the job succeeds and adds 0 words.
- The file has numbers and symbols but no letters at all → it passes the English check (0 letters means 0% non-English) and is counted.
- Mostly English with a few accented words → under 1%, so it's accepted.
- Hebrew or Russian text, or binary content → the job fails and logs it, and the counts don't change.
- Two POSTs with the same URL at the same time → the first gets 202, the second gets `skipped`. The URL is marked in progress before the 202 is sent, with an atomic check-and-set.
- POSTs with different URLs at the same time → the global map stays thread-safe (mutex or a concurrent map).
- A URL whose job failed can be POSTed again and gets 202.
- The server restarts during a job → the job and all counts are lost (in memory only).
- Windows line endings (`\r\n`) and tabs are treated as whitespace.
- Very big files are streamed, so memory use depends on the number of distinct words, not the file size.
- A file with no trailing newline still has its last word counted.
- Mixed case: POST counts `Country COUNTRY`, then GET `/words/CoUnTrY` → 2.

## 7. Acceptance criteria
Tests that check counts must wait for the background job: poll GET until the expected value shows up, or wait on the job in unit tests.
- [ ] A valid POST returns `202` right away, without waiting for the download.
- [ ] POST a file with `country country`, and after the job ends, GET `/words/country` → `2`.
- [ ] `Count-ry` gives `count` 1 and `ry` 1. `a,b` gives `a` 1 and `b` 1.
- [ ] GET `/words/COUNTRY` and GET `/words/country` return the same count.
- [ ] Numbers and regex-like tokens (`42`, `^a.*b$`) are counted and can be looked up (URL-encoded).
- [ ] POSTing the same URL twice (while in progress or after it's done): the second response is `200 skipped` and counts don't change.
- [ ] `https://norvig.com/big.txt` (~6 MB) gets 202, is processed completely in the background, and isn't loaded into memory all at once. Then `/words/the` returns a large count.
- [ ] A word that spans a chunk boundary is counted correctly (unit test with a small chunk size).
- [ ] A URL with Hebrew or Russian text → 202. The job fails with a logged error, the global map doesn't change, and the URL can be POSTed again.
- [ ] A URL with binary or non-UTF-8 content → same as above.
- [ ] English text with a few accented words (`café`) → processed and counted.
- [ ] An unreachable URL → 202, the job logs the error, and the URL can be POSTed again.
- [ ] Invalid body or malformed URL → 400.
- [ ] GET with a word longer than 30 bytes → 400. Empty word → 400. Unknown word → `0`.
- [ ] Concurrent POSTs don't lose counts (race test).

## 8. Decisions / out of scope
- **Telling the client when processing finished, or that it failed, is out of scope.** There's no job status endpoint and no callbacks. Failures are only logged.
- Errors such as unreachable URL, non-2xx, or non-English content are no longer returned to the client. They happen in the background and are only logged.
- Punctuation other than `-` and `,` stays part of the word, so regex-like tokens survive.
- "English" is checked by script (Latin letters only), not by real language detection.
- Nothing is saved to disk, and there's no auth, rate limiting or delete endpoint. Jobs are not resumed after a restart.
- Two URLs are the same only if they match exactly after trimming. No further URL normalization.