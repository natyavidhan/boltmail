# JavaMail looks up protocol providers and content handlers by class name
# (META-INF/javamail.default.providers, META-INF/mailcap).
-keep class com.sun.mail.imap.IMAPStore { <init>(...); }
-keep class com.sun.mail.imap.IMAPSSLStore { <init>(...); }
-keep class com.sun.mail.smtp.SMTPTransport { <init>(...); }
-keep class com.sun.mail.smtp.SMTPSSLTransport { <init>(...); }
-keep class com.sun.mail.handlers.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.activation.** { *; }
-keep class javax.mail.internet.MimeMultipart { *; }
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn javax.security.sasl.**
-dontwarn javax.naming.**
-dontwarn javax.activation.**
-dontwarn com.sun.mail.**
-dontwarn org.apache.harmony.**
