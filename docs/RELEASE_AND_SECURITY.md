# FactoryFlow release and security checklist

## Firebase environment setup

1. Create a Firebase project owned by the organisation.
2. Register Android application ID `com.tck.factoryflow`.
3. Add the environment-specific `google-services.json` to the app module (never commit production credentials to a public repository).
4. Enable Email/Password authentication and configure authorised users.
5. Assign `organisationId` and `role` as Firebase Authentication custom claims from a trusted Admin SDK process. The Android client must never be allowed to set these claims.
6. Publish and test `firestore.rules` in the Firebase project. Rules in this repository are a starting policy and must be emulator-tested before deployment.
7. Configure App Check and a trusted Cloud Function/API to validate and atomically post stock-affecting operations. Firestore client rules deliberately deny direct writes to master records and posted transactions.

## Inventory integrity boundary

Room transactions make local operations atomic on one device only. They do not prevent two different devices from overselling the same stock. Shared factory inventory must be posted by a trusted backend transaction that checks organisation membership, role, idempotency key and current stock in the same transaction. The local outbox is a retry queue, not proof that the server accepted an event.

## Data correction policy

Do not delete posted production, packing, dispatch or meter records to correct mistakes. Use reasoned compensating/reversal events with actor identity and a durable audit record. Reversal UI and policy need manager/admin authorization in the backend before operational deployment.

## Release gate

- Firebase project and credentials configured per environment.
- Firestore security rules tested with Firebase Emulator Suite.
- Backend atomic stock endpoint and idempotency tested under concurrent dispatch attempts.
- Room migration tested from schema version 1 and from a populated database.
- Authentication, role, offline retry, export, restore, and UI tests passing.
- Debug APK assembled and installed on a physical Android device.
