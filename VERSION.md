# Version History

## 1.22 - Retry All Safe Failures

- Added a confirmed **Retry All Safe** action to Failed Messages that requeues only send failures with zero submitted SMS segments.
- Delivery failures and partial multipart submissions remain excluded to prevent duplicate messages.

## 1.21 - Failed Messages View

- Added a dashboard **Failed Messages** screen listing send failures, delivery failures, and scheduled retries in one place.
- Tapping or holding a failed message opens full Message Details, including the duplicate-safe Retry action when Android submitted zero SMS segments.

## 1.20 - Member History Long-Press Fix

- Fixed member message-history rows being rebuilt by the one-second delivery-status refresh while they were being held, which could cancel the Message Details long-press gesture.

## 1.19 - Safe SMS Retry

- Added up to three automatic retries after 15, 30, and 60 minutes when Android rejects a message before any SMS segment is submitted.
- Added a duplicate-safe **Retry** action to outbound Message Details; it is available only when zero segments were submitted.
- Message Details now shows send-attempt count, last-attempt time, and next scheduled retry.
- Multipart messages are never retried as a whole after even one segment was submitted, avoiding duplicate delivery of accepted segments.

## 1.18 - Long-Press Message Details

- Added a long-press **Message Details** dialog to dashboard Recent Activity and each member's history while preserving the dashboard's normal tap-to-open-member behavior.
- Outbound details include exact recipient number, type, created/queued/submitted/delivered times, status, character count, multipart submitted/delivered/total progress, and readable error information.
- Incoming details include sender, number, received time, character count, and whether the message was a command or regular relay text.
- Newly received `#` messages are now logged with the COMMAND category so their details are accurate; existing history remains unchanged.
- Message history rows are focusable with the existing DPAD focus highlight so long-press details remain accessible on small non-touch devices.

## 1.17 - Admin Rename Command

- Added the admin-only `#rename <number> <new nickname>` command, with support for spaces in both common formatted phone numbers and nicknames.
- The command requires an active member's phone number, returns clear usage/not-found replies, and sends the existing admin rename notice to the acting admin and other unmuted members.
- Added `#rename` to the admin command-help response and covered its number/nickname parsing with a unit test.

## 1.16 - Optional Member Nicknames

- Members can now be added without a nickname from the Add Member screen, `#add`, or CSV import.
- Missing nicknames receive a privacy-friendly `Member ####` label based on the last four digits rather than exposing the full phone number through `#list`.
- Re-adding an inactive member without a nickname preserves their previous nickname.
- Updated command help and the Add Member hint to show that nicknames are optional, with unit coverage for number-only parsing and generated labels.

## 1.15 - Historical Status Backfill and Live Refresh

- Added a one-time SQLite v3-to-v4 migration that safely links historical outbox records to the nearest unlinked outbound history row only when member, full message body, and timestamp (within 10 seconds) agree.
- Dashboard Recent Activity and open member-history screens now refresh delivery labels every second as sent/delivery callbacks arrive.
- Preserves unmatched history rather than attaching a potentially incorrect delivery status.

## 1.14 - Inline Delivery Status

- Linked every newly queued outbox row to its exact outbound message-log row through a non-destructive SQLite v2-to-v3 migration.
- Added Queued, Sending, Submitted, Delivered, Send Failed, and Delivery Failed labels directly beneath outbound messages in both dashboard Recent Activity and each member's message history.
- Kept the compact dashboard delivery totals and failure notifications, while removing the separate Delivery Status button/screen so status appears in the normal message workflow.
- Existing message and outbox history remains intact; exact inline linkage begins with messages created after upgrading to 1.14.

## 1.13 - SMS Send and Delivery Reporting

