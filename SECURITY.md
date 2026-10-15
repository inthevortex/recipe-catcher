# Security Policy

## Security Model & Privacy

Recipe Catcher is built on a **local-only, offline-first, privacy-by-design** model:

- **No Remote Backend**: Recipe Catcher does not have a proprietary cloud server, analytics infrastructure, or remote database.
- **Local Secrets Storage**: User-provided credentials (such as the Anthropic API key) are encrypted on-device using **AES-GCM** keys managed by the hardware-backed **AndroidKeyStore**. Only ciphertext is persisted in Android DataStore.
- **Direct Network Access**: Outbound network requests originate solely from the user's phone directly to:
  1. The target recipe web pages, Instagram, or YouTube to fetch public HTML/JSON metadata.
  2. The Anthropic Messages API (`api.anthropic.com`) using the user's own API key.
  3. GitHub Releases API (`api.github.com`) to check for app updates.

---

## Supported Versions

Security fixes and updates are provided for the latest active release branch:

| Version | Supported          |
| ------- | ------------------ |
| Latest  | :white_check_mark: |
| Older   | :x:                |

---

## Reporting a Vulnerability

If you discover a security vulnerability or sensitive data exposure issue, please report it privately:

1. **Email**: Send details of the issue to **`angsumanray@outlook.com`**.
2. **Details**: Include:
   - A description of the vulnerability and its potential impact.
   - Steps to reproduce or a minimal proof of concept.
   - Affected device, Android OS version, and app version.
3. **Response**: You will receive an acknowledgment within 48 hours, followed by updates on triage and mitigation.

Please do **not** open public GitHub issues for undisclosed security vulnerabilities.
