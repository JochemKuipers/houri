<!--
Curated release notes for the NEXT version. Append user-facing bullets here as
work lands, grouped under the headings below, then bump versionName and push.
CI renders this into the release body, and `--cut` archives it into
CHANGELOG.md and clears it after the release.

Write for someone deciding whether to update: what changed for them, not which
files moved. See .github/scripts/release_notes.py.
-->

### New

- **Find duplicates:** the library overflow menu now has a **Find duplicates** screen listing every entry that has more than one copy, with how many each one has. Tap one to open the existing duplicate review, where you can open either side or jump straight to migration. Matches on shared titles and on the same entry being tracked on the same tracker
- **FlareSolverr support:** if a site's Cloudflare check defeats the built-in solver, point Houri at your own FlareSolverr instance (Advanced -> Network), turn it on, and it will use that first - retrying with the user agent the challenge was solved under. There is a **Test FlareSolverr and update user agent** action that solves a probe page so you can confirm the setup works, and a banner naming the host and method while a challenge is being worked around. Falls back to the built-in solver whenever the instance is unreachable, and resets the user agent if it stops answering
- **Per-category update schedules:** a category can be given its own update interval — daily, every few days, weekly, fortnightly, or monthly — from the category manager, alongside the status binding above. Entries in that category stop being checked on every global run until their interval has elapsed, and where an entry sits in several categories the shortest interval wins so a daily request is never overridden by a monthly one. Categories left on the default keep following your global library update setting exactly as before
- **Smart categories:** a category can now be bound to a tracker status from the category manager, and entries move between those categories on their own — finish chapter 1 on a remote tracker and the entry leaves *Wish to read* and lands in *Reading* on its own, and finishing the last chapter moves it to *Completed*. Categories you have not bound are never touched, so a folder you happen to have named *Reading* is left alone. This follows a status change however it happens: while you read, when you change the status yourself, or when a tracker refresh picks up a change you made elsewhere

### Improve

### Fix

- **Smart scanlator merge:** chapters hidden by a merge no longer show up in the reader or get reached with next/previous — and resuming a hidden chapter from history no longer fails to open
- **Manga covers:** the full-screen cover view now updates the moment you replace a cover, instead of sitting on the previous image until you reopen it
- **Long strips (manhua):** a page too tall to decode is no longer split into a strip with a missing chunk when memory runs out mid-split — the whole image is used instead, which is slower but correct, instead of a scrambled page that looked fine
- **WebGPU reader:** if the GPU is lost while the app is in the background, the reader now retries rebuilding it instead of giving up on the first attempt — which used to leave every page broken for the rest of the session
- **Chapter loading:** pages are no longer fetched twice. The page queue never de-duplicated and two workers drained it at once, so most pages in a chapter were downloaded two or more times — doubling data use, and pushing fragile sources into failures that caused yet more retries
- **WebGPU reader:** pages no longer stick on a blank placeholder when the GPU device is lost — recovery now asks for a fresh GPU adapter instead of reusing the one the lost device already consumed, so the reader picks back up instead of failing every page decode for the rest of the session
- **WebGPU reader:** a page whose image could not be read is no longer able to wedge the reader for the whole session — a second attempt now discards the cached copy and fetches it again, and a page whose download silently dies is recovered after 30 seconds instead of hanging forever with no indication anything went wrong
- **WebGPU reader:** double-page mode keeps its height matching for spreads whose partner is slow to load, instead of giving up after a fraction of a second
- **WebGPU reader:** low memory now also releases the tile and filter caches, not just decoded pages — on a device with under 2 GB of RAM the next frame no longer has to rebuild them from scratch
- **WebGPU reader:** dual-page mode no longer risks drawing a spread whose textures were already freed, and the loading pineapple no longer keeps showing a texture bound to a GPU device that has been lost
- **Tracker manager:** the logout icon no longer overlaps the logged-in checkmark and the priority star — it now appears only while you're actually swiping a row, and steps aside for those icons the rest of the time
- **Category sorting:** sorting categories by **Most items** or **Least items** now reorders the library's category tabs too, instead of quietly falling back to your manual order while the management screen showed a different one
- **Search:** a new **Exact author** toggle narrows search results to entries whose author or artist is the name you searched for, so looking up *John Smith* no longer turns up *John Smithson*. Sources decide what to return, so this filters what they returned — if a source never fills in creator names, nothing will match until it does. Off by default; search works exactly as before
- **Storage:** downloaded update APKs now clean themselves up. The APK left in the app's cache was never removed, so it survived every update and kept occupying tens of megabytes; it is now deleted once the version it represents is the one you're running, and an update you download and then never install is dropped after a week