- Added per-part `PendingIntent` callbacks for both SMS submission and carrier delivery reports, including multipart messages.
- Outbox entries now move through Queued, Sending, Submitted, Delivered, Send Failed, and Delivery Failed states instead of being marked sent immediately after calling `SmsManager`.
- Added a non-destructive SQLite v1-to-v2 migration that preserves members, message history, settings, and existing outbox records while adding part counters, timestamps, and error codes.
- Added a dashboard delivery summary and a DPAD-compatible **Delivery Status** screen showing the 100 most recent outbound messages, recipient, state, time, and error code when available.
- Added high-priority local notifications when Android rejects a send or a carrier reports delivery failure.
- Existing `SENT` outbox records are retained as Submitted; delivery status is available only for messages sent after upgrading.

## 1.12 - In-App License Viewer

- Added `app/src/main/assets/license.html`, an HTML transcription of `LICENSE.md`.
- Added `LicenseActivity`, a `WebView`-backed screen (`activity_license.xml`, WebView as root since it manages its own scrolling) that loads the bundled license asset via `file:///android_asset/license.html` — no network access or new permissions needed.
- Added a **License** button at the bottom of the Rate Limiting screen that opens `LicenseActivity`.
- Added a "You have read and accept the terms of the license." checkbox to the first-run consent screen, with a **License** link next to it that also opens `LicenseActivity`. **I Agree** is now disabled until that checkbox is checked, in addition to the existing disclosure acceptance.

## 1.11 - Disband Resets Group Name

- Disband Group now also resets the group name back to the default "jRelay" (`Prefs.resetGroupName`), instead of leaving the previous group's name behind after all its members and history are erased.

## 1.10 - #list Header, Dashboard Button Label

- `#list` now replies with the group name, a blank line, then the member list, instead of the bare list.
- Renamed the dashboard's "Rate Limiting" button label to "Configure".

## 1.9 - Membership Search, Clear History, Dashboard Polish

