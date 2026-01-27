#set document(
  title: "Encryption Documentation - Coords",
  author: "Allison Bentley",
)
#set page(margin: 1in)
#set text(font: "Source Sans 3", size: 11pt)
#set heading(numbering: "1.")

#align(center)[
  #text(size: 18pt, weight: "bold")[Encryption Documentation]

  #text(size: 14pt)[Coords iOS Application]

  #v(0.5em)

  Bundle ID: `sh.bentley.Transponder`

  #datetime.today().display("[month repr:long] [day], [year]")
]

#v(2em)

= Overview

This document describes the cryptographic functionality used in the Coords iOS application for the purpose of U.S. Export Administration Regulations (EAR) compliance.

= Encryption Algorithms Used

The application uses the following standard cryptographic algorithms:

#table(
  columns: (auto, auto, auto),
  inset: 8pt,
  align: left,
  [*Algorithm*], [*Standard*], [*Purpose*],
  [Ed25519], [RFC 8032 (IETF)], [Digital signatures for user identity verification],
  [X25519], [RFC 7748 (IETF)], [Elliptic curve Diffie-Hellman key exchange],
  [AES-256-GCM], [NIST SP 800-38D], [Authenticated encryption of location data],
)

= Implementation

The cryptographic algorithms are implemented via a bundled Rust library (`transponder_core`) rather than Apple's CryptoKit or Security framework. The implementations use well-established, audited cryptographic libraries from the Rust ecosystem.

= Purpose of Encryption

The encryption in this application is used solely for:

+ *User Authentication* — Ed25519 digital signatures verify user identity when sharing location data with friends. Each user generates a keypair that serves as their cryptographic identity.

+ *Personal Data Protection* — X25519 key exchange derives a shared secret, which is then used with AES-256-GCM to encrypt location data. This provides end-to-end encryption where only the intended recipient can decrypt the data.

= Exemption Qualification

This use of encryption qualifies for export exemption under EAR §740.17(b)(1) for the following reasons:

- All algorithms used are publicly available, international standards published by IETF and NIST
- No proprietary or non-standard cryptographic algorithms are used
- Encryption is used exclusively for authentication and protection of personal user data
- The application does not provide encryption as a service to third parties
- The application is not designed for government or military use

= Data Flow

*Key Exchange* (out-of-band, no server involvement):

#figure(
  ```
  User A                                                         User B
    |                                                               |
    |------------------   Public key via QR code or coord:// link ->|
    |<- Public key via QR code or coord:// link --------------------|
    |                                                               |
    |-- Derive shared secret (X25519)                               |
    |                               Derive shared secret (X25519) --|
  ```,
)

#v(1em)

*Location Sharing* (server only sees encrypted data):

#figure(
  ```
  User A                    Server                 User B
    |                          |                      |
    |-- Sign (Ed25519) ------->|                      |
    |-- Encrypt (AES-GCM) ---->|                      |
    |                          |-- Encrypted blob --->|
    |                          |               Decrypt (AES-GCM)
    |                          |                Verify (Ed25519)
  ```,
)

= Contact Information

Developer: Allison Bentley \
Developer Website: https://bentley.sh \

Application: Coords \
Bundle Identifier: `sh.bentley.Transponder` \
Website: https://coord.is

#v(2em)

#line(length: 100%)

#text(size: 9pt, fill: gray)[
  This document is provided for U.S. export compliance purposes under the Export Administration Regulations (EAR). The cryptographic functionality described herein is limited to authentication and personal data protection as defined in EAR §740.17(b)(1).
]