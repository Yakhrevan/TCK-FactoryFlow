import { firestoreGet, firestoreList, firestorePatch, firestoreBeginTransaction, firestoreBatchGet, firestoreCommit, firestoreRollback } from "./firestore.js";
import {
  createRemoteJWKSet,
  jwtVerify,
  SignJWT,
  importPKCS8
} from "jose";

const PROJECT_ID = "tck-admin-ac864";
const FIREBASE_ISSUER =
  `https://securetoken.google.com/${PROJECT_ID}`;

const FIREBASE_JWKS = createRemoteJWKSet(
  new URL(
    "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"
  )
);

async function verifyFirebaseToken(request) {
  const authorization = request.headers.get("Authorization");

  if (!authorization || !authorization.startsWith("Bearer ")) {
    throw new Error("AUTH_MISSING");
  }

  const token = authorization.slice(7);

  const { payload } = await jwtVerify(token, FIREBASE_JWKS, {
    audience: PROJECT_ID,
    issuer: FIREBASE_ISSUER,
  });

  if (!payload.sub) {
    throw new Error("AUTH_INVALID");
  }

  return payload;
}

async function getGoogleAccessToken(env) {
  const serviceAccount = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT);

  const privateKey = await importPKCS8(
    serviceAccount.private_key,
    "RS256"
  );

  const now = Math.floor(Date.now() / 1000);

  const assertion = await new SignJWT({
    scope: "https://www.googleapis.com/auth/datastore",
  })
    .setProtectedHeader({ alg: "RS256", typ: "JWT" })
    .setIssuer(serviceAccount.client_email)
    .setAudience("https://oauth2.googleapis.com/token")
    .setIssuedAt(now)
    .setExpirationTime(now + 3600)
    .sign(privateKey);

  const response = await fetch(
    "https://oauth2.googleapis.com/token",
    {
      method: "POST",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded",
      },
      body: new URLSearchParams({
        grant_type:
          "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion,
      }),
    }
  );

  const result = await response.json();

  if (!response.ok || !result.access_token) {
    throw new Error("GOOGLE_TOKEN_FAILED");
  }

  return result.access_token;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    // Worker health check
    if (request.method === "GET" && url.pathname === "/") {
      return Response.json({
        success: true,
        service: "TCK FactoryFlow API",
        message: "Cloudflare Worker is running.",
      });
    }

    // Firebase token verification
    if (
      request.method === "GET" &&
      url.pathname === "/api/auth/verify"
    ) {
      try {
        const user = await verifyFirebaseToken(request);

        return Response.json({
          success: true,
          authenticated: true,
          user: {
            uid: user.sub,
            email: user.email || null,
            emailVerified: user.email_verified === true,
          },
        });
      } catch {
        return Response.json(
          {
            success: false,
            error: "Invalid or missing Firebase authentication token.",
          },
          { status: 401 }
        );
      }
    }

    // Protected, read-only Firestore connectivity test
    if (
      request.method === "GET" &&
      url.pathname === "/api/firestore/test"
    ) {
      try {
        await verifyFirebaseToken(request);

        const accessToken = await getGoogleAccessToken(env);

        const response = await fetch(
          `https://firestore.googleapis.com/v1/projects/${PROJECT_ID}/databases/(default)/documents:listCollectionIds`,
          {
            method: "POST",
            headers: {
              Authorization: `Bearer ${accessToken}`,
              "Content-Type": "application/json",
            },
            body: JSON.stringify({
              pageSize: 100,
            }),
          }
        );

        const result = await response.json();

        if (!response.ok) {
          return Response.json(
            {
              success: false,
              error: "Firestore API request failed.",
              status: response.status,
            },
            { status: 502 }
          );
        }

        return Response.json({
          success: true,
          firestoreConnected: true,
          projectId: PROJECT_ID,
          collections: result.collectionIds || [],
        });
      } catch (error) {
        const missingAuth = error.message === "AUTH_MISSING";

        return Response.json(
          {
            success: false,
            error: missingAuth
              ? "Firebase authentication token is required."
              : "Firestore connection test failed.",
          },
          { status: missingAuth ? 401 : 500 }
        );
      }
    }

    // Inventory snapshot endpoint
    if (
      request.method === "POST" &&
      url.pathname === "/api/inventory/snapshot"
    ) {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();

        const orgId = body.organisationId;
        const factoryId = body.factoryId;

        if (
          typeof orgId !== "string" ||
          typeof factoryId !== "string" ||
          !orgId ||
          !factoryId
        ) {
          return Response.json(
            { success: false, error: "organisationId and factoryId are required." },
            { status: 400 }
          );
        }

        const accessToken = await getGoogleAccessToken(env);

        const memberPath =
          `organisations/${orgId}/members/${user.sub}`;

        const member = await firestoreGet(
          env,
          memberPath,
          accessToken
        );

        if (!member || !member.fields) {
          return Response.json(
            { success: false, error: "Organisation membership required." },
            { status: 403 }
          );
        }

        const fields = member.fields;
        const active = fields.active?.booleanValue === true;
        const memberOrg = fields.organisationId?.stringValue;
        const role = fields.role?.stringValue;
        const factoryIds =
          fields.factoryIds?.arrayValue?.values || [];

        const permittedRoles = [
          "owner", "admin", "manager", "operator",
          "production", "production_staff",
          "packing", "packing_staff",
          "storekeeper", "dispatch", "dispatch_staff",
          "viewer"
        ];

        const hasExplicitFactory = factoryIds.some(
          item => item.stringValue === factoryId
        );

        // Match Android's existing admin/owner fallback.
        const hasFactory =
          hasExplicitFactory ||
          (
            factoryIds.length === 0 &&
            (role === "admin" || role === "owner") &&
            factoryId === "factory-01"
          );

        if (
          !active ||
          memberOrg !== orgId ||
          !hasFactory ||
          !permittedRoles.includes(role)
        ) {
          return Response.json(
            { success: false, error: "Permission denied for this factory." },
            { status: 403 }
          );
        }

        const root =
          `organisations/${orgId}/factories/${factoryId}`;

        const [products, stocks] = await Promise.all([
          firestoreList(env, `${root}/products`, accessToken),
          firestoreList(env, `${root}/stock`, accessToken)
        ]);

        const stockById = new Map(
          stocks.map(doc => [
            doc.name.split("/").pop(),
            doc.fields || {}
          ])
        );

        const items = products.map(doc => {
          const productId = doc.name.split("/").pop();
          const p = doc.fields || {};
          const stock = stockById.get(productId) || {};

          return {
            productId,
            name: p.name?.stringValue || productId,
            sizeLabel: p.sizeLabel?.stringValue || "",
            colour: p.colour?.stringValue || "",
            bottlesPerPack: Number(
              p.bottlesPerPack?.integerValue || 0
            ),
            active: p.active?.booleanValue === true,
            unpackedGoodBottles: Number(
              stock.unpackedGoodBottles?.integerValue || 0
            ),
            completePacks: Number(
              stock.completePacks?.integerValue || 0
            ),
            looseBottles: Number(
              stock.looseBottles?.integerValue || 0
            ),
            updatedAt: stock.updatedAt?.timestampValue || null
          };
        });

        return Response.json({
          success: true,
          organisationId: orgId,
          factoryId,
          serverTime: new Date().toISOString(),
          items
        });
      } catch (error) {
        const missingAuth = error.message === "AUTH_MISSING";

        return Response.json(
          {
            success: false,
            error: missingAuth
              ? "Firebase authentication token is required."
              : "Inventory snapshot request failed."
          },
          { status: missingAuth ? 401 : 500 }
        );
      }
    }

    // Product master upsert endpoint
    if (request.method === "POST" && url.pathname === "/api/products/upsert") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();

        const { organisationId: orgId, factoryId, productId, name, sizeLabel, colour } = body;
        const packSize = body.bottlesPerPack;
        const active = body.active === true;

        if (
          ![orgId, factoryId, productId, name, sizeLabel, colour]
            .every(v => typeof v === "string" && v.trim()) ||
          !Number.isSafeInteger(packSize) || packSize < 0
        ) {
          return Response.json({ success: false, error: "Invalid product input." }, { status: 400 });
        }

        const accessToken = await getGoogleAccessToken(env);
        const member = await firestoreGet(
          env,
          `organisations/${orgId}/members/${user.sub}`,
          accessToken
        );
        const f = member?.fields || {};
        const role = f.role?.stringValue || "";
        const factoryIds = f.factoryIds?.arrayValue?.values || [];

        const hasFactory =
          factoryIds.some(x => x.stringValue === factoryId) ||
          (factoryIds.length === 0 &&
            ["admin", "owner"].includes(role) &&
            factoryId === "factory-01");

        if (
          f.active?.booleanValue !== true ||
          f.organisationId?.stringValue !== orgId ||
          !hasFactory ||
          !["owner", "admin", "manager"].includes(role)
        ) {
          return Response.json(
            { success: false, error: "Permission denied for this factory." },
            { status: 403 }
          );
        }

        const root = `organisations/${orgId}/factories/${factoryId}`;
        const productPath = `${root}/products/${productId}`;
        const stockPath = `${root}/stock/${productId}`;

        const [existing, stock] = await Promise.all([
          firestoreGet(env, productPath, accessToken),
          firestoreGet(env, stockPath, accessToken)
        ]);

        const oldPackSize = Number(existing?.fields?.bottlesPerPack?.integerValue || 0);
        if (existing && oldPackSize !== packSize && stock) {
          const sf = stock.fields || {};
          const quantities = [
            Number(sf.unpackedGoodBottles?.integerValue || 0),
            Number(sf.completePacks?.integerValue || 0),
            Number(sf.looseBottles?.integerValue || 0)
          ];
          if (quantities.some(v => v > 0)) {
            return Response.json(
              { success: false, error: "Pack size cannot change while stock exists. Complete a controlled inventory adjustment first." },
              { status: 409 }
            );
          }
        }

        const now = new Date().toISOString();
        const createdAt = existing?.fields?.createdAt?.timestampValue || now;

        await firestorePatch(env, productPath, {
          organisationId: { stringValue: orgId },
          factoryId: { stringValue: factoryId },
          productId: { stringValue: productId },
          name: { stringValue: name.trim() },
          sizeLabel: { stringValue: sizeLabel.trim() },
          colour: { stringValue: colour.trim() },
          bottlesPerPack: { integerValue: String(packSize) },
          active: { booleanValue: active },
          updatedBy: { stringValue: user.sub },
          updatedRole: { stringValue: role },
          updatedAt: { timestampValue: now },
          createdAt: { timestampValue: createdAt }
        }, accessToken);

        return Response.json({ success: true, productId, active, updated: true });
      } catch (error) {
        const missingAuth = error.message === "AUTH_MISSING";
        return Response.json(
          { success: false, error: missingAuth ? "Firebase authentication token is required." : "Product upsert failed." },
          { status: missingAuth ? 401 : 500 }
        );
      }
    }

    // Production posting endpoint
    if (request.method === "POST" && url.pathname === "/api/production/post") {
      let transaction;

      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();

        const {
          organisationId: orgId,
          factoryId,
          idempotencyKey: key,
          productId,
          productionDate: date,
          producedBottles: produced,
          goodBottles: good,
          rejectedBottles: rejected
        } = body;

        const machineId = typeof body.machineId === "string" ? body.machineId : "";
        const lineId = typeof body.lineId === "string" ? body.lineId : "";
        const machineName = typeof body.machineName === "string" ? body.machineName.slice(0, 180) : "";
        const lineName = typeof body.lineName === "string" ? body.lineName.slice(0, 180) : "";
        const remarks = typeof body.remarks === "string" ? body.remarks.slice(0, 2000) : "";

        const validString = v => typeof v === "string" && v.trim().length > 0;
        const validInteger = v => Number.isSafeInteger(v) && v >= 0;

        if (
          ![orgId, factoryId, key, productId, date].every(validString) ||
          !validInteger(produced) || produced <= 0 ||
          !validInteger(good) || !validInteger(rejected) ||
          good + rejected > produced ||
          !Number.isSafeInteger(good + rejected) ||
          !/^\d{4}-\d{2}-\d{2}$/.test(date)
        ) {
          return Response.json(
            { success: false, error: "Invalid production input." },
            { status: 400 }
          );
        }

        const fingerprint = JSON.stringify({
          orgId, factoryId, actorUid: user.sub, productId, date,
          produced, good, rejected, machineId, lineId, machineName, lineName, remarks
        });

        const accessToken = await getGoogleAccessToken(env);
        const member = await firestoreGet(
          env,
          `organisations/${orgId}/members/${user.sub}`,
          accessToken
        );

        const fields = member?.fields || {};
        const role = fields.role?.stringValue || "";
        const factoryIds = fields.factoryIds?.arrayValue?.values || [];
        const hasFactory =
          factoryIds.some(x => x.stringValue === factoryId) ||
          (factoryIds.length === 0 &&
            ["admin", "owner"].includes(role) &&
            factoryId === "factory-01");

        if (
          fields.active?.booleanValue !== true ||
          fields.organisationId?.stringValue !== orgId ||
          !hasFactory ||
          !["owner", "admin", "manager", "operator", "production", "production_staff"].includes(role)
        ) {
          return Response.json(
            { success: false, error: "Insufficient permissions." },
            { status: 403 }
          );
        }

        const root = `organisations/${orgId}/factories/${factoryId}`;
        const runPath = `${root}/productions/${key}`;
        const productPath = `${root}/products/${productId}`;
        const stockPath = `${root}/stock/${productId}`;
        const machinePath = machineId ? `${root}/machines/${machineId}` : null;

        transaction = await firestoreBeginTransaction(accessToken);

        const readPaths = [runPath, productPath, stockPath];
        if (machinePath) readPaths.push(machinePath);

        const batchResults = await firestoreBatchGet(
          accessToken,
          transaction,
          readPaths
        );


        const snapshots = new Map();
        for (const item of batchResults) {
          if (item.found) {
            const path = item.found.name.split("/documents/")[1];
            snapshots.set(path, item.found);
          } else if (item.missing) {
            const path = item.missing.split("/documents/")[1];
            snapshots.set(path, null);
          }
        }

        const existing = snapshots.get(runPath) || null;
        const productSnap = snapshots.get(productPath) || null;
        const stockSnap = snapshots.get(stockPath) || null;
        const machineSnap = machinePath
          ? snapshots.get(machinePath) || null
          : null;

        if (existing) {
          const prior = existing.fields || {};
          if (
            prior.actorUid?.stringValue !== user.sub ||
            prior.organisationId?.stringValue !== orgId ||
            prior.factoryId?.stringValue !== factoryId ||
            prior.requestFingerprint?.stringValue !== fingerprint
          ) {
            await firestoreRollback(accessToken, transaction);
            transaction = null;
            return Response.json(
              { success: false, error: "Idempotency key already used for a different production run." },
              { status: 409 }
            );
          }

          await firestoreRollback(accessToken, transaction);
          transaction = null;
          return Response.json({
            success: true,
            productionId: key,
            status: prior.status?.stringValue || "POSTED",
            duplicate: true
          });
        }

        if (!productSnap || productSnap.fields?.active?.booleanValue !== true) {
          await firestoreRollback(accessToken, transaction);
          transaction = null;
          return Response.json(
            { success: false, error: "Product is missing or inactive." },
            { status: 409 }
          );
        }

        if (machinePath && (!machineSnap || machineSnap.fields?.status?.stringValue !== "RUNNING")) {
          await firestoreRollback(accessToken, transaction);
          transaction = null;
          return Response.json(
            { success: false, error: "Selected machine is missing or not running." },
            { status: 409 }
          );
        }

        const stock = stockSnap?.fields || {};
        const unpacked = Number(stock.unpackedGoodBottles?.integerValue || 0);

        if (
          !Number.isSafeInteger(unpacked) || unpacked < 0 ||
          !Number.isSafeInteger(unpacked + good)
        ) {
          await firestoreRollback(accessToken, transaction);
          transaction = null;
          return Response.json(
            { success: false, error: "Unpacked bottle stock is invalid." },
            { status: 409 }
          );
        }

        const name = productSnap.fields?.name?.stringValue || productId;
        const now = new Date().toISOString();

        const writes = [
          {
            update: {
              name: `projects/${PROJECT_ID}/databases/(default)/documents/${runPath}`,
              fields: {
                organisationId: { stringValue: orgId },
                factoryId: { stringValue: factoryId },
                idempotencyKey: { stringValue: key },
                requestFingerprint: { stringValue: fingerprint },
                productId: { stringValue: productId },
                productNameSnapshot: { stringValue: name },
                productionDate: { stringValue: date },
                producedBottles: { integerValue: String(produced) },
                goodBottles: { integerValue: String(good) },
                rejectedBottles: { integerValue: String(rejected) },
                machineId: { stringValue: machineId },
                lineId: { stringValue: lineId },
                machineName: { stringValue: machineName },
                lineName: { stringValue: lineName },
                remarks: { stringValue: remarks },
                status: { stringValue: "POSTED" },
                actorUid: { stringValue: user.sub },
                actorRole: { stringValue: role },
                createdAt: { timestampValue: now },
                reversalId: { nullValue: null }
              }
            },
            currentDocument: { exists: false }
          },
          {
            update: {
              name: `projects/${PROJECT_ID}/databases/(default)/documents/${stockPath}`,
              fields: {
                organisationId: { stringValue: orgId },
                factoryId: { stringValue: factoryId },
                productId: { stringValue: productId },
                unpackedGoodBottles: { integerValue: String(unpacked + good) },
                completePacks: stock.completePacks || { integerValue: "0" },
                looseBottles: stock.looseBottles || { integerValue: "0" },
                updatedAt: { timestampValue: now }
              }
            },
            currentDocument: stockSnap
              ? { exists: true }
              : { exists: false }
          }
        ];

        const transactionPath =
          `projects/${PROJECT_ID}/databases/(default)/documents/${root}/inventoryTransactions`;

        if (good > 0) {
          writes.push({
            update: {
              name: `${transactionPath}/${key}-${productId}-good`,
              fields: {
                organisationId: { stringValue: orgId },
                factoryId: { stringValue: factoryId },
                productId: { stringValue: productId },
                productNameSnapshot: { stringValue: name },
                quantity: { integerValue: String(good) },
                unit: { stringValue: "BOTTLE" },
                transactionType: { stringValue: "PRODUCTION_GOOD_IN" },
                sourceType: { stringValue: "production" },
                sourceId: { stringValue: key },
                idempotencyKey: { stringValue: `${key}:${productId}:good` },
                actorUid: { stringValue: user.sub },
                createdAt: { timestampValue: now }
              }
            },
            currentDocument: { exists: false }
          });
        }

        if (rejected > 0) {
          writes.push({
            update: {
              name: `${transactionPath}/${key}-${productId}-rejected`,
              fields: {
                organisationId: { stringValue: orgId },
                factoryId: { stringValue: factoryId },
                productId: { stringValue: productId },
                productNameSnapshot: { stringValue: name },
                quantity: { integerValue: String(-rejected) },
                unit: { stringValue: "BOTTLE" },
                transactionType: { stringValue: "PRODUCTION_REJECTED" },
                sourceType: { stringValue: "production" },
                sourceId: { stringValue: key },
                idempotencyKey: { stringValue: `${key}:${productId}:rejected` },
                actorUid: { stringValue: user.sub },
                createdAt: { timestampValue: now }
              }
            },
            currentDocument: { exists: false }
          });
        }

        await firestoreCommit(accessToken, transaction, writes);
        transaction = null;

        return Response.json({
          success: true,
          productionId: key,
          status: "POSTED",
          duplicate: false,
          unpackedGoodBottles: unpacked + good
        });
      } catch (error) {
        if (transaction) {
          try {
            const token = await getGoogleAccessToken(env);
            await firestoreRollback(token, transaction);
          } catch {}
        }

        const missingAuth = error.message === "AUTH_MISSING";
        console.error("Production posting error:", error?.message || error);
        return Response.json(
          {
            success: false,
            error: missingAuth
              ? "Firebase authentication token is required."
              : `Production posting failed: ${String(error?.message || error).slice(0, 300)}`
          },
          { status: missingAuth ? 401 : 500 }
        );
      }
    }


    return Response.json(
      {
        success: false,
        error: "Endpoint not found",
      },
      { status: 404 }
    );
  },
};