- Added an instant search bar to the Membership screen: filters live as you type by nickname (substring, case-insensitive), by phone number in any format (digit-only comparison, so formatting doesn't matter), or by typing "admin" to show only admins. Matching text is highlighted (`SpannableString` + `BackgroundColorSpan`) in both the nickname and the phone number.
- Each member row now shows the phone number in small text underneath the nickname (`row_member.xml` restructured into a nickname+phone column beside the admin/muted badge).
- Added thin 1dp separators between rows in both the Membership list and the dashboard's Recent Activity feed (`util/UiUtil.createDivider`).
- Tapping a Recent Activity entry on the dashboard now opens that member's detail screen.
- Added a small "Group Name" caption underneath the group name on the dashboard.
- Added a **Clear History** button above Disband Group on the Rate Limiting screen: a Continue/Cancel confirmation that erases only the message history (`MessageRepository.deleteAll`) — members, admins, mute state, and settings are untouched, and nobody is notified. The button label shows a rough size estimate in parentheses (`MessageRepository.estimateStorageBytes`).

## 1.8 - Disband Group, Membership CSV Import/Export

- Added a red **Disband Group** button at the bottom of the Rate Limiting screen. Confirming requires typing back a random 4-digit PIN shown in the warning dialog (freshly generated each time); a wrong PIN or Cancel does nothing. On correct confirmation, `DbHelper.wipeAllData()` permanently deletes all members, message history, and any queued outbound messages, with no notification sent to anyone, and returns to the dashboard.
- Added a **Menu** button at the top-left of the Membership screen (same line as the title) opening a dropdown with **Export to CSV** and **Import from CSV**:
  - Export writes every active member as `phone,nickname` rows via the system "save file" picker (`ACTION_CREATE_DOCUMENT`) — no storage permission needed.
  - Import reads a chosen CSV via the system file picker (`ACTION_OPEN_DOCUMENT`) and adds a member per valid row through the existing `CommandProcessor.addMember` flow (same welcome/broadcast messages as `#add`), skipping header rows, blanks, invalid numbers, empty nicknames, and existing active members, then reports an imported/skipped summary.
  - Added `util/CsvUtil` (minimal RFC 4180-style field escaping/parsing) with unit tests (`CsvUtilTest`).

## 1.7 - Flexible #add Argument Order

- Fixed `#add` rejecting the number with `"Invalid phone number format."` when the nickname came first (e.g. `#add User 234-567-8910`) — only `#add <number> <nickname>` was accepted. Added `PhoneNumberUtils.splitTrailingNumberAndRest`, tried as a fallback whenever the leading-number parse fails, so `#add <nickname> <number>` now works too. Covered by new tests in `PhoneNumberUtilsTest`.

## 1.6 - Fixed Hyphenated Number Entry on Add Member

- `PhoneNumberUtils` already normalized `234-567-8910` and `1-234-567-8910` correctly (confirmed with new `PhoneNumberUtilsTest` unit tests covering all six documented US formats) — the actual bug was the Add Member screen's phone field using `android:inputType="phone"`, which attaches Android's `DialerKeyListener` and silently filters out `-`, `(`, `)`, and spaces as they're typed. Changed it to `textNoSuggestions` so every documented number format can actually be entered there. The `#add` SMS command and the Member Detail "Edit Phone Number" dialog were unaffected (no character restriction on those inputs).

## 1.5 - Group Renaming, Role-Aware Command List

- Added the `#topic <new name>` command (admin-only): changes the group name and notifies every other active member (`"<admin nickname> has changed the group name to <new name>."`).
- Renaming the group from the dashboard's Edit button now notifies every active member the same way, attributed to "An Admin" (`"An Admin has changed the group name to <new name>."`), via the same new `CommandProcessor.changeGroupName` used by `#topic`. Previously it silently updated `SharedPreferences` with no notification at all.
- The dashboard's group name now refreshes live (every second, alongside the queue status tick) instead of only on `onResume`, so a rename from any source shows up immediately while the dashboard is open.
- `#commands` replies now depend on the requester's admin status: `#add`, `#remove`, and `#topic` are only listed for admins. Non-admins get the same reply as before minus those three lines.

## 1.4 - Nickname Changes

- Added the `#name <new nickname>` command: any member can rename themselves by text. They get a confirmation reply (`"Your name has been changed to <new nickname>."`), and every other active member is notified (`"<old nickname> changed their name to <new nickname>."`).
- Fixed a gap where changing a member's nickname from the Member Detail screen silently updated the database with no notification at all. It now notifies the rest of the group (`"An Admin has changed <old nickname>'s name to <new nickname>."`), via a new `CommandProcessor.renameMemberFromApp`, matching how app-initiated add/remove already behave.

## 1.3 - Send Queue Visibility, Burst Randomization, Initial Delay, Group Broadcast

- Dashboard now shows live queue depth ("Messages in Queue") and a live countdown to the next burst ("Next burst in Xs/Xm Ys (N messages)"), updated every second while the screen is visible. Backed by a new in-memory `SendQueueStatus` holder that `SmsSendService` publishes to as it schedules each wait.
- Rate Limiting screen's single "messages per burst" field is now a random range: **Minimum** and **Maximum** messages per burst (`Prefs.burstMin`/`burstMax`), matching the existing min/max wait-time randomization. `SmsSendService` picks a random burst size in that range before each burst.
- Added an **initial delay** checkbox (checked by default) on Rate Limiting: when enabled, jRelay waits a random duration (drawn from the same min/max wait-between-bursts range) before sending the very first burst of a new send cycle, instead of relaying immediately. Unchecked sends right away.
- Every EditText on the Rate Limiting screen now has a small bold header label above it (in addition to its hint) so the field's purpose stays visible once text is entered.
- Added **Send to Group**: a dashboard button opens a dedicated `SendGroupMessageActivity` where an admin composes a message that's sent (as `[Admin]: ...`) to every active member regardless of mute state, via `CommandProcessor.broadcastToGroup`.
- Added `OutboxRepository.countUnsent()`/`countPending()` to back the new queue-depth display.

## 1.2 - Documentation

- Added `README.md` documenting all features (dashboard, membership, member detail, add member, rate limiting), the full command list with exact syntax and message templates, mute/removal semantics, permissions, and build instructions.

## 1.1 - Bug Fix

- Fixed a crash when adding a member whose phone number belonged to a previously removed (soft-deleted) member. `phone_e164` is UNIQUE in the `members` table, and soft-deleted rows keep their number, so re-adding it via `#add` or the Add Member screen hit `insertOrThrow` and threw an uncaught `SQLiteConstraintException`. `CommandProcessor.addMember` now reactivates the existing row (`MemberRepository.reactivate`) instead of inserting a duplicate when the number belongs to an inactive member.

## 1.0 - Initial Build

- Stripped all androidx/Jetpack dependencies from the generated template (AppCompatActivity, ConstraintLayout, Material Components, activity-ktx) in favor of vanilla `android.app.Activity` and framework widgets/themes, per project instructions.
- Added SQLite schema (`DbHelper`) with `members`, `message_log`, and `outbox` tables.
- Implemented phone number normalization (`PhoneNumberUtils`) supporting common US formats (+1XXXXXXXXXX, +1 (XXX) XXX-XXXX, (XXX) XXX-XXXX, XXX-XXX-XXXX, 1-XXX-XXX-XXXX, 10-digit, etc.), including numbers with internal spaces when parsing `#add <number> <nickname>`.
- Implemented the SMS relay pipeline: `SmsReceiver` (incoming SMS) -> `CommandProcessor` (parsing/business logic) -> `outbox` table -> `SmsSendService` (rate-limited/staggered sending in configurable bursts with randomized wait between bursts).
- Implemented all member commands: `#commands`, `#mute`, `#unmute`, `#stop`, `#list`, `#admin <message>` (admin-only relay + host notification), `#add <number> <nickname>` and `#remove <nickname|number>` (admin-only, with an "Only admins can use this command." reply for non-admins).
- Implemented all add/remove/mute/stop message templates and broadcasts to the rest of the group.
- Implemented soft-delete for removed/`#stop`'d members so message history and stats are preserved, while excluding them from relay, `#list`, and the membership screen.
- Muting pauses both directions: a muted member neither receives relayed/admin broadcasts nor has their own plain-text messages relayed, while slash-commands still work.
- Added first-run `ConsentActivity`: SMS/data-rate and legal-liability disclaimer that must be accepted before use (re-shown every launch until accepted), followed by runtime permission requests (`SEND_SMS`, `RECEIVE_SMS`, `POST_NOTIFICATIONS`).
- Added `MainActivity` dashboard: group name + rename, member/admin/muted counts, today/total message counts, scrollable recent activity feed, Add Member shortcut, navigation to Membership and Rate Limiting screens.
- Added `MembershipActivity`: scrollable list of active members with an admin badge, tap-through to member detail.
- Added `MemberDetailActivity`: per-member stats (messages sent/received, member since, last activity, 7-day activity level) and recent activity; actions to toggle admin/mute, remove, send a direct message, edit nickname/number, export contact, call, and text via the default SMS app.
- Added `AddMemberActivity` for adding members from the UI (recorded as "An Admin" in broadcasts/templates).
- Added `RateLimitActivity`: configurable burst size and randomized min/max wait between bursts for jRelay's own send pacing, plus a section to view/edit Android's built-in outgoing-SMS throttle (`sms_outgoing_check_max_count` / `sms_outgoing_check_interval_ms`) when `WRITE_SECURE_SETTINGS` is granted, or the exact `adb shell pm grant` command to grant it when it isn't.
- Added a notification channel/alert on the host device whenever a `#admin` message is relayed.
- Added DPAD-focus highlighting on all interactive rows/buttons and made every screen a `ScrollView`-rooted vertical `LinearLayout` per UI guidelines.
- Removed the default instrumented test (`androidTest`) along with its androidx.test dependencies, keeping only plain JUnit for unit tests.
