# Android release signing

Production releases must use one persistent signing key. Never commit a keystore to a public repository.

## GitHub Actions repository secrets

| Secret | Value |
| --- | --- |
| RELEASE_KEYSTORE_B64 | Single-line base64 encoding of private JKS |
| RELEASE_KEYSTORE_PASSWORD | Private JKS password |
| RELEASE_KEY_PASSWORD | Private signing key password |
| RELEASE_KEY_ALIAS | radio-usb-recorder |

### One-time setup

1. Download the private JKS and signing-secrets.env from the ChatGPT conversation.
2. Install GitHub CLI and authenticate on a trusted machine.
3. Run: gh secret set -f radio-usb-recorder-signing-secrets.env -R Iu-yang1/usb-raw-recorder-ab13x
4. Alternatively use Settings > Secrets and variables > Actions > New repository secret.
5. Securely back up the original JKS and both passwords offline; loss prevents seamless APK upgrades.

Public X.509 signing certificate SHA-256:
59:36:BE:32:17:95:D3:06:1A:0F:94:F3:88:5F:82:A4:07:EF:3F:2A:0A:F8:BF:F0:53:CE:8A:79:50:5B:57:B3

Certificate: self-signed RSA-3072 / SHA256withRSA, 30-year validity. Never expose the keystore contents, base64, or secrets file.

### Signed GitHub release

GitHub Actions > Publish signed Android Release > Run workflow. Enter v0.1.0-alpha.1 and select prerelease.
The workflow builds the unsigned APK, zipaligns, signs using apksigner, verifies certificate and signs assets,
then creates a GitHub prerelease and attaches APK and SHA256 checksum. It fails if signing secrets are absent.
Only perform this after Build Android Debug APK passes. HarmonyOS 4 and AB13X hardware support is not yet proven.
