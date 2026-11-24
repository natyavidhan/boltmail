#!/usr/bin/env python3
"""Local test mail server helper for BoltMail.

Start a GreenMail server with two test users:

    docker run -d --name boltmail-greenmail -p 3025:3025 -p 3143:3143 \
      -e GREENMAIL_OPTS='-Dgreenmail.setup.test.smtp -Dgreenmail.setup.test.imap -Dgreenmail.hostname=0.0.0.0 \
      -Dgreenmail.users=me:secret@boltmail.test,riya:secret@boltmail.test -Dgreenmail.users.login=email' \
      greenmail/standalone:2.1.2

In BoltMail, add me@boltmail.test / secret with manual settings: IMAP 10.0.2.2:3143 and SMTP 10.0.2.2:3025,
security "None" (10.0.2.2 is the host machine as seen from the Android emulator).

    python3 tools/greenmail.py seed          # fill the inbox with sample mail of different ages
    python3 tools/greenmail.py send "Hi"     # deliver a new message over SMTP (tests push)
    python3 tools/greenmail.py show          # list folders and message counts

All names and content below are made up.
"""
import imaplib
import smtplib
import sys
from datetime import datetime, timedelta, timezone
from email.message import EmailMessage
from email.utils import format_datetime, make_msgid

HOST = "127.0.0.1"
USER = "me@boltmail.test"
PASSWORD = "secret"
now = datetime.now(timezone.utc)


def message(frm, subject, text, html=None, attach=None, to=USER, cc=None):
    m = EmailMessage()
    m["From"] = frm
    m["To"] = to
    if cc:
        m["Cc"] = cc
    m["Subject"] = subject
    m["Date"] = format_datetime(now)
    m["Message-ID"] = make_msgid(domain="sample.test")
    m.set_content(text)
    if html:
        m.add_alternative(html, subtype="html")
    if attach:
        name, mime, data = attach
        maintype, subtype = mime.split("/")
        m.add_attachment(data, maintype=maintype, subtype=subtype, filename=name)
    return m


def seed():
    pdf = b"%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n"
    items = [
        (timedelta(minutes=12), False, message(
            "Riya Sharma <riya@lightbox.sample>", "Re: Shoot schedule for Friday",
            "Hi team,\n\nWorks for us. Can we move the call to 4 pm so the crew can join after the recce? "
            "I've attached the updated call sheet.\n\nTwo things for Friday: location permits are confirmed till 7 pm, "
            "and lunch is for 14 people, all veg.\n\nThanks,\nRiya\n\n"
            "On Mon, 23 Nov 2026 at 18:02, Me <me@boltmail.test> wrote:\n> Can we lock Friday?\n> Thanks",
            attach=("Call sheet - Friday.pdf", "application/pdf", pdf), cc="ankit@studio.sample")),
        (timedelta(minutes=95), False, message(
            "Hosting Co <billing@hosting.sample>", "Your invoice is ready",
            "Payment of Rs 1,499.00 received for Business Email Starter.",
            html="""<html><body style="font-family:Arial;background:#f4f4f7;margin:0">
<table width="600" align="center" style="background:#fff;border-radius:8px;padding:24px">
<tr><td><h2 style="color:#673de6;margin:0 0 12px">Payment received</h2>
<p>Payment of <b>&#8377;1,499.00</b> received for <b>Business Email Starter</b>.</p>
<p>Your next renewal is on 12 March 2027.</p>
<img src="https://tracker.sample/open.gif" width="1" height="1">
<p><a href="https://hosting.sample/invoices">View invoice</a></p></td></tr></table></body></html>""")),
        (timedelta(hours=3), True, message(
            "Ankit Verma <ankit@studio.sample>", "Contract draft v2",
            "Attached the revised draft with the changes from Tuesday. Clause 4 now covers usage rights for 12 months.",
            attach=("Contract v2.pdf", "application/pdf", pdf))),
        (timedelta(hours=26), True, message(
            "Accounts <no-reply@accounts.sample>", "Security alert",
            "A new sign-in on Android. If this was you, you don't need to do anything.")),
        (timedelta(hours=29), True, message(
            "Kabir Mehta <kabir@friends.sample>", "Lunch on Thursday?",
            "There's a new place near the office that does a proper thali. 1:30?")),
        (timedelta(days=3), True, message(
            "Aditi Rao <aditi@design.sample>", "Moodboard feedback",
            "Loved the second direction. The type feels calmer and the colours are spot on.")),
        (timedelta(days=4), True, message(
            "Payroll <payroll@studio.sample>", "Salary slip for October",
            "Your salary slip for October 2026 is attached.",
            attach=("Salary slip Oct 2026.pdf", "application/pdf", pdf))),
        (timedelta(days=12), True, message(
            "Neha Kapoor <neha@client.sample>", "Brief for the Diwali campaign",
            "Sharing the brief for the campaign. Budget and timelines are in the second section.")),
        (timedelta(days=40), True, message(
            "Studio Team <team@studio.sample>", "Offsite photos",
            "All the offsite photos are in the shared folder now.")),
    ]
    imap = imaplib.IMAP4(HOST, 3143)
    imap.login(USER, PASSWORD)
    for age, seen, m in items:
        when = (now - age).timestamp()
        imap.append("INBOX", "(\\Seen)" if seen else "()", imaplib.Time2Internaldate(when), m.as_bytes())
    imap.logout()
    print("seeded", len(items), "messages")


def send(subject):
    s = smtplib.SMTP(HOST, 3025)
    s.send_message(message("Riya Sharma <riya@lightbox.sample>", subject, "Quick one: can you share the final cut by 6?"))
    s.quit()
    print("sent", subject)


def show():
    imap = imaplib.IMAP4(HOST, 3143)
    imap.login(USER, PASSWORD)
    _, boxes = imap.list()
    for raw in boxes:
        box = raw.decode().split(' "." ')[-1].strip('"')
        imap.select(box, readonly=True)
        _, everything = imap.search(None, "ALL")
        _, seen = imap.search(None, "SEEN")
        print(f"{box}: {len(everything[0].split())} messages, {len(seen[0].split())} read")
    imap.logout()


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "seed":
        seed()
    elif cmd == "send":
        send(sys.argv[2] if len(sys.argv) > 2 else "Test message")
    elif cmd == "show":
        show()
    else:
        print(__doc__)
