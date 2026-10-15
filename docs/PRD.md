# Recipe Catcher — PRD

Sep 23, 2026 · @Angsuman Ray

What the app is for, who it serves and what v1 includes. Behaviour is in App spec, implementation in Tech spec, and build order in Waves.

## 1. Problem and goals

A personal Android app that turns a shared Instagram post or recipe website link into a clean, searchable, offline recipe card in under 10 seconds of user effort. The app is called Recipe Catcher.

**Problem.** Recipes on Instagram live in long captions, pinned comments or "recipe in comments" replies. Saving the post keeps the link but not the recipe. Finding it again means scrolling saved posts and re-reading messy captions. Recipe websites have the opposite problem: the recipe is buried under ads, pop-ups and long introductions.

**Goals**

- Capture a recipe from the Android share sheet or a pasted link in one flow.
- Extract a structured recipe (ingredients, steps, servings, time) with a cheap online LLM.
- Browse, search, filter and cook from recipes entirely offline, with data only on the phone.
- Plan the week, see what's due today, and shop from a list built from the plan and what's in stock.
- Suggest what to cook from my own recipes, based on mood and ingredients on hand, with optional new finds from the web.
- Never lose data: one-tap zip backup that can land in the Syncthing folder.

**Target user.** One person (the owner), one Android phone. Sideloaded APK, no Play Store listing, no accounts, no server.

## 2. User stories

P0 is what v1 can't do without and P1 rounds it out; both ship in v1. Build order comes from the Waves tab, not from priority.

