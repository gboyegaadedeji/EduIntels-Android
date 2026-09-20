# EduIntels Android Release Checklist

1. Change `versionCode` and `versionName` for each release.
2. Build and sign with the same private release keystore every time.
3. Test login/logout for Admin, Staff/Teacher, Parent and Student accounts.
4. Test file upload, profile image upload, PDF/document download and camera capture.
5. Test Paystack flows in test mode before production payments.
6. Test external links, WhatsApp, email and telephone actions.
7. Test Android back navigation and full-screen media.
8. Test offline state and reconnection.
9. Test on at least Android 9, Android 12, Android 14 and Android 15/16-class devices where available.
10. Before Play Store release, review current Google Play target-SDK, privacy and account/data-safety requirements.
