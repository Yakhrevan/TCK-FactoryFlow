import { initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";

initializeApp();
const db = getFirestore();

type DispatchLineInput = { productId: string; packs: number };
type DispatchInput = {
  organisationId: string;
  factoryId: string;
  idempotencyKey: string;
  dispatchReference: string;
  dispatchDate: string;
  customerId: string;
  customerName: string;
  lines: DispatchLineInput[];
  remarks?: string;
};

function requiredString(value: unknown, label: string): string {
  if (typeof value !== "string" || value.trim().length === 0 || value.length > 180) {
    throw new HttpsError("invalid-argument", label + " is required.");
  }
  return value.trim();
}
function positiveInteger(value: unknown, label: string): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value <= 0) {
    throw new HttpsError("invalid-argument", label + " must be a positive integer.");
  }
  return value as number;
}
async function authorize(
  uid: string,
  orgId: string,
  factoryId: string,
  allowedRoles: string[]
): Promise<{ role: string; memberRef: FirebaseFirestore.DocumentReference }> {
  const memberRef = db.doc(`organisations/${orgId}/members/${uid}`);
  const snap = await memberRef.get();
  if (!snap.exists) throw new HttpsError("permission-denied", "Organisation membership is required.");
  const member = snap.data()!;
  if (member.active !== true || member.organisationId !== orgId ||
      (member.factoryIds && !member.factoryIds.includes(factoryId))) {
    throw new HttpsError("permission-denied", "Active membership for this factory is required.");
  }
  const role = typeof member.role === "string" ? member.role : "";
  if (!allowedRoles.includes(role)) throw new HttpsError("permission-denied", "Role cannot perform this operation.");
  return { role, memberRef };
}

/**
 * Authoritative multi-line dispatch. All stock reads and writes happen in one Firestore
 * transaction; the idempotency record prevents duplicate posting on client retries.
 *
 * Required schema:
 * organisations/{orgId}/members/{uid}
 * organisations/{orgId}/factories/{factoryId}/products/{productId}
 * organisations/{orgId}/factories/{factoryId}/stock/{productId}
 * organisations/{orgId}/factories/{factoryId}/dispatches/{idempotencyKey}
 * organisations/{orgId}/factories/{factoryId}/inventoryTransactions/{transactionId}
 */
export const postDispatch = onCall({ enforceAppCheck: true }, async (request) => {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in required.");
  const data = request.data as Partial<DispatchInput>;
  const orgId = requiredString(data.organisationId, "organisationId");
  const factoryId = requiredString(data.factoryId, "factoryId");
  const key = requiredString(data.idempotencyKey, "idempotencyKey");
  const reference = requiredString(data.dispatchReference, "dispatchReference");
  const date = requiredString(data.dispatchDate, "dispatchDate");
  const customerId = requiredString(data.customerId, "customerId");
  const customerName = requiredString(data.customerName, "customerName");
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) throw new HttpsError("invalid-argument", "dispatchDate must be YYYY-MM-DD.");
  if (!Array.isArray(data.lines) || data.lines.length < 1 || data.lines.length > 50) {
    throw new HttpsError("invalid-argument", "Dispatch must contain 1 to 50 lines.");
  }
  const merged = new Map<string, number>();
  for (const raw of data.lines) {
    const productId = requiredString(raw?.productId, "productId");
    const packs = positiveInteger(raw?.packs, "packs");
    merged.set(productId, (merged.get(productId) ?? 0) + packs);
  }
  const lines = [...merged.entries()].map(([productId, packs]) => ({ productId, packs }));
  const { role } = await authorize(request.auth.uid, orgId, factoryId, ["owner", "admin", "manager", "dispatch"]);
  const root = `organisations/${orgId}/factories/${factoryId}`;
  const dispatchRef = db.doc(`${root}/dispatches/${key}`);
  const customerRef = db.doc(`${root}/customers/${customerId}`);
  const productRefs = lines.map((line) => db.doc(`${root}/products/${line.productId}`));
  const stockRefs = lines.map((line) => db.doc(`${root}/stock/${line.productId}`));
  const result = await db.runTransaction(async (tx) => {
    const existing = await tx.get(dispatchRef);
    if (existing.exists) {
      const prior = existing.data()!;
      if (prior.actorUid !== request.auth!.uid && prior.organisationId !== orgId) {
        throw new HttpsError("permission-denied", "Idempotency key belongs to another operation.");
      }
      return { dispatchId: dispatchRef.id, status: prior.status as string, duplicate: true };
    }
    const customerSnap = await tx.get(customerRef);
    if (!customerSnap.exists || customerSnap.data()?.active === false) {
      throw new HttpsError("failed-precondition", "Customer does not exist or is inactive.");
    }
    // Complete all reads before any writes, as required by Firestore transactions.
    const productSnaps = await Promise.all(productRefs.map((ref) => tx.get(ref)));
    const stockSnaps = await Promise.all(stockRefs.map((ref) => tx.get(ref)));
    const postedLines = lines.map((line, index) => {
      const productSnap = productSnaps[index];
      const stockSnap = stockSnaps[index];
      if (!productSnap.exists) throw new HttpsError("not-found", `Product ${line.productId} not found.`);
      const product = productSnap.data()!;
      if (product.active !== true) throw new HttpsError("failed-precondition", `Product ${line.productId} is inactive.`);
      const packSize = product.bottlesPerPack;
      if (!Number.isSafeInteger(packSize) || packSize <= 0) {
        throw new HttpsError("failed-precondition", `Pack size is not configured for ${product.name ?? line.productId}.`);
      }
      const stock: FirebaseFirestore.DocumentData = stockSnap.exists ? stockSnap.data()! : {};
      const availablePacks = stock.completePacks;
      if (!Number.isSafeInteger(availablePacks) || availablePacks < 0) {
        throw new HttpsError("failed-precondition", `Authoritative pack stock is missing for ${product.name ?? line.productId}.`);
      }
      if (line.packs > availablePacks) {
        throw new HttpsError("failed-precondition", `Insufficient stock for ${product.name ?? line.productId}: ${availablePacks} packs available.`);
      }
      if (!Number.isSafeInteger(line.packs * packSize)) throw new HttpsError("invalid-argument", "Bottle equivalent exceeds the supported integer range.");
      return {
        productId: line.productId,
        productName: String(product.name ?? line.productId),
        packSizeSnapshot: packSize,
        packs: line.packs,
        bottles: line.packs * packSize,
        availablePacks,
      };
    });
    tx.create(dispatchRef, {
      organisationId: orgId, factoryId, idempotencyKey: key,
      dispatchReference: reference, dispatchDate: date, customerId, customerName,
      lines: postedLines.map(({ availablePacks: _available, ...line }) => line),
      remarks: typeof data.remarks === "string" ? data.remarks.slice(0, 2000) : "",
      status: "POSTED", actorUid: request.auth!.uid, actorRole: role,
      createdAt: FieldValue.serverTimestamp(), reversalId: null,
    });
    postedLines.forEach((line, index) => {
      tx.update(stockRefs[index], {
        completePacks: line.availablePacks - line.packs,
        updatedAt: FieldValue.serverTimestamp(),
      });
      const movementRef = db.collection(`${root}/inventoryTransactions`).doc();
      tx.create(movementRef, {
        organisationId: orgId, factoryId, productId: line.productId,
        productNameSnapshot: line.productName, quantity: -line.packs, unit: "PACK",
        transactionType: "DISPATCH_OUT", sourceType: "dispatch", sourceId: key,
        idempotencyKey: `${key}:${line.productId}`, actorUid: request.auth!.uid,
        createdAt: FieldValue.serverTimestamp(),
      });
    });
    return { dispatchId: dispatchRef.id, status: "POSTED", duplicate: false };
  });
  return result;
});