| ID | As the owner, I want to… | Priority |
| --- | --- | --- |
| US-1 | share an Instagram post to the app and get a saved recipe | P0 |
| US-2 | paste a link inside the app when sharing isn't convenient | P0 |
| US-16 | share or paste a recipe website link and get the same kind of saved recipe | P0 |
| US-20 | share a YouTube video or Short and get the recipe from its description | P0 |
| US-27 | add a recipe by hand, by typing it, pasting its text or finding it with AI | P0 |
| US-3 | review and edit the extracted recipe before it is saved | P0 |
| US-4 | pick tags from my own list, with AI-suggested tags pre-selected | P0 |
| US-5 | see the original caption or page text kept alongside the parsed recipe | P0 |
| US-6 | search recipes by title, ingredient, caption text or my notes | P0 |
| US-7 | filter the library by tags, diet and "without" flags | P0 |
| US-8 | tick off ingredients and scale servings while cooking | P0 |
| US-22 | mark recipes as favourites and filter by them | P0 |
| US-23 | log each time I cook a recipe, with an optional note | P0 |
| US-28 | add a photo of my own attempt when I log a cook, and use it as the cover | P0 |
| US-24 | keep my own notes on a recipe | P0 |
| US-25 | see the equipment and consumables a recipe needs, and filter by equipment | P0 |
| US-26 | see how complex a recipe is, and correct it | P0 |
| US-29 | see and filter by spice level | P0 |
| US-30 | see and filter recipes that can be made ahead or frozen | P0 |
| US-9 | use a cooking mode that keeps the screen on, one step at a time | P1 |
| US-10 | back up everything to one zip file and restore it | P0 |
| US-11 | have failed captures kept as "pending" and retried later | P0 |
| US-21 | import my backlog of saved Instagram posts in one go | P1 |
| US-31 | plan the coming week with my own meal slots per day, different each week | P1 |
| US-32 | fill slots with things to cook, quick prep or leftover portions, and change them on the fly | P1 |
| US-33 | see "To Be Done Today" as the app's home screen and in a home-screen widget | P1 |
| US-38 | see and tick off "To Be Done Today" on my Wear OS watch | P1 |
| US-34 | get alerts for night-before prep and for when to start cooking | P1 |
| US-35 | ask the AI to suggest recipes for empty slots | P1 |
| US-36 | get a merged grocery list from the week's plan | P1 |
| US-37 | keep an inventory with in stock / low / out, so empty items land on the grocery list | P1 |
| US-12 | optionally include the top comment when the caption lacks the recipe | P1 |
| US-13 | re-run extraction on a saved recipe from its stored source text | P1 |
| US-14 | manage my tag, diet, equipment and consumables lists (add, rename, delete, merge) | P1 |
| US-15 | open the original post, page or video from the recipe | P0 |
| US-17 | tell a chat my mood and the ingredients I have, and get suggestions from my saved recipes | P1 |
| US-18 | let that chat also find new recipes on the web, and save one with a tap | P1 |
| US-19 | come back to earlier chats | P1 |
| US-39 | extract recipes from images (Instagram carousel slides 2–5, cookbook photos, handwritten notes, screenshots from TikTok/Pinterest/WhatsApp) using Claude Haiku 5.5 multimodal vision | P1 |
| US-40 | extract recipes from YouTube video timed-text captions/subtitles when the description lacks recipe text | P1 |
| US-41 | choose between Claude Haiku 5.5 and Claude 3.5 Sonnet in Settings for recipe chat and meal plan suggestions | P1 |
| US-42 | interactively reconcile pantry inventory stock (in stock / low / out / don't change) when marking a recipe as cooked | P1 |
| US-43 | share a recipe as clean formatted plain text via the Android share sheet | P1 |
| US-44 | clean up orphaned images in app storage manually or periodically via background worker | P1 |
| US-45 | check for new app releases from GitHub and receive update notifications on phone and watch | P1 |

## 3. Scope

V1 is capture, store, view and back up; nothing leaves the phone except page fetches (Instagram, recipe sites and YouTube) and LLM calls.

**In scope (v1)**

- Share-target and paste-link capture for Instagram posts and reels (`/p/`, `/reel/`, `/tv/` links), recipe website links, and YouTube videos and Shorts; plus recipes added by hand or found with AI.
- Multimodal image capture (Instagram carousel slides 2–5, cookbook photos, handwritten notes, screenshots from TikTok/Pinterest/WhatsApp) using Claude Haiku 5.5 vision.
- Instagram caption fetch; optional top-comment fetch through an in-app Instagram login (P1).
- Website import: schema.org `Recipe` JSON-LD first, readable page text as the fallback.
- YouTube import from video description or linked recipe page, plus timed-text caption extraction directly from subtitle tracks when descriptions lack recipe text.
- Bulk import of saved Instagram posts from Instagram's data download (P1).
- Claude Haiku 5.5 through the Anthropic Messages API for extraction tasks, with an optional dual model selector in Settings enabling Claude 3.5 Sonnet for recipe chat and meal plan suggestions.
- Local Room/SQLite storage with full-text search; post, page or video image stored as a local file, with automatic WorkManager periodic and manual orphaned image cleanup in Settings.
- Today screen, home-screen widget and Wear OS companion (upgraded to Wear Compose Material 3: tile, complication, small app), week planner with reminders, grocery list and inventory (P1); interactive pantry reconciliation sheet/dialog when marking a recipe as cooked; library, recipe viewer, edit screen, cooking mode, settings.
- Personal touches: favourites, a cooked log with optional photos, personal notes; AI-extracted equipment, consumables, complexity, spice level, make-ahead / freezer flags, diet type, "contains" flags and approximate calories.
- Recipe chat (P1): suggestions from the library by mood and ingredients, plus opt-in web suggestions through Claude's web search tool.
- Sharing a recipe as clean formatted plain text (`text/plain`) via the Android share sheet.
- GitHub Releases in-app update checker for phone and watch with update notifications.
- Zip backup and restore through the system file picker; a running AI cost counter in Settings.
- Platform-independent logic in a plain Kotlin `:core` module, so a desktop or web app can reuse it later.

**Out of scope (v1)**

- Accounts, cloud sync, any backend.
- Play Store release, multi-language UI, tablets, and the desktop or web companion itself.
- On-device LLM (Gemini Nano); revisit later behind the same interface.
- Audio and voice transcription models (Whisper, audio LLMs) for video/reels (YouTube timed-text subtitles are extracted directly from subtitle tracks without speech-to-text).
- Detailed nutrition (macros), and sharing recipes as rich markdown or PDF (clean plain text is used).
- A standing dietary profile for the chat; dietary needs are stated in each chat message.

## 4. Success criteria, risks, open questions

V1 is done when a share-to-saved capture works end to end for at least 8 of 10 caption-based recipe posts.

**Success criteria**

- Share → review screen in under 8 s on Wi-Fi (fetch + LLM).
- At least 80% of caption-based recipes need no edits to ingredients or steps.
- Image recipe capture (cookbook photo or carousel slide) yields clean structured recipe in under 10 s.
- YouTube caption extraction successfully retrieves recipes from videos with timed-text tracks.
- Orphaned image cleanup reclaims 100% of unreferenced images without touching active recipe covers or cook log photos.
- Library search returns results in under 200 ms with 1,000 recipes.
- Backup → wipe app data → restore gives an identical library, images included.
- LLM cost stays under US$0.50 per month at 100 captures (estimate in Tech spec section 4).

**Risks**

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Instagram changes oEmbed or page markup | Caption fetch breaks | Three fetch strategies with fallback; manual "paste caption" escape hatch |
| Comment scraping triggers account warnings | Account lock | Comments are opt-in, one request per capture, human pace; a secondary account is recommended |
| Recipe only in the video audio | Nothing to extract | Save as `no_recipe_found` with link and caption; user can type it in |
| YouTube videos without timed-text captions or with auto-captions disabled | Nothing to extract from subtitles | Fallback to description, linked recipe page or manual entry / note |
| High-resolution camera photos exceeding Claude payload limits | API request payload rejection or high latency | Automatic client-side resizing and JPEG compression before base64 encoding |
| Sonnet 3.5 cost and rate limits | Higher API bill or rate throttling | Opt-in toggle in Settings for Chat/Suggester while keeping Haiku 5.5 default |
| Recipe site blocks bots (Cloudflare check, paywall) | Page fetch fails | "Paste page text" escape hatch; off-screen WebView fetch as a P1 retry |
| LLM returns malformed or invented data | Bad recipe | JSON schema output, validation, mandatory review screen, raw source text kept |
| Chat suggests a recipe that isn't in the library | Broken card | Model may only return IDs from the index sent; unknown IDs are dropped |
| Library grows past one chat request | Slow or costly chat | Above \~300 recipes, pre-filter with local search before calling the LLM |
| Model deprecation (Haiku 5.5 retires no sooner than 7 Oct 2027) | Calls fail | Model ID is a setting, not a constant |
| Phone loss | Data loss | Backup reminder after N new recipes; backups go to the Syncthing folder |

**Open questions**

- [ ] Starter lists in Tech spec section 2 (tags, diet, equipment, consumables): the fixed vocabularies the AI picks from. Edit them before the first build.
