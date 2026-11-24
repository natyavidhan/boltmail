# Sign in with Google

BoltMail asks Google for permission to read and send Gmail over IMAP and SMTP (scope `https://mail.google.com/`). Google only grants that to apps registered in a Google Cloud project, so BoltMail needs your own project. It's free and takes about ten minutes, once.

Until it's done, add Gmail with **Use an app password instead**.

## Steps

1. Open <https://console.cloud.google.com/> and create a project, for example *BoltMail*.
2. **APIs & Services → Library**: enable **Gmail API**. BoltMail doesn't call it, but enabling it makes the Gmail scope available on the consent screen.
3. **Google Auth Platform** (the OAuth consent screen):
   - *Branding*: app name **BoltMail**; your email as the support and developer contact.
   - *Audience*: user type **External**; add your Gmail address under *Test users*.
   - *Data access*: add the scope `https://mail.google.com/`.
   - Back on *Audience*, choose **Publish app**. The app stays *unverified*, which is fine for personal use. The first time you sign in, Google shows a "Google hasn't verified this app" screen: tap **Advanced → Go to BoltMail**. If you leave the app in *Testing* instead, Google signs you out every 7 days.
4. **Clients → Create client → Android**:
   - Package name: `app.boltmail`
   - SHA-1 certificate fingerprint: `27:6C:B3:DB:64:BF:76:F1:4C:60:B3:02:A0:6E:FA:B2:D7:20:5D:9B`

   This is the fingerprint of the key on the computer that built BoltMail (`~/.android/debug.keystore`). If you ever build on another computer, create a second Android client with that computer's SHA-1:
   `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`
5. Wait a few minutes for Google to pick up the new client. Then in BoltMail: **Add account → your Gmail address → Continue with Google**.

The Google account has to be on the phone (*Settings → Passwords & accounts*); otherwise Google offers to add it during sign-in.

## If it doesn't work

- **"Google sign-in isn't set up for this copy of BoltMail yet"** means Google doesn't recognise the package name and SHA-1 pair. Check step 4 and give it a few more minutes.
- **The account later shows "needs you to sign in again"**: open the account in Settings and tap **Sign in with Google**. This happens after you revoke access in your Google account, change your password, or leave the app in Testing for more than a week.
