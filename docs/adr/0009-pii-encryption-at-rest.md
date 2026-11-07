# ADR-0009: Application-level encryption of personal data at rest

- **Status:** Accepted
- **Date:** 2026-11-07
- **Deciders:** SkyBook architecture

## Context

Bookings store personal data: passenger names, dates of birth and contact e-mail addresses (GDPR
Art. 32 asks for "appropriate technical measures"). Database backups, replicas, support exports and
anyone with SQL access would otherwise see that data in plain text. Disk encryption (TDE, encrypted
volumes) protects against stolen disks, but not against any of those.

## Decision

- **Encrypt personal-data columns in the application** before they reach the database, using
  **AES-256-GCM** (authenticated encryption) with a random 96-bit IV per value (`FieldEncryptor` in
  the common library).
- The **JPA `AttributeConverter`** (`EncryptedStringConverter`) applies it declaratively:
  `@Convert(converter = EncryptedStringConverter.class)`. Spring injects the encryptor into Hibernate's
  converter instances, so no static key holder is needed.
- **Encrypted fields:** `booking.contact_email`, `booking_passenger.first_name`, `last_name`,
  `date_of_birth`, and the Stripe `payment_client_secret`.
- **Format** `<keyId>:<base64url(iv | ciphertext | tag)>`. The key id prefix enables **rotation**:
  1. add a new key to the key ring and make it active (new writes use it);
  2. older values remain readable while their key stays in the ring;
  3. optionally re-encrypt in the background, then retire the old key.
- **Keys** live in Vault (`skybook.crypto.keys.*`, ADR-0011). The development default in
  `application.yml` exists only so the service starts locally.
- **Log hygiene backs this up:** `Passenger.toString()` and `PassengerDetails.toString()` omit
  personal data, validation errors never echo rejected values, and 500 responses never echo exception
  messages.

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| Database TDE / encrypted volumes only | Transparent | Data is plain text to every SQL user, in backups restored elsewhere, and in replicas |
| MariaDB column functions (`AES_ENCRYPT`) | No application code | Keys sit in SQL statements and logs; ECB mode by default; ties the schema to the database |
| Deterministic encryption | Allows equality search | Leaks equal values (frequency analysis); not needed, because look-ups go by customer id (token subject) and reference |
| Tokenisation service | Strongest isolation | Another critical service to run; too much for this scale |

## Consequences

- Encrypted columns cannot be searched or indexed by content. Agents search by booking reference,
  flight or status, never by passenger name. A searchable blind index (HMAC) can be added if that
  requirement appears.
- Ciphertext is about 1.4 times longer than the plaintext plus a 30-byte overhead, so the columns
  are `VARCHAR(512)`.
- A lost key means lost data. Keys must be backed up with Vault, and rotation must never delete a key
  that existing rows still use.
- A test reads the raw columns with JDBC to prove that no plaintext is stored
  (`personalDataIsEncryptedAtRest`).
