# jRelay

jRelay turns a single Android phone into a relay hub for a group SMS conversation. Members text the host device's phone number; the host fans each message back out to every other active member. Group membership, admin messages, send-pacing, daily limits, and message content are all managed from commands sent by text or from the app itself.

Built as a vanilla Android Java app (no Kotlin, no Jetpack/androidx) with a local SQLite database — see `CLAUDE.md` for the project's coding constraints.

## How it works

There is no group MMS thread and no third-party service involved. jRelay only uses standard SMS:

1. A member texts the host device's number.
2. jRelay reads the message, checks the sender against its member list, and either runs a command or relays the message (after checking the current group mode and daily limits — see [Group Modes](#group-modes) and [Daily Limits](#daily-limits)).
3. A relayed message is optionally transformed (see [Message Content & Salting](#message-content--salting)), then queued and sent out individually to every other active, unmuted member, prefixed with the sender's nickname (e.g. `Alex: on my way`).
4. Sending itself is paced globally (see [Send Pacing](#send-pacing)) — the whole outbox drains on one shared burst/wait/microspacing schedule, in a shuffled order, with automatic retries and failure tracking.

Because everything rides on regular carrier SMS, standard messaging and data rates apply to every message sent and received, and you are responsible for complying with all applicable laws and carrier policies around automated/bulk messaging. This is disclosed and must be accepted the first time the app is opened.

## First run

On first launch, jRelay shows a disclosure screen covering:

- Carrier messaging/data rates apply to all relayed messages.
- You are responsible for legal and carrier-policy compliance for automated messaging.
- The app is provided as-is with no warranty.

Below that is a checkbox, "You have read and accept the terms of the license," with a **License** link next to it that opens the full license text in-app (see [License](#license) below). **I Agree** stays disabled until that checkbox is checked. Tapping **Decline** closes the app, and the same screen reappears next launch until both the checkbox is checked and I Agree is tapped. Agreeing triggers the runtime permission prompts for SMS send/receive (and notifications, on Android 13+).

## Screens

### Dashboard (Main screen)

- Group name, with an **Options** button opening a dropdown: **Set Group Name**, **Add Member**, **Settings**, **Send to Group**, and one-tap entries for the two group modes that aren't currently active — **Switch to Group Mode**, **Switch to Announcement Mode**, **Switch to Reply Mode** (see [Group Modes](#group-modes)). A small **Announcement Mode** or **Reply Mode** badge appears next to the group name whenever one of those is active, and a **Service Paused** badge appears whenever [Pause Service](#pause-service) is active.
- A 2-column grid of stat tiles: Members, Admins, Muted, Messages Today, Messages Total, **Failed Today**, and In Queue. The Queue tile shows a small green **Sending…** badge whenever a burst is actively scheduled or in flight.
- **Next burst in Xs (N messages)** — a live countdown (updates every second) to the next scheduled burst, and how many messages it will contain. Hidden when nothing is scheduled.
- A scrollable feed of the most recent activity across the whole group, with a thin separator between each entry. Tapping an entry opens that member's detail screen. Shows one row per relayed message (who sent it and what) rather than a copy per recipient it was delivered to.
- A **Membership** button.

### Membership

- A small **Menu** button at the top left, on the same line as the title, opens a dropdown with **Export to CSV** and **Import from CSV** (see [Import/Export](#importexport-membership-csv) below).
- An instant search bar filters the list as you type — by nickname, by phone number in *any* format (it compares digits only, so `234-567-8910`, `(234) 567-8910`, and `2345678910` all match the same member), or by typing "admin" to show only admins. The matching text is highlighted in each result.
- Each row shows the nickname with the phone number in small print underneath, plus a colored `(admin)` and/or `(muted)` pill badge where applicable, with a thin separator between rows.
- Tap any member to open their detail screen.
- **Add Member** shortcut.

#### Import/Export membership CSV

- **Export to CSV** opens the system "save file" picker (no storage permission needed) and writes every active member as `phone,nickname` rows (with a header row), one file you choose the name/location for.
- **Import from CSV** opens the system file picker, reads whichever CSV you choose, and adds a member for each valid `phone,nickname` row. Before importing, you're asked to choose how to notify the group about it:
  - **Usual** — every new member gets the welcome text and everyone else gets the usual "An Admin added ..." notice, exactly as if each had been added one at a time.
  - **Streamlined** — new members still get their individual welcome text, but everyone who was already a member just gets one combined notice: "An Admin added N new members."
  - **No reporting** — every row is added silently; nobody is notified anything happened.
  - This choice is independent of the **Notify group when a member is added** toggle in Settings (see [Member Reporting](#member-reporting)), which only governs individual `#add`/Add Member additions.
  - A header row, blank lines, an unparseable phone number, an empty nickname, or a number that's already an active member (including a duplicate within the same file) are all skipped rather than failing the whole import; you get a summary of how many were imported vs. skipped.
- Bulk-importing many contacts at once queues a lot of outbound SMS — it's paced by the same send-pacing settings as everything else, so a big CSV will take a while to fully go out, by design.

### Member Detail

Tapping a member shows:

- Nickname and phone number.
- Member-since / last-active caption, and a stat tile grid: Sent, Received, Messages Today, and a color-coded Activity level (Very active / Active / Quiet / Inactive) based on message volume over the last 7 days.
- A **Daily Limit** section to give this member their own daily cap, independent of the group's shared pool (see [Daily Limits](#daily-limits)) — shows "today: X / Y" usage, an **Unlimited** checkbox to exempt just this member from their own cap (they still respect the shared group pool if that's enabled), and an **Override (+1 Today)** button that appears automatically once a non-unlimited cap is hit.
- A scrollable feed of that member's recent activity, with a thin separator between each entry (matching the dashboard's Recent Activity feed).

Actions available:

| Action | Effect |
|---|---|
| Make Admin / Revoke Admin | Toggles admin status |
| Mute / Unmute | Toggles mute (see [Mute](#mute-behavior) below) |
| Send Direct Message | Sends a one-off `[Admin]: ...` text to just this member |
| Edit Nickname | Renames the member |
| Edit Phone Number | Updates their stored number |
| Export Contact | Opens the Contacts app pre-filled with this member's name/number to save |
| Call | Opens the dialer with this member's number |
| Text (Default App) | Opens your default SMS app with this member's number, for an off-the-record text outside the relay |
| Remove from Group | Removes the member (with confirmation) — see [Removal](#removal) below |

The last remaining admin cannot be removed, demoted, or otherwise stop being an admin — by text (`#stop`, `#remove`) or in the app. This is deliberate: it was previously possible to end up with zero admins, and there was no way back — re-adding that person returns them as an ordinary member, since admin status can only be granted from the app, and with no admin left, no admin command would work either.

### Add Member

A simple form (phone number + nickname) for adding a member from within the app. Members added this way are recorded as added by **"An Admin"** in all broadcasts, rather than a specific admin's nickname.

### Settings

Everything configurable lives on one scrollable Settings screen (opened from the dashboard's **Options → Settings**), grouped into labeled sections. The two most urgent, state-changing controls (Pause Service and Group Mode) are the first two sections, right below the title.

#### Pause Service

An emergency stop. Choosing an option is not instant — see below for what "immediately" actually means:

- **Not Paused** (default) — normal operation, or resumes if currently paused.
- **Pause for 10 Seconds / 1 Minute / 1 Hour / 1 Day** — stops all SMS sending and incoming-message processing for that long, then resumes automatically.
- **Pause Until Unpaused** — stops indefinitely; only picking **Not Paused** resumes it.

While paused, incoming texts are ignored entirely (not even logged), but sending is not cut off mid-air: the pause flag is only checked at the top of the drain loop, so a burst already scheduled still goes out in full before the pause takes hold — at the defaults that's up to 5 messages. Anything queued after that, or queued by an app action while paused, stays queued and goes out once resumed. A status line under the dropdown shows the live countdown, and the dashboard shows a **Service Paused** badge. Resuming manually flushes the queue immediately; if a timed pause instead expires while the app is in the background, the backlog goes out on the next thing that would normally trigger a send (an incoming message or an app action), since there's no background alarm driving it on a timer alone. Opening the Settings screen no longer cancels an active pause — it used to resume sending as a side effect of the screen drawing itself, silently releasing the backlog while still showing "Not Paused".

#### Group Mode

A dropdown + Save button to switch between **Group Mode**, **Announcement Mode** and **Reply Mode** from the app — the same setting `#mode group`/`#mode announcement`/`#mode reply` controls by text (see [Group Modes](#group-modes)), and also reachable from the dashboard's **Options** menu, which offers the two modes that aren't currently active.

#### Reply Mode

Only affects behavior while the group is in [Reply Mode](#reply-mode).

- **Reply window (hours; 0 = no limit)** — default 24. How long after a post a plain reply still reaches its author. Past the window there's no valid target, so the message goes to the admins instead.
- **Copy replies to admins** — off by default. Also sends every delivered reply to active admins as `@admin reply from <sender> to <target>: <text>`. Muted admins are skipped, and so are the sender and the target, so nobody gets it twice.

#### Commands

- **Accept bare keywords** — on by default. Lets members text `STOP`, `UNSUBSCRIBE`, `CANCEL`, `QUIT`, `END`, `HELP`, `MUTE` or `UNMUTE` without a leading `#`. See [Bare keywords](#bare-keywords).

#### Send Pacing

- **Staggering** — on by default. Waits a random duration (the min/max seconds you set) between bursts so sends don't all fire at once; an **initial delay** checkbox (also on by default) additionally waits before the very first burst of a send cycle. Turning staggering off sends bursts back-to-back with no inter-burst wait at all.
- **Burst Configuration** — how many messages go out per burst:
  - **Random amount, between bounds** (default) — each burst's size is drawn at random from a min/max range.
  - **Fixed amount** — every burst is exactly the size you set.
  - **All messages at once** — no bursting at all; the *entire pending outbox* goes out together, not just what's queued for one recipient — so picking this sends a whole fan-out in one uninterrupted burst.
- **Microspacing** — paces the individual messages *inside* one burst apart from each other, in milliseconds (default 350ms, on by default). Can be a fixed gap or randomized between bounds. This is intentionally not built on `Thread.sleep`: gaps are computed as a fractional-millisecond deadline and enforced with a self-correcting loop of short `LockSupport.parkNanos` calls, so short waits stay precise instead of drifting.
- Send pacing is global — one shared burst/wait/microspacing schedule drains the whole outbox. It is not configurable per member.
- Pacing fields (waits, burst size, microspacing) now have upper as well as lower bounds, so a bad value can't put the sender to sleep for years or crash it outright. These are sanity limits, not a rate control — they do not keep you under any carrier's threshold. In particular, the inter-burst wait is skipped entirely with staggering off, and **All messages at once** ignores the burst ceiling altogether, so neither of those is bounded by this at all.

- **Coalesce window (seconds; 0 = off)** — default 45. Holds a relayed post briefly so posts made close together reach each member as **one** text instead of one each. See [Coalescing](#coalescing).
- **Max segments per merged message** — default 3. A merged text never grows past this many SMS segments; when the next post would push it over, the merged message is closed and a new one started.

#### Delivery Queue Shuffling

On by default. Each burst, the pending messages it's drawn from are shuffled before being picked, so the same member isn't always first (or last) in a repeating pattern.

#### Member Reporting

**Notify group when a member is added** (on by default) — when a member is added via `#add` or the Add Member screen, this controls whether the usual welcome text (to them) and broadcast (to everyone else) are sent at all. Turning it off adds members completely silently. This does not affect CSV import, which always asks for a reporting style per import (see [Import/Export](#importexport-membership-csv)) — or joins via `#join`, which follow this same toggle (see [Join Requests](#join-requests)).

#### Join Requests

Off by default. Lets people who *aren't* members yet text `#join <nickname>` (or just `#join`, using their phone number as the nickname) to the group number to ask to join:

- **Off** — `#join` from a non-member is silently ignored.
- **Allow** — they're added immediately, as if an admin had run `#add` for them (subject to the Member Reporting toggle above).
- **Require Approval** — nobody is added automatically. Admins get a notice naming the requester and a ready-to-forward `#add <number> <nickname>` command; the requester gets a short "sent to the admins" acknowledgment. jRelay doesn't track the request beyond that — an admin approves it by simply sending back the suggested `#add` command (or ignores it to decline).

An existing member who sends `#join` is just told they're already a member.

#### Message Content & Salting

- **Append sender's number** (off by default) — adds the sender's own number, as bare digits (`2345678910`), to the end of every relayed message.
- **Strip phone numbers** (off by default) — removes anything that looks like a phone number (via regex) from a member's message before relaying it.
- **Salting** (all off by default) — makes each relayed message unique, e.g. to dodge a carrier's duplicate-message filtering:
  - **Timestamp** — appends the send time, in your choice of format: `HH:mm:ss`, Unix seconds, hex seconds, or hex milliseconds (the shortest, and unique to the millisecond).
  - **Random hex code** — appends or prepends a random 3-digit hex code (e.g. `[A3F]`).
  - **Zero-width spaces** — randomly injects invisible `U+200B` characters between words, so the message differs byte-for-byte each time even though it reads identically.

#### Failures & Retries

- **Retry limit** (default 1) — how many times a failed send is retried before giving up. 1 means one retry after the initial attempt (2 attempts total, never more).
- **Failure alert threshold** (default 3) — once a member's cumulative failed-send count reaches a multiple of this number, every admin gets an `@system: <nickname> has had N failed messages.` alert (plus a device notification). A member's failure count resets to 0 on their next successful send. Every send that exhausts its retries is also logged and shows up in that member's Recent Activity feed.

#### Group Daily Limit

Two independent, layered daily caps (both reset at a configurable time of day, midnight by default). **Both caps count posts, not messages sent.** One relayed post is one unit against the cap, no matter how many members it fans out to — a group of 100 turns every post into ~99 outbound texts that are never separately counted. So a limit typed as if it meant "messages" is off by roughly the group's size: a cap of "100" is not 100 messages/day, it's up to ~9,900.

- **Group Daily Limit** — **off by default.** As shipped, there is no volume cap on the group at all; `#limits` answers "The group daily limit is not enabled." until you turn this on. Enabled, it's a single shared pool for the whole group (e.g. "100 posts/day total" — not 100 messages). When enabled and exhausted, **every** member's relayed messages are blocked until reset, with a reply telling them it's a **Group limit** and when it resets. This always takes precedence over a member's individual cap.
- **Default Individual Limit** — a separate per-member cap, also counted in posts. Each member can have their own custom daily limit (set on their Member Detail screen), and this Settings field is a convenience to bulk-seed it: type a number and tap **Apply to All Members** (overwrites everyone's individual cap) or **Apply to Members Without a Custom Limit** (only fills in members who don't already have one). This does not touch the shared group pool.

When a member hits *their own* individual cap (with the group pool still having room), they're told it's an **Individual limit**. Either way, the reply tells them to contact an admin for an override.

Admins can check status and grant overrides by text — see [Daily Limits](#daily-limits) below.

#### Android Outgoing SMS Limit

Android itself has a built-in threshold (`sms_outgoing_check_max_count` / `sms_outgoing_check_interval_ms`) that warns/blocks an app sending too many texts too fast. jRelay can read and display the device's current values always. Editing them requires the `WRITE_SECURE_SETTINGS` permission, which apps cannot be granted through a normal permission prompt — if it's missing, the screen shows the current values (read-only) plus the exact command to run:

```
adb shell pm grant com.sh7411usa.jrelay android.permission.WRITE_SECURE_SETTINGS
```

Run that from a computer with the device connected over ADB, then reopen the screen to edit the system values. Tapping the notice itself copies the command to the clipboard.

#### Language

Choose **System Default**, **English**, **עברית** (Hebrew), or **יידיש** (Yiddish). Takes effect immediately (the app relaunches to the dashboard). See [Localization](#localization) below.

#### Appearance

Choose **System Default**, **Light**, or **Dark**. Takes effect immediately, independent of the device's own theme setting.

#### Miscellaneous

- **Clear History** — labeled with a rough estimate of how much history there is to clear (e.g. `Clear History (~12 KB)`). Tapping it shows a Continue/Cancel confirmation explaining that this only erases the message history behind the dashboard/member activity feeds and stats — **members, admins, mute state, and settings are all left alone**, and nobody is notified. There's no undo.
- **Disband Group** — a red button. Tapping it shows a warning that this will **permanently erase every member and all message history, and that nobody will be notified** — then, to confirm, you have to type back a random 4-digit PIN shown right there in the dialog (a fresh one each time). Get the PIN wrong (or cancel) and nothing happens. Get it right and jRelay wipes its entire database, resets the group name back to the default "jRelay", and returns to the dashboard. There's no undo, and nothing is sent to anyone as part of it.
- **License** — opens the same in-app license viewer as the link on the first-run screen (see [License](#license) below).

#### About

Shows the installed version as `jRelay <versionName> (<versionCode>)`, read from the app's own package info so it always matches the actual build.

### Send to Group

A dedicated screen (opened from **Options → Send to Group**) for sending a one-off admin message to every active member at once — regardless of anyone's mute state — formatted the same way as a direct message (`[Admin]: ...`). Useful for group-wide announcements that shouldn't wait on someone muting/unmuting.

## Commands (sent by text from any member)

| Command | Who can use it | What it does |
|---|---|---|
| `#commands` | anyone | Replies with the list of available commands |
| `#help` | anyone | Replies with how to phrase a message **in the mode the group is in right now** — in [Reply Mode](#reply-mode) it explains the `#all` prefix, in [Announcement Mode](#announcement-mode) it explains who may post, in Group Mode it says to just text normally. A muted member gets a different answer instead, since the above would be false for them: it says they are muted and points them at `#unmute`. Distinct from `#commands`, which answers "what commands exist?" rather than "what do I do now?" |
| `#mute` | anyone | Pauses messages for you (see below) |
| `#unmute` | anyone | Resumes messages |
| `#stop` | anyone | Leaves the group |
| `#list` | anyone | Replies with the group name, a blank line, then a newline list of member nicknames only — no phone numbers. Shows `(You)` next to your own entry and `(admin)` next to admins |
| `#name <new nickname>` | anyone | Changes your own nickname (see below) |
| `#admin <message>` | anyone | Sends `<message>` to admins only (formatted `@admin <nickname>: <message>`), and pops up a notification on the host device. Non-admins can use this to reach admins directly |
| `#add <number> <nickname>` | admins only | Adds a new member |
| `#remove <nickname or number>` | admins only | Removes a member |
| `#topic <new name>` | admins only | Renames the group (see below) |
| `#limits` | admins only | Replies with today's group daily limit status: used/total/remaining and reset time |
| `#override` | admins only | Adds 1 message of headroom to the group's daily limit for today |
| `#override <nickname or number>` | admins only | Adds 1 message of headroom to that member's individual daily limit for today (errors if they don't have one set) |
| `#mode` | anyone | Replies with the current group mode (Group, Announcement or Reply) |
| `#mode group` / `#mode announcement` / `#mode reply` | admins only | Switches the group mode (see [Group Modes](#group-modes)) |
| `#all <message>` | anyone | Posts `<message>` to everyone. Required in [Reply Mode](#reply-mode), where a plain message is treated as a reply instead; accepted (and simply stripped) in the other modes so the habit is never punished. `all: <message>` works the same way |
| `#to <nickname> <message>` | anyone | **Reply Mode only.** Sends `<message>` privately to one named member. Accepts a nickname or a phone number, and handles nicknames containing spaces. In other modes it replies "Only available in Reply Mode." |
| `#join <nickname>` | non-members | Requests to join the group, if Join Requests is enabled (see [Join Requests](#join-requests)) |

Members can also text the bare words `STOP`, `UNSUBSCRIBE`, `CANCEL`, `QUIT` or `END` (same as `#stop`), `HELP` (same as `#commands`), or `MUTE`/`UNMUTE`, with no leading `#` — see [Bare keywords](#bare-keywords).

Note that bare `HELP` and `#help` deliberately do **not** do the same thing. Bare `HELP` is a carrier opt-out convention that has to keep returning the command list, so it maps to `#commands`; `#help` answers the different question of how to phrase a message right now. Each reply mentions the other, so a member who types the "wrong" one is still pointed somewhere useful.

Non-admins attempting an admin-only command get back: `"Only admins can use this command."` An unrecognized `#` command gets: `"Unknown command. Reply #commands for a list of commands."` `#commands` itself only lists the admin-only commands to admins — a regular member's reply omits them entirely.

A command followed by a line break (e.g. `#admin` on its own line, message on the next) is recognized correctly and works the same as a trailing space. Command matching also no longer depends on the phone's own language/locale setting, so it can't silently stop recognizing commands on certain devices.

An empty or whitespace-only message is not relayed — a pocket-send no longer reaches the group as a blank post and no longer counts against the daily limit.

### Adding a member by text

```
#add +12345678910 Alex
#add (234) 567-8910 Alex Smith
#add +1 (234) 567-8910 Alex Smith
#add Alex Smith 234-567-8910
```

Nicknames can contain spaces. jRelay accepts common US phone formats: `+12345678910`, `+1 (234) 567-8910`, `1-234-567-8910`, `234-567-8910`, `2345678910`, and `(234) 567-8910` — and the number can go either first or last (`#add <number> <nickname>` or `#add <nickname> <number>`), so it doesn't matter which order feels natural. The same tolerant number matching is used for `#remove <number>` and `#override <number>`.

When a member is added (by text or from the app), they receive:
> `<admin> added you to <group name> Group. Reply #stop at anytime to opt out.`

Everyone else in the group receives:
> `<admin> added <nickname> to the group.`

### Changing your nickname by text

```
#name Alex Smith
```

You get a confirmation:
> `Your name has been changed to <new nickname>.`

Everyone else in the group receives:
> `<old nickname> changed their name to <new nickname>.`

Changing a member's nickname from the **Member Detail** screen in the app sends the same kind of notice to everyone else, but attributed to "An Admin":
> `An Admin has changed <old nickname>'s name to <new nickname>.`

### Removing a member by text

```
#remove Alex
#remove +12345678910
```

The removed member receives:
> `You have been removed from <group name> Group. You will no longer receive messages from this Group.`

Everyone else receives:
> `<admin> removed <nickname> from the group.`

Removal is a soft delete — the member's history and stats are preserved (visible again if you're troubleshooting), but they're excluded from relaying, `#list`, and the Membership screen. They can be re-added later with `#add`/Add Member using the same number, which reactivates their original record rather than creating a duplicate.

### Renaming the group by text

```
#topic Weekend Trip
```

Admin-only. Everyone else in the group receives:
> `<admin> has changed the group name to <new name>.`

Renaming the group from the dashboard's **Options → Set Group Name** sends the same notice to every active member (there's no "acting admin" to exclude), attributed to "An Admin":
> `An Admin has changed the group name to <new name>.`

The dashboard's group name always reflects the current value, live — no need to reopen the app.

### Mute behavior

Muting pauses messages in **both directions**: while muted, you neither receive relayed or admin messages, nor does anything you send in plain text get relayed to the group. Commands (`#unmute`, `#stop`, `#commands`, `#help`, `#list`) still work normally while muted, so you're never stuck. `#help` in particular detects that you're muted and tells you so, rather than giving the normal mode advice that wouldn't apply to you.

### Leaving the group (`#stop`)

Sending `#stop` removes you from the group immediately (same soft-delete as an admin removal). You get a confirmation, and everyone else is notified that you left:
> `<nickname> left the group.`

## Send Pacing

Sending is global: `SmsSendService` drains the whole outbox on one shared burst/wait/microspacing cadence — it is not configurable per member. See [Settings → Send Pacing](#send-pacing) above for the burst-mode/staggering/microspacing controls, and [Delivery Queue Shuffling](#delivery-queue-shuffling) for how the pending messages a burst draws from are shuffled.

A send that fails is retried (up to the configured retry limit) by requeuing it for a later burst rather than hammering immediately; once retries are exhausted it's logged as failed and counted toward that member's failure-alert threshold (see [Settings → Failures & Retries](#failures--retries)).

Only one drain runs at a time. If something triggers a send while one is already in progress, it's dropped rather than starting a second — the running drain re-checks the outbox on every pass, so it picks up whatever the dropped trigger was for. Without this, every incoming message would add another burst loop running its own independent pacing, and the effective send rate would multiply.

## Delivery status

jRelay asks Android to report what actually happened to each message, rather than assuming a send worked because the call didn't fail. When a message can't be delivered, the reason appears next to it in that member's activity feed:

| What you'll see | What it means |
|---|---|
| **Carrier rejected the message** | The network refused it outright |
| **Sending too fast -- check the outgoing SMS limit in Settings** | Something refused the message for rate reasons. Most often this is Android's own per-app throttle (30 messages per 30 minutes by default), not your carrier — check [Settings → Android Outgoing SMS Limit](#settings) first, then your send pacing |
| **Phone had no signal (airplane mode or radio off)** | The host phone had no radio at that moment |
| **No cell service at the time** | In range of nothing usable |
| **Never reached the network** | The send failed before it left the phone |
| **Send failed (code N)** | An unrecognised response. The number is shown rather than guessed at, so it stays diagnosable |

Failed messages are retried up to your configured retry limit before being recorded as failed, and repeated failures to the same member still trigger the usual admin alert. The difference is that these now fire on real failures — previously they could only react to the rare case where the send call itself threw, which is not how carriers refuse messages.

A failed message is retried after a delay rather than immediately, and how long depends on why it failed: five minutes for a rate refusal, since something has just told you to slow down and resending at once is the opposite of the right response; a minute for no-signal or no-service, which usually resolve on their own; thirty seconds otherwise.

**If you hit a rate refusal, the backoff is not the fix — raising the limit is.** The usual source of that refusal is Android's own per-app cap, and its window is 30 messages per *30 minutes* (see [Android Outgoing SMS Limit](#android-outgoing-sms-limit)). Waiting five minutes often isn't long enough for that window to reopen, so with the default retry limit of 1 the message spends its only retry and is then marked failed. Worse, the cap applies to the whole phone line rather than to one message, so during a group send the messages behind it are being refused too. If you see a run of rate refusals, raise the Android limit rather than waiting it out. Holding the entire queue when the limit is hit — the response that would actually fit the problem — is planned but not yet built.

**What this does not do: detect carrier filtering.** Filtering is silent by design — the network accepts the message, reports success, and drops it later. No error code is produced, so nothing here can see it. Everything above is a failure that *announced itself*. Staying under the carrier's thresholds is still the only defence against the silent kind, which is what the pacing, daily limits and [coalescing](#coalescing) are for.

Delivery results arrive a moment after a message goes out, so the app stays running briefly at the end of a send to collect them — otherwise a failure on the very last message of a group send would sit unretried until the next time anything else triggered a send.

## Coalescing

Posts made close together are combined, so each member receives one text instead of one per post. With the default 45-second window, three posts inside that window reach 100 members as **100 sends instead of 300**. A person composing one longer message is also closer to what carriers expect than three rapid identical blasts.

- Only **relayed posts** are ever held. Command replies, system notices, admin messages, DMs and Reply-Mode replies go out immediately, as before.
- Merged posts are joined in the order they were sent, one per line, and the merged text never exceeds [Max segments per merged message](#send-pacing). A single post too long to fit on its own is still sent whole — never split or dropped.
- **A post made while an earlier one is still being delivered is merged into the copies that haven't gone out yet.** Members reached early get the two texts separately; everyone still in the queue gets them together. This is why merging is per *member* rather than per *post* — in a group where a post draws replies, the tail of the list gets the whole exchange in one message.
- Set the window to **0** to turn coalescing off entirely and send each post on its own, as before.

While posts are being held the dashboard says so, either as `holding N` appended to the next-burst countdown or as **Holding N to merge** on its own, so a post never appears to simply vanish for 45 seconds. The **Sending…** badge stays hidden while merely holding, since nothing is going out yet.

## Group Modes

A group-wide switch between three modes. It can be changed three ways: by text (`#mode group` / `#mode announcement` / `#mode reply`, admins only), from **Settings → Group Mode** (dropdown + Save), or from the dashboard's **Options** menu, which offers the two modes that aren't currently active — anyone can check the current mode by texting `#mode`.

- **Group mode** (default) — everyone's plain-text messages are relayed to the whole group, as usual.
- **Announcement mode** — only admins' plain-text messages are relayed to everyone. A non-admin's message is instead sent only to admins (formatted `@admin <nickname>: <message>`, the same as `#admin <message>`), and doesn't count against anyone's daily limit.
- **Reply mode** — anyone can still post to everyone, but they have to say so: a plain message is treated as a *reply* and goes to one person. See below.

However it's changed, switching modes broadcasts a notice to the group (attributed to whichever admin made the change, or "An Admin" when changed from the app) and, if triggered by text, replies to that admin with the new mode's status. The dashboard shows a small **Announcement Mode** or **Reply Mode** badge next to the group name whenever one is active, so it's obvious at a glance.

### Reply Mode

Reply mode exists to cut how many texts actually leave the phone. In a 100-member group, a post relayed to everyone costs 100 SMS — and so does every reply to it. Reply mode keeps posts costing 100 and makes replies cost 1.

- **To post to everyone**, start the message with `#all ` (or `all:`). The prefix is stripped and the message is relayed exactly as it would be in Group mode — same daily-limit checks, same `nickname: body` format, same salting. It counts as one relayed message, as usual.
- **To reply**, just text normally. The message goes *only* to the person who sent the last post you received, formatted `<nickname> (reply): <text>`. Replies **don't** count against anyone's daily limit, the same way `#admin` messages don't.
- **If there's nobody to reply to** — you haven't received a post yet, the [reply window](#reply-mode-1) has passed, the poster has left the group, or you wrote the last post yourself — the message goes to the admins instead, and you get back: `"Sent to the admins. To post to everyone, start your message with #all."`
- **To reply to a specific person** when two posts arrived close together, use `#to <nickname> <message>`. It accepts a nickname or a phone number, and copes with nicknames that contain spaces.
- Receiving an admin **Send to Group** broadcast or an admin direct message clears your reply target, so replying to one of those reaches the admins rather than whichever member happened to post before it.
- `#all` also works in Group and Announcement mode (the prefix is simply stripped), so nobody is punished for keeping the habit after a mode change.

### Bare keywords

Carriers expect opt-out words to work without punctuation, and people type `STOP` without the `#`. With **Settings → Commands → Accept bare keywords** on (the default), a message whose *entire* text is one of these — trimmed, any capitalization — runs the matching command, in every mode:

| Typed | Acts as |
|---|---|
| `STOP`, `UNSUBSCRIBE`, `CANCEL`, `QUIT`, `END` | `#stop` |
| `HELP` | `#commands` |
| `MUTE` / `UNMUTE` | `#mute` / `#unmute` |

Whole-message matching only, so ordinary sentences are safe: "stop by later" relays as normal text. Turning the setting off makes these words relay as text too.

## Message Content & Salting

Content transforms apply only to **relayed member messages** (the `nickname: body` broadcasts) — not system notices, admin broadcasts, DMs, or command replies. See [Settings → Message Content & Salting](#message-content--salting) above for the full list of options (all off by default except send-pacing related ones).

**Each recipient's copy is salted separately.** A salt is applied once per outgoing text, at the moment it's sent, so every member's copy of the same post differs. Earlier versions salted a post once and sent that identical body to everyone, which meant the salt only ever distinguished one post from another — never one copy from another — and a hundred byte-identical messages is exactly the pattern salting exists to avoid. Retries are salted afresh too, rather than resending an identical body.

One consequence: the **timestamp** salt reflects when that member's copy went out, not when the post was made. At the default pacing a 100-member fan-out takes roughly three minutes, so the spread is small; the `HEX_SECONDS`, `HEX_MILLIS` and `UNIX_SECONDS` formats make it unnoticeable, and `TIME_HHMMSS` is the only one where it's legible.

## Daily Limits

jRelay tracks how many **posts** have actually been relayed (not commands, not system/admin traffic) against two independent, layered caps — see [Settings → Group Daily Limit](#group-daily-limit) above for how to configure them, and the [`#limits`/`#override`](#commands-sent-by-text-from-any-member) commands above for checking status and granting overrides by text. The unit is the inbound post, not the outbound copies it becomes — a 100-member group turns each post into ~99 sends that aren't separately counted, so size the number accordingly. Both caps reset at the same configurable time of day (default midnight); an override adds one post of headroom to *today's* limit only.

## Localization

jRelay ships with English, Hebrew (עברית), and Yiddish (יידיש) translations. Pick one explicitly in **Settings → Language**, or leave it on **System Default** to follow the device's own locale. Hebrew and Yiddish are both right-to-left; every layout already uses start/end (not left/right) attributes, so RTL mirroring is automatic. The Yiddish translation is a best-effort machine/AI translation and would benefit from a native-speaker review before relying on it for anything user-facing at scale.

Language and theme are applied without any androidx dependency: every screen extends a shared `BaseActivity` that wraps its `Context` with a `Configuration` forcing the chosen locale and/or dark/light mode in `attachBaseContext`, so `values-iw`/`values-yi`/`values-night` resources resolve correctly regardless of the device's own settings.

## Theming

**Settings → Appearance** lets you pick **System Default**, **Light**, or **Dark**, independent of the device's own dark-mode setting. Changing it (or the language) relaunches the app at the dashboard so every screen picks up the change immediately.

## Permissions

| Permission | Why | How it's granted |
|---|---|---|
| `SEND_SMS` | Sending relayed/command messages | Runtime prompt at first run |
| `RECEIVE_SMS` | Reading incoming messages to relay | Runtime prompt at first run |
| `POST_NOTIFICATIONS` (Android 13+) | Notifying you of `#admin` messages and failure alerts, and showing the sending notification | Runtime prompt at first run |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Keeping the send service alive for the length of a fan-out (see [Staying alive](#staying-alive)) | Granted at install |
| `RECEIVE_BOOT_COMPLETED` | Resuming the outbox after a reboot | Granted at install |
| `WRITE_SECURE_SETTINGS` | Editing Android's built-in SMS throttle | Not requestable through a dialog — grant manually via `adb` (see [Settings](#settings)) |

jRelay does not need to be set as your default SMS app, and does not request contacts or call permissions — exporting a contact, calling, and texting outside the relay all hand off to your Contacts/Phone/Messages apps via intents instead.

## Staying alive

Relaying to 100 members takes about three minutes, and [coalescing](#coalescing) can hold messages for a while before that. Android is aggressive about stopping background work on that timescale, and when it does, the only symptom is that messages quietly stop.

**While sending, jRelay runs as a foreground service** and shows a notification saying so. That notification is the thing telling Android not to kill the send, so it cannot be dismissed while a send is in progress — it disappears on its own when the queue empties. It also doubles as a status readout: how many messages are pending, how many are being held for merging, and whether the next burst is scheduled. It is on its own silent channel, so it never makes a sound, and tapping it opens the dashboard.

**A foreground service does not keep the processor awake.** It stops the send being killed; it does not stop the phone sleeping. With the screen off, the pauses between bursts can stretch out, so a fan-out may take longer than your pacing settings predict. Nothing is lost — it catches up — but if you find sends dragging badly with the screen off, that is why.

**After a reboot**, jRelay repairs anything left half-sent and picks the queue back up — but only once the phone has been unlocked a single time. Until first unlock the app's database is encrypted and genuinely unreadable, so there is nothing it can do before then. Worth knowing if the host phone ever restarts unattended; Samsung in particular ships an "auto restart at set times" option that is sometimes on by default.

**Battery settings still matter**, because manufacturer battery managers override the standard rules. On a dedicated relay phone, set jRelay to **Unrestricted** under Settings → Apps → jRelay → Battery, and make sure it is excluded from any "put unused apps to sleep" / "deep sleeping apps" list. These can also be set over `adb`:

```
adb shell dumpsys deviceidle whitelist +com.sh7411usa.jrelay
adb shell cmd appops set com.sh7411usa.jrelay RUN_ANY_IN_BACKGROUND allow
```

Samsung, Xiaomi, Huawei and Oppo add their own protected-app or auto-start lists that `adb` cannot reach; those have to be set by hand on the device.

## Data storage

Everything is stored locally in a SQLite database on the device — there is no server or cloud component. Three tables back the app: members (including each member's optional daily-limit override), a full message log (used for dashboard/member stats, recent activity, and daily-limit accounting), and an outbox queue (with per-item retry tracking) that `SmsSendService` drains globally according to your send-pacing settings.

The app no longer ships as a debuggable build, and Android backups are now off. That database holds every member's number and the entire message log; debuggable + default backup rules meant `adb shell run-as` could read it straight off the phone, and a backup would have carried it into whichever Google account the phone is signed into, or into a phone-to-phone transfer — none of which any member agreed to. The trade-off: `adb shell run-as` can no longer read the database for troubleshooting. Logcat is unaffected.

## License

jRelay is source-available under the **jRelay Noncommercial License 1.0** — see `LICENSE.md` at the repository root for the authoritative text. In short: free to use, modify, and share for noncommercial purposes; commercial use requires a separate written license from the copyright holder.

The same text is bundled in the app itself (`app/src/main/assets/license.html`) and shown in a `WebView` by `LicenseActivity`, reachable from a **License** link on the first-run screen and a **License** button at the bottom of the Settings screen — no network access needed, since it's loaded from the local asset.

## Building

```
./gradlew assembleDebug
```

Requires Android SDK with `compileSdk 36` / `minSdk 24`. See the commit history for changes.
