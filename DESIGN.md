# Design

BoltMail's visual language is **Shade**: mail is laid out the way Android lays out notifications, in grouped, rounded stacks, so the app reads as part of the phone. Everything below is taken from the built app (`ui/theme/Theme.kt`, `ui/components/`).

## Principles

- **Mail first.** Rows carry sender, subject and one line of preview. There are no avatars, stars or chips in the list.
- **One accent, and only for meaning.** Bolt blue marks unread state and the primary action on each screen, nothing else.
- **Identity is a tag.** Each account has a color shown as an 8 dp rounded square, and only when more than one account is on screen.
- **Undo, not confirm.** Archive and delete happen instantly with an undo snackbar. Dialogs are reserved for discarding unsent text and removing an account.

## Color

| Token | Light | Dark | Use |
|---|---|---|---|
| ground | `#ECEEF1` | `#0A0B0D` | Screen background, behind the stacks |
| surface | `#FFFFFF` | `#1A1C20` | Rows and cards |
| raised | `#F5F6F8` | `#23262B` | Skeletons, quote toggle, suggestion rows |
| ink | `#16181B` | `#ECEEF1` | Primary text |
| ink2 | `#545961` | `#A3A8B0` | Secondary text, section labels, idle icons |
| ink3 | `#6A6F77` | `#8E939B` | Times, placeholders, field labels |
| accent | `#2F5BFF` | `#93A6FF` | Unread dot and time, primary pill, links |
| accentSoft / onAccentSoft | `#DCE3FF` / `#0F2A8F` | `#27336E` / `#DDE4FF` | FAB, selected mailbox, archive swipe |
| dangerSoft / onDangerSoft | `#FDE2E0` / `#8C1D18` | `#4C1F1D` / `#FFDAD6` | Delete swipe, error notices, PDF tile |
| line | `#D9DCE1` | `#34373D` | Hairlines and borders (rare) |

Account colors: `#F07A2E #14A89A #8B67F0 #2F8FE0 #E0457B #6E9F2E #C9A227 #7A8594`.

All text pairs meet 4.5:1 on their surface. HTML mail is darkened by WebView's algorithmic darkening in dark mode.

## Type

One family: **Google Sans Flex** (OFL), a variable font subset to Latin and bundled at 222 KB. Scripts outside the subset, including Devanagari, fall back to the system fonts. Titles add a little roundness (`ROND` 40). Sizes are in sp and follow the system font size.

| Role | Size / line | Weight | Used for |
|---|---|---|---|
| displaySmall | 34 / 40 | 500, ROND 40 | Welcome title |
| headlineMedium | 31 / 38 | 500, ROND 40 | Screen titles ("Inbox") |
| headlineSmall | 25 / 31 | 500, ROND 40 | Message subject |
| titleLarge | 21 / 27 | 500, ROND 40 | Sheet and top-bar titles |
| titleMedium | 16 / 22 | 450 (680 unread) | Sender, row titles |
| bodyLarge | 16 / 25.6 | 400 | Message body, inputs |
| bodyMedium | 15 / 21 | 400 (560 unread) | Subject line |
| bodySmall | 14 / 20 | 400 | Preview, subtitles |
| labelLarge | 14.5 / 20 | 560 | Buttons |
| labelMedium | 13 / 18 | 600 | Section labels ("Today") |
| labelSmall | 13 / 17 | 400 | Times, metadata |

## Shape and spacing

- **Stacks**: 10 dp inset from the screen edges; 2 dp seams between rows; 24 dp outer corners and 6 dp inner corners (`shadeShape(index, count)`). A single row gets 24 dp all round.
- **Rows**: 30 dp leading padding (the unread dot sits in it at x = 13 dp), 16 dp trailing, 12 to 13 dp vertical. Settings rows are at least 58 dp tall.
- **Section labels**: 24 dp from the edge, 18 dp above, 8 dp below.
- **Pills**: fully rounded, 44 dp tall (52 dp for full-width calls to action, 40 dp in top bars).
- **FAB**: 56 dp, 19 dp corners, accentSoft.
- Touch targets are at least 48 dp.

## Components

- `ShadeGroup` / `ShadeCell` / `ShadeRow`: the stack and its rows.
- `MailRow`: sender and time (plus account tag), subject (plus paperclip), one-line preview, unread dot. It sits inside `SwipeToDismissBox`: swipe right to archive (accentSoft), left to delete (dangerSoft).
- `Notice`: an icon in a tinted circle, a title, one sentence and up to two text actions. Used for push health, sign-in problems and the outbox.
- `Pill` (primary or surface), `TextAction`, `IconBtn` (48 dp), `AccountTag`, and `Monogram`, which appears only in the message header.
- Mailbox switcher: a bottom sheet on the ground color with stacks for inboxes and folders, and account chips to pick whose folders to show.

## Icons

Material Symbols Rounded, weight 400, 24 dp, imported as vector drawables (`res/drawable/ic_*.xml`). The app mark is the filled bolt on bolt blue. The Google "G" appears only on the Sign in with Google button.

## Motion

- Screens slide an eighth of the width and fade in over 260 ms, mirrored for Back.
- Rows animate in and out of the list (`animateItem`); swipes follow the finger and settle with Material defaults.
- The first-sync skeleton pulses its opacity between 0.55 and 1 over 900 ms. No other motion loops.

## Voice

Short, plain sentences that say what happened and what to do: "Images are hidden so the sender can't track you." "Let mail arrive instantly." No exclamation marks, no jargon in the main flow. Server terms (IMAP, SMTP, STARTTLS) appear only on the manual settings screen.