export const reverseDispatch = onCall({ enforceAppCheck: true }, async (request) => {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in required.");
  const orgId = requiredString(request.data?.organisationId, "organisationId");
  const factoryId = requiredString(request.data?.factoryId, "factoryId");
  const dispatchId = requiredString(request.data?.dispatchId, "dispatchId");
  const reason = requiredString(request.data?.reason, "reason");
  const { role } = await authorize(request.auth.uid, orgId, factoryId, ["owner", "admin", "manager"]);
  const root = `organisations/${orgId}/factories/${factoryId}`;
  const dispatchRef = db.doc(`${root}/dispatches/${dispatchId}`);
  const reversalRef = db.doc(`${root}/dispatchReversals/${dispatchId}`);
  return db.runTransaction(async (tx) => {
    const dispatchSnap = await tx.get(dispatchRef);
    const reversalSnap = await tx.get(reversalRef);
    if (!dispatchSnap.exists) throw new HttpsError("not-found", "Dispatch not found.");
    if (reversalSnap.exists) return { reversed: false, duplicate: true };
    const dispatch = dispatchSnap.data()!;
    if (dispatch.status !== "POSTED") throw new HttpsError("failed-precondition", "Only posted dispatches can be reversed.");
    if (dispatch.organisationId !== orgId || dispatch.factoryId !== factoryId) {
      throw new HttpsError("permission-denied", "Dispatch scope mismatch.");
    }
    const lines = dispatch.lines as Array<{productId: string; productName: string; packs: number; bottles: number}>;
    if (!Array.isArray(lines) || lines.length === 0) throw new HttpsError("failed-precondition", "Dispatch lines are missing.");
    const stockRefs = lines.map((line) => db.doc(`${root}/stock/${line.productId}`));
    const stockSnaps = await Promise.all(stockRefs.map((ref) => tx.get(ref)));
    lines.forEach((line, index) => {
      if (!Number.isSafeInteger(line.packs) || line.packs <= 0 || !stockSnaps[index].exists) {
        throw new HttpsError("failed-precondition", "Cannot safely restore inventory; stock record is missing.");
      }
    });
    tx.create(reversalRef, {
      organisationId: orgId, factoryId, dispatchId, reason,
      actorUid: request.auth!.uid, actorRole: role, createdAt: FieldValue.serverTimestamp(),
    });
    tx.update(dispatchRef, { status: "REVERSED", reversalId: reversalRef.id, reversedAt: FieldValue.serverTimestamp() });
    lines.forEach((line, index) => {
      const current = stockSnaps[index].data()!.completePacks;
      if (!Number.isSafeInteger(current) || current < 0) throw new HttpsError("failed-precondition", "Stock balance is invalid.");
      tx.update(stockRefs[index], { completePacks: current + line.packs, updatedAt: FieldValue.serverTimestamp() });
      tx.create(db.collection(`${root}/inventoryTransactions`).doc(), {
        organisationId: orgId, factoryId, productId: line.productId,
        productNameSnapshot: line.productName, quantity: line.packs, unit: "PACK",
        transactionType: "DISPATCH_REVERSAL_IN", sourceType: "dispatchReversal",
        sourceId: dispatchId, idempotencyKey: `reverse:${dispatchId}:${line.productId}`,
        reason, actorUid: request.auth!.uid, createdAt: FieldValue.serverTimestamp(),
      });
    });
    return { reversed: true, duplicate: false };
  });
});
