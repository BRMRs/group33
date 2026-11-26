# MedicationReminder Test Privacy Policy — Review Draft

Version: 2026-11-25-draft-1. Prepared: 25 November 2026.

**Review draft — not effective.** This document prepares the planned phone-authentication integration test. It does not announce a live service. Before activation, the project must verify actual SDK data, storage locations, retention and deletion procedures, then publish the final policy and effective date. Do not use this draft URL as the accepted policy URL of a verified team APK.

Operator: CSI301 Group33 项目组 (CSI301 Group33 Project Team).

Privacy contact: [rui.zheng23@student.xjtlu.edu.cn](mailto:rui.zheng23@student.xjtlu.edu.cn).

## 1. Scope

MedicationReminder is a CSI301 course project. This policy addresses adult volunteers in the team test, covering phone authentication, role selection, consented pairing, fictional medication photos and plans, reminders, reports and necessary synchronization. It is not diagnosis, prescribing or emergency assistance. A reminder, report or connectivity state does not prove ingestion.

Use fictional medication, instructions, plans and photos without identifiable patient information. Do not upload another person's phone number, medical record, prescription, identity document or identifiable patient photo. Volunteers may use their own number to test authentication. Course testing and a keyed phone hash do not make personal information anonymous.

## 2. Information and purposes

- **Identity and login:** the number processed for verification or entered for SMS, verification result, account ID, fixed role selected at registration, and caregiver display name. Verification temporarily processes the provider-confirmed number. The candidate persistence design identifies accounts using a keyed phone digest; that digest is not anonymous data.
- **Installation and session:** installation/session IDs, authentication attempts and deadlines, credentials, original operation IDs and necessary replay-protection records. These support current identity, account/installation isolation, recovery of the original operation and duplicate prevention. Authentication alone does not authorize another Elder's data.
- **Pairing and business data:** relationships created by users and consented to by the Elder, fictional photos, medication text, plans, execution zone, reminder actions and server receipt times. Pairing codes are temporary authorization data, not a public sharing mechanism.
- **Local settings and cache:** language choice, required session state, Room cache, pending operations and reminder state. These support offline display and recovery. The release must verify actual storage and backup exclusions; this draft does not claim that all data is encrypted or uninstall removes every copy.
- **Connection and diagnostics:** IP address, request time, status and safe error categories needed for transport, rate limits and diagnosis. Hosting platforms also process connection information. Project diagnostics must avoid full numbers, proof tokens, access tokens, pairing codes, photos and medication text; release logging remains to be verified.

The test does not provide advertising, data sales, medical decisions or commercial user profiling. Login-risk SDK processing still requires disclosure; we do not claim that no third-party SDK or identifier collection exists.

## 3. SDKs and service providers

These components are present in the phone-test candidate. Packaging is not evidence that every possible field was collected. Verify the final APK, provider scheme and network behavior before real authentication.

- **Alibaba Cloud Fusion Authentication:** one-tap and SDK SMS authentication. Its guide identifies device name, model and OS version; number verification can also process the number, carrier and network information. See the displayed carrier terms and [Number Verification Policy](https://terms.aliyun.com/legal-agreement/terms/suit_bu1_ali_cloud/suit_bu1_ali_cloud202112211045_86198.html).
- **SMS authentication SDK:** provider-managed SMS verification and result; the authentication chain handles the entered number and verification interactions. See its device and processing details in the [SMS SDK Policy](https://terms.alicdn.com/legal-agreement/terms/privacy_policy_full/20230922101800634/20230922101800634.html). The project does not run a separate manual code-sending system or ask volunteers to email codes/proof tokens.
- **CAPTCHA and related providers:** the actual scheme may request verification for risk control, processing IP, device/environment and interaction information. See the [CAPTCHA Policy](https://terms.alicdn.com/legal-agreement/terms/privacy_policy_full/20240510173033685/20240510173033685.html). Verify which sub-services the final scheme enables.
- **Umeng login security:** the candidate packages common 9.5.6 and asms 1.8.0 and enables the SDK-associated component for risk detection and frequency control. Verify identifiers, hardware and network fields for the actual version, permissions and scheme; a provider's optional-field list is not an observed collection inventory. See the [Umeng Policy](https://www.umeng.com/policy.html).
- **Render and Supabase:** the accepted stack uses Render for the independent test service/static policy page and Supabase PostgreSQL/private Storage for structured business data and photos. They receive corresponding requests and process data under reviewed configuration. New resources, regions and permissions are not all verified and are not declared active. See [Render's Policy](https://render.com/privacy) and [Supabase's Policy](https://supabase.com/privacy). Provider policies do not replace this project's final processing disclosure.

Alibaba and its service entities and carriers provide the relevant authentication capabilities. Consult the individual policies for their entities and downstream providers; a brand name alone does not complete the final recipient disclosure.

## 4. Choice and permissions

Before real phone authentication, the App must show an accessible project policy and let you actively choose whether to continue; declining must not start the authentication SDK. Carrier/SMS/provider terms are shown as applicable, and required consent is not replaced by preselected checkboxes. Reading this web page does not consent to App processing.

Photos use a camera flow you initiate. Notifications and applicable exact-alarm/background playback capabilities support reminders. Refusal affects that feature and must not be interpreted as a dose outcome. Check the final APK and operating-system prompts. Existing core tests do not require your contacts, identity documents or precise location.

You may cancel authentication, leave the test and manage system permissions. Local sign-out or pairing revocation is not deletion of the server account, original receipts or provider records.

## 5. Location, retention and deletion

The proposed independent Render service uses Singapore; the existing preview is also there. The new service, database and photo-storage locations are not all finalized. We do not claim that all data stays in mainland China or only on your phone. Before personal data enters this environment, the project must identify actual recipients, locations, purposes, categories and rights channels, and verify applicable cross-border notice, consent and other requirements. A checkbox for this draft does not complete those requirements.

A unified retention/cleanup procedure has not been verified, so this draft does not open real-number testing. The final policy must separately state retention for identity/session records, replay-protection digests, business records/photos, diagnostics and backups, with deletion or appropriate handling once the purpose ends. Credential expiry is not database deletion. A 900-second SDK grant does not justify deleting replay fingerprints after 900 seconds.

Complete self-service account cancellation and joint database/object/backup deletion have not been demonstrated. You may email requests for access, copies, correction, withdrawal, restriction or deletion. The team will verify the necessary identity and scope and explain available handling and limits. Do not email codes, login tokens, passwords, keys or real medical records. Before activation, confirm the responsible person, response window, deletion procedure and provider rights channels. Revoking a session is not deletion.

## 6. Access and security

Business access is checked against the current account, role and active care relationship. Provider credentials, database passwords and signing private keys are restricted to designated configuration operators, not the APK, public repository or test logs. Real integration uses HTTPS. Local HTTP fixtures do not demonstrate cloud security.

We minimize data and access but cannot guarantee absolute system/network security. Stop affected tests and contact the team if data is exposed or sent in error. The team must contain exposure and then notify and handle it as applicable.

## 7. Participation, changes and contact

The integration test does not recruit minors or real patients. Adult volunteers should use only their own authentication information and fictional business data. Contact the team if inappropriate data is found.

Material changes to purposes, recipients, categories, retention or the provider scheme require a revised version and renewed notice/consent where needed. This draft is not effective. The final policy must be accessible before authentication and retain version history.

Contact CSI301 Group33 Project Team at [rui.zheng23@student.xjtlu.edu.cn](mailto:rui.zheng23@student.xjtlu.edu.cn).
