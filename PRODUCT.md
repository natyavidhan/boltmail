# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Native Android: Kotlin + Jetpack Compose (Material 3). The owner chose this over Flutter for the smallest app and the most reliable background push. Mail goes over IMAP/SMTP (JavaMail for Android) with a local Room/SQLite cache.

## Users

One person, the owner, who has several mailboxes that aren't Gmail (two Hostinger custom-domain addresses) plus Gmail. They want every new email to reach their phone as a notification the moment it arrives, and to read it right there, the way the Gmail app handles Gmail. They check mail many times a day, in short bursts, one-handed, often on the move.

## Product Purpose

BoltMail puts every IMAP mailbox the owner has into one Android app: instant push notifications for each new message, a unified inbox, reading, and replying, with no server or subscription in between. Success: a Hostinger email notifies within seconds, opens in one tap, and reads cleanly.

## Positioning

A mail client with nothing in it but mail. Push comes straight from each mail server to the phone over IMAP IDLE: no relay server, no BoltMail account, no ads, no feeds. FairEmail exposes every knob and Thunderbird for Android needs push switched on per folder. BoltMail makes push the default and makes setup a two-field form.

## Operating Context

- Accounts: Hostinger on custom domains (IMAP imap.hostinger.com:993, SMTP smtp.hostinger.com:465); Gmail via Sign in with Google (OAuth, XOAUTH2); any standard IMAP/SMTP provider.
- Proton Mail is out of scope, because it has no IMAP access without the desktop Bridge.
- Push is IMAP IDLE held open by an always-on foreground connection. Phones that kill background apps (Xiaomi, OnePlus, Realme and others) need battery optimization switched off, and the app has to walk the owner through that.
- Used on a phone, one-handed, indoors and out, many times a day.

## Capabilities and Constraints

- v1: add accounts with auto-detected server settings; unified inbox plus per-account and per-folder views; reading HTML and plain-text mail with attachments; push notifications with actions; archive, delete and mark read/unread; reply, reply-all, forward and compose with attachments; send over SMTP and save to Sent.
- Distribution: a sideloaded APK for the owner only, not the Play Store. That allows an always-on special-use foreground service and a direct prompt for the battery-optimization exemption.
- Gmail sign-in: Sign in with Google through Play Services authorization, scope `https://mail.google.com/`. It needs the owner's own Google Cloud OAuth client (Android type: package name plus signing SHA-1). An app-password path stays as a fallback.
- Low bloat is a requirement: few dependencies, no analytics or trackers, a small APK.
- Remote images in mail stay blocked until the reader asks for them (tracking pixels).
- Not decided yet: conversation threading, server-side drafts, searching beyond cached mail.

## Brand Commitments

- Name: BoltMail. The icon at `assets/icon.svg` is a T3 Code placeholder and isn't binding.
- The owner's brief, verbatim: "make the UIUX EXTREMELY user friendly and minimal, with nice and readable typography, as less clutter as possible, and keep the app as less bloat-y as possible".

## Evidence on Hand

None. No real mail content exists in the repo. Demo and test content must be labeled as such and never presented as real.

## Product Principles

1. Mail first: every screen leads with the message, and chrome has to earn its place.
2. Push you can trust: when the connection can't stay alive, the app says why and offers a one-tap fix.
3. Setup is an email address plus a password, or Google. Server details only appear when detection fails.
4. Nothing extra: no feature, dependency or permission without a clear job.
5. Reversible by default: destructive actions happen instantly and offer undo instead of a confirmation dialog.

## Accessibility & Inclusion

Text follows the system font size, touch targets are at least 48dp, and text contrast is at least 4.5:1. Mail can contain Devanagari (Hindi) and other scripts, which must fall back to proper system fonts.
