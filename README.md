# jRelay

jRelay turns a single Android phone into a relay hub for a group SMS conversation. Members text the host device's phone number; the host fans each message back out to every other active member. Group membership, admin messages, and send-pacing are all managed from commands sent by text or from the app itself.

Built as a vanilla Android Java app (no Kotlin, no Jetpack/androidx) with a local SQLite database — see `CLAUDE.md` for the project's coding constraints.

## How it works

There is no group MMS thread and no third-party service involved. jRelay only uses standard SMS:

1. A member texts the host device's number.
2. jRelay reads the message, checks the sender against its member list, and either runs a command or relays the message.
3. A relayed message is queued and sent out individually to every other active, unmuted member, prefixed with the sender's nickname (e.g. `Alex: on my way`).

Because everything rides on regular carrier SMS, standard messaging and data rates apply to every message sent and received, and you are responsible for complying with all applicable laws and carrier policies around automated/bulk messaging. This is disclosed and must be accepted the first time the app is opened.

## First run

On first launch, jRelay shows a disclosure screen covering:

- Carrier messaging/data rates apply to all relayed messages.
- You are responsible for legal and carrier-policy compliance for automated messaging.
- The app is provided as-is with no warranty.

Below that is a checkbox, "You have read and accept the terms of the license," with a **License** link next to it that opens the full license text in-app (see [License](#license) below). **I Agree** stays disabled until that checkbox is checked. Tapping **Decline** closes the app, and the same screen reappears next launch until both the checkbox is checked and I Agree is tapped. Agreeing triggers the runtime permission prompts for SMS send/receive (and notifications, on Android 13+).

## Screens

### Dashboard (Main screen)

- Group name, with an **Edit** button to rename the group.
- Live stats: member count, admin count, muted count, messages sent today, and messages sent all-time.
- **Messages in Queue** — how many outbox messages are waiting to go out or mid-send right now.
- **Delivered / Submitted / Failed** — live outbound status totals. Submitted means Android/the carrier accepted the SMS; Delivered means the recipient network returned a delivery report. Some carriers do not provide delivery reports.
- **Next burst in Xs (N messages)** — a live countdown (updates every second) to when the send service will fire its next burst, and how many messages that burst will contain. Hidden when nothing is scheduled (queue empty, or a burst is actively sending).
- A scrollable feed of the most recent activity across the whole group, with a thin separator between each entry. Outbound entries show their queued/submitted/delivered/failed state beneath the message. Tapping an entry opens that member's detail screen; pressing and holding opens detailed message metadata.
- **Add Member** shortcut.
- Buttons to open the **Membership** screen, the Rate Limiting screen (labeled **Configure**), and **Send to Group**.
- Send and delivery failures trigger a notification on the host device. Delivery reports depend on recipient carrier support, so a message can remain Submitted even when it was delivered if no report is returned.

### Membership

- A small **Menu** button at the top left, on the same line as the title, opens a dropdown with **Export to CSV** and **Import from CSV** (see [Import/Export](#importexport-membership-csv) below).
- An instant search bar filters the list as you type — by nickname, by phone number in *any* format (it compares digits only, so `234-567-8910`, `(234) 567-8910`, and `2345678910` all match the same member), or by typing "admin" to show only admins. The matching text is highlighted in each result.
- Each row shows the nickname with the phone number in small print underneath, plus an `(admin)` badge and/or a `(muted)` badge where applicable, with a thin separator between rows.
- Tap any member to open their detail screen.
- **Add Member** shortcut.

#### Import/Export membership CSV

- **Export to CSV** opens the system "save file" picker (no storage permission needed) and writes every active member as `phone,nickname` rows (with a header row), one file you choose the name/location for.
- **Import from CSV** opens the system file picker, reads whichever CSV you choose, and adds a member for each valid `phone,nickname` or phone-only row — reusing the exact same "added" flow as `#add`/Add Member, so the new member gets the welcome text and everyone else gets the usual "An Admin added ... " notice. A blank nickname receives a `Member ####` label. A header row, blank lines, an unparseable phone number, or a number that's already an active member are skipped rather than failing the whole import; you get a summary of how many were imported vs. skipped.
- Since import re-runs the normal add flow per row, bulk-importing many contacts at once queues a lot of outbound SMS (welcome + broadcast per new member) — it's paced by the same rate-limiting settings as everything else, so a big CSV will take a while to fully go out, by design.

### Member Detail

Tapping a member shows:

- Nickname and phone number.
- Member since date, last activity timestamp, messages sent/received counts, and an activity level (Very active / Active / Quiet / Inactive) based on their message volume over the last 7 days.
- A scrollable feed of that member's recent activity.
- Outbound activity entries show their queued/submitted/delivered/failed state beneath the message.
- Press and hold any history entry for direction/type, full number, exact lifecycle times, character and SMS-segment counts, and readable failure details when available.

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

### Add Member

A simple form (phone number + optional nickname) for adding a member from within the app. If the nickname is blank, jRelay assigns a privacy-friendly label such as `Member 8910` using only the number's last four digits. Members added this way are recorded as added by **"An Admin"** in all broadcasts, rather than a specific admin's nickname.

### Rate Limiting

Two independent controls:

- **jRelay Send Pacing**:
  - **Minimum/maximum messages per burst** — each burst's size is chosen at random from this range, rather than being fixed.
  - **Minimum/maximum wait between bursts (seconds)** — jRelay waits a random duration in this range before sending the next burst.
  - **Wait before sending the first burst** (checkbox, checked by default) — when checked, jRelay waits a random duration (drawn from the same wait range above) before sending the very first burst of a new send cycle, instead of relaying the moment a message comes in. Uncheck it to relay immediately.
  - This is jRelay's own throttle, always in effect, and needs no special permission. Every field has a small label above it so its purpose stays visible even once you've typed a value.
- **Android Outgoing SMS Limit** — Android itself has a built-in threshold (`sms_outgoing_check_max_count` / `sms_outgoing_check_interval_ms`) that warns/blocks an app sending too many texts too fast. jRelay can read and display the device's current values always. Editing them requires the `WRITE_SECURE_SETTINGS` permission, which apps cannot be granted through a normal permission prompt — if it's missing, the screen shows the current values (read-only) plus the exact command to run:

  ```
  adb shell pm grant com.sh7411usa.jrelay android.permission.WRITE_SECURE_SETTINGS
  ```

  Run that from a computer with the device connected over ADB, then reopen the screen to edit the system values.

#### Clear History

A **Clear History** button sits above Disband Group, labeled with a rough estimate of how much history there is to clear (e.g. `Clear History (~12 KB)`). Tapping it shows a Continue/Cancel confirmation explaining that this only erases the message history behind the dashboard/member activity feeds and stats — **members, admins, mute state, and settings are all left alone**, and nobody is notified. There's no undo.

#### Disband Group

A red **Disband Group** button sits at the bottom of the Rate Limiting screen. Tapping it shows a warning that this will **permanently erase every member and all message history, and that nobody will be notified** — then, to confirm, you have to type back a random 4-digit PIN shown right there in the dialog (a fresh one each time). Get the PIN wrong (or cancel) and nothing happens. Get it right and jRelay wipes its entire database (members, message log, and any still-queued outbound messages), resets the group name back to the default "jRelay", and returns to the dashboard. There's no undo, and nothing is sent to anyone as part of it.

#### License

A **License** button at the very bottom of the Rate Limiting screen opens the same in-app license viewer as the link on the first-run screen (see [License](#license) below).

### Send to Group

A dedicated screen (opened from the dashboard's **Send to Group** button) for sending a one-off admin message to every active member at once — regardless of anyone's mute state — formatted the same way as a direct message (`[Admin]: ...`). Useful for group-wide announcements that shouldn't wait on someone muting/unmuting.

## Commands (sent by text from any member)

| Command | Who can use it | What it does |
|---|---|---|
| `#commands` | anyone | Replies with the list of available commands |
| `#mute` | anyone | Pauses messages for you (see below) |
| `#unmute` | anyone | Resumes messages |
| `#stop` | anyone | Leaves the group |
| `#list` | anyone | Replies with the group name, a blank line, then a newline list of member nicknames only — no phone numbers. Shows `(You)` next to your own entry and `(admin)` next to admins |
| `#name <new nickname>` | anyone | Changes your own nickname (see below) |
| `#admin <message>` | anyone | Sends `<message>` to admins only, and pops up a notification on the host device. Non-admins can use this to reach admins directly |
| `#add <number> [nickname]` | admins only | Adds a new member; nickname is optional |
| `#remove <nickname or number>` | admins only | Removes a member |
| `#rename <number> <new nickname>` | admins only | Renames a member identified by phone number |
| `#topic <new name>` | admins only | Renames the group (see below) |

Non-admins attempting `#add`, `#remove`, `#rename`, or `#topic` get back: `"Only admins can use this command."` An unrecognized `#` command gets: `"Unknown command. Reply #commands for a list of commands."` `#commands` itself only lists the admin-only commands to admins.

### Renaming a member by text

```
#rename 914-433-5742 Rabbi New Name
```

Admin-only. The phone number must belong to an active member; the new nickname may contain spaces. Using a phone number avoids ambiguity when two members have the same nickname.

### Adding a member by text

```
#add +12345678910 Alex
#add (234) 567-8910 Alex Smith
#add +1 (234) 567-8910 Alex Smith
#add Alex Smith 234-567-8910
#add 234-567-8910
```

Nicknames are optional and can contain spaces. A number added without one receives a `Member ####` label. jRelay accepts common US phone formats: `+12345678910`, `+1 (234) 567-8910`, `1-234-567-8910`, `234-567-8910`, `2345678910`, and `(234) 567-8910` — and the number can go either first or last when a nickname is present, so it doesn't matter which order feels natural.

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

Renaming the group from the dashboard's **Edit** button sends the same notice to every active member (there's no "acting admin" to exclude), attributed to "An Admin":
> `An Admin has changed the group name to <new name>.`

The dashboard's group name always reflects the current value, live — no need to reopen the app.

### Mute behavior

Muting pauses messages in **both directions**: while muted, you neither receive relayed or admin messages, nor does anything you send in plain text get relayed to the group. Commands (`#unmute`, `#stop`, `#commands`, `#list`) still work normally while muted, so you're never stuck.

### Leaving the group (`#stop`)

Sending `#stop` removes you from the group immediately (same soft-delete as an admin removal). You get a confirmation, and everyone else is notified that you left:
> `<nickname> left the group.`

## Permissions

| Permission | Why | How it's granted |
|---|---|---|
| `SEND_SMS` | Sending relayed/command messages | Runtime prompt at first run |
| `RECEIVE_SMS` | Reading incoming messages to relay | Runtime prompt at first run |
| `POST_NOTIFICATIONS` (Android 13+) | Notifying you of `#admin` messages | Runtime prompt at first run |
| `WRITE_SECURE_SETTINGS` | Editing Android's built-in SMS throttle | Not requestable through a dialog — grant manually via `adb` (see [Rate Limiting](#rate-limiting)) |

jRelay does not need to be set as your default SMS app, and does not request contacts or call permissions — exporting a contact, calling, and texting outside the relay all hand off to your Contacts/Phone/Messages apps via intents instead.

## Data storage

Everything is stored locally in a SQLite database on the device — there is no server or cloud component. Three tables back the app: members, a full message log (used for dashboard/member stats), and an outbox queue that `SmsSendService` drains according to your rate-limiting settings.

## License

jRelay is source-available under the **jRelay Noncommercial License 1.0** — see `LICENSE.md` at the repository root for the authoritative text. In short: free to use, modify, and share for noncommercial purposes; commercial use requires a separate written license from the copyright holder.

The same text is bundled in the app itself (`app/src/main/assets/license.html`) and shown in a `WebView` by `LicenseActivity`, reachable from a **License** link on the first-run screen and a **License** button at the bottom of the Rate Limiting screen — no network access needed, since it's loaded from the local asset.

## Building

```
./gradlew assembleDebug
```

Requires Android SDK with `compileSdk 36` / `minSdk 24`. See `VERSION.md` for the full change history.
