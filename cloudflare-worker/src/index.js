import { firestoreGet, firestoreList, firestorePatch, firestoreBeginTransaction, firestoreBatchGet, firestoreCommit, firestoreRollback, firestoreDelete, toFirestoreValue, fromFirestoreDoc } from "./firestore.js";
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

function validateSyncEventInput(eventKey, eventType, payload) {
  if (!eventKey || eventKey.length > 150 ||
      !/^[A-Za-z0-9:_-]+$/.test(eventKey)) {
    return "Invalid event key.";
  }

  if (!eventType || eventType.length > 100) {
    return "Invalid event type.";
  }

  if (new TextEncoder().encode(payload).length > 700 * 1024) {
    return "Event payload exceeds the 700 KB limit.";
  }

  return null;
}

async function verifyFirebaseToken(request) {
  const authorization = request.headers.get("Authorization");

  if (!authorization || !authorization.startsWith("Bearer ")) {
    throw new Error("AUTH_MISSING");
  }

  const token = authorization.slice(7);

  let payload;

  try {
    ({ payload } = await jwtVerify(token, FIREBASE_JWKS, {
      audience: PROJECT_ID,
      issuer: FIREBASE_ISSUER,
    }));
  } catch {
    throw new Error("AUTH_INVALID");
  }

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
    scope: "https://www.googleapis.com/auth/datastore https://www.googleapis.com/auth/identitytoolkit",
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

async function setFirebaseCustomClaims(env, uid, claims, accessToken) {
  if (!uid) return;
  const url = `https://identitytoolkit.googleapis.com/v1/projects/${PROJECT_ID}:setCustomUserClaims`;
  const response = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      localId: uid,
      customAttributes: JSON.stringify(claims),
    }),
  });

  if (!response.ok) {
    const errorText = await response.text();
    console.error("Failed to set Firebase custom claims:", response.status, errorText);
    throw new Error("FIREBASE_SET_CLAIMS_FAILED");
  }

  return await response.json();
}

async function authorizeMember(env, user, orgId, factoryId, allowedRoles, accessToken) {
  if (!orgId || typeof orgId !== "string" || orgId.includes("/") || orgId.length > 150) {
    return { ok: false, status: 400, error: "Invalid organisation ID." };
  }
  if (!factoryId || typeof factoryId !== "string" || factoryId.includes("/") || factoryId.length > 150) {
    return { ok: false, status: 400, error: "Valid factory ID is required." };
  }
  const memberPath = `organisations/${orgId}/members/${user.sub}`;
  const member = await firestoreGet(env, memberPath, accessToken);
  if (!member || !member.fields) {
    return { ok: false, status: 403, error: "Organisation membership required." };
  }
  const f = member.fields;
  if (f.uid?.stringValue !== user.sub) {
    return { ok: false, status: 403, error: "Authenticated member UID mismatch." };
  }
  if (f.active?.booleanValue !== true) {
    return { ok: false, status: 403, error: "Account is inactive." };
  }
  if (f.organisationId?.stringValue !== orgId) {
    return { ok: false, status: 403, error: "Organisation mismatch." };
  }
  const role = f.role?.stringValue || "";
  const factoryIds = (f.factoryIds?.arrayValue?.values || []).map(x => x.stringValue);
  if (!factoryIds.includes(factoryId)) {
    return { ok: false, status: 403, error: "Permission denied for this factory." };
  }
  if (allowedRoles && allowedRoles.length > 0 && !allowedRoles.includes(role)) {
    return { ok: false, status: 403, error: `Role '${role}' not authorized for this operation.` };
  }
  return { ok: true, role, member: f };
}

function objectToFirestoreFields(obj) {
  const fields = {};
  for (const [k, v] of Object.entries(obj)) {
    if (v !== undefined) {
      fields[k] = toFirestoreValue(v);
    }
  }
  return fields;
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


    // Fetch authenticated user's organisation membership
    if (request.method === "POST" && url.pathname === "/api/auth/member") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json().catch(() => ({}));
        let orgId = String(body?.organisationId || user.organisationId || "").trim();

        if (orgId && (orgId.length > 150 || orgId.includes("/"))) {
          return Response.json(
            {success:false,error:"Invalid organisation ID."},
            {status:400}
          );
        }

        const accessToken = await getGoogleAccessToken(env);
        let doc = null;

        if (orgId) {
          const path = `organisations/${orgId}/members/${user.sub}`;
          doc = await firestoreGet(env, path, accessToken);
        } else {
          // If no organisation was provided in the token or request, look up user membership in known default organisation
          const defaultOrgs = ["tck-factory-01"];
          for (const candOrg of defaultOrgs) {
            const path = `organisations/${candOrg}/members/${user.sub}`;
            const candDoc = await firestoreGet(env, path, accessToken);
            if (candDoc?.fields) {
              doc = candDoc;
              orgId = candOrg;
              break;
            }
          }
        }

        if (!doc?.fields) {
          return Response.json({success:false,error:"Member profile not found."},{status:404});
        }

        const f = doc.fields;

        console.log("ADMIN MEMBERSHIP DIAGNOSTIC", {
          tokenUid: user.sub,
          documentUid: f.uid?.stringValue ?? null,
          active: f.active?.booleanValue ?? null,
          documentOrg: f.organisationId?.stringValue ?? null,
          requestedOrg: orgId,
          uidMatch: f.uid?.stringValue === user.sub,
          activeMatch: f.active?.booleanValue === true,
          orgMatch: f.organisationId?.stringValue === orgId
        });

        if (
          f.uid?.stringValue !== user.sub ||
          f.active?.booleanValue !== true ||
          f.organisationId?.stringValue !== orgId
        ) {
          return Response.json(
            {success:false,error:"Member is inactive or organisation access is invalid."},
            {status:403}
          );
        }

        const member = {
          uid: f.uid.stringValue,
          name: f.name?.stringValue || "",
          email: f.email?.stringValue || user.email || "",
          role: f.role?.stringValue || "",
          allowedModules: f.allowedModules?.stringValue || "ALL",
          active: true,
          organisationId: f.organisationId.stringValue,
          factoryIds: (f.factoryIds?.arrayValue?.values || []).map(x => x.stringValue || "")
        };

        // If custom claims are not present on the user's token, synchronize them now
        if (!user.organisationId && member.organisationId) {
          await setFirebaseCustomClaims(env, user.sub, {
            organisationId: member.organisationId,
            role: member.role,
            factoryIds: member.factoryIds
          }, accessToken).catch(err => console.error("Auto-sync claims error:", err));
        }

        return Response.json({success:true,member});
      } catch(error) {
        console.error("Member fetch error:",error);

        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        const invalidJson = error instanceof SyntaxError;

        return Response.json(
          {
            success:false,
            error: authError
              ? "Firebase authentication is required or expired."
              : invalidJson
                ? "Invalid JSON request body."
                : "Member fetch failed."
          },
          {status: authError ? 401 : invalidJson ? 400 : 500}
        );
      }
    }

    // Bootstrap first administrator membership
    if (request.method === "POST" && url.pathname === "/api/auth/bootstrap-admin") {
      try {
        const user = await verifyFirebaseToken(request);
        await request.json().catch(() => ({}));
        const orgId = "tck-factory-01";
        const factoryId = "factory-01";
        const name = "Administrator";
        const email = String(user.email || "").toLowerCase();
        const allowedBootstrap =
          email === "admin@tck-factory.com";

        if (!allowedBootstrap) {
          return Response.json({success:false,error:"Bootstrap is restricted."},{status:403});
        }

        const accessToken = await getGoogleAccessToken(env);
        const memberPath = `organisations/${orgId}/members/${user.sub}`;
        const lockPath = `organisations/${orgId}/system/bootstrap-admin`;
        const transaction = await firestoreBeginTransaction(accessToken);

        try {
          const lock = await firestoreGet(
            env, lockPath, accessToken, transaction
          );

          if (lock?.fields?.completed?.booleanValue === true) {
            await firestoreRollback(accessToken, transaction);
            return Response.json({
              success:false,
              error:"Bootstrap has already been completed."
            }, {status:403});
          }

          const existing = await firestoreGet(
            env, memberPath, accessToken, transaction
          );

          if (existing?.fields) {
            const f = existing.fields;

            await firestoreRollback(accessToken, transaction);

            if (
              f.uid?.stringValue !== user.sub ||
              f.role?.stringValue !== "admin" ||
              f.active?.booleanValue !== true ||
              f.organisationId?.stringValue !== orgId
            ) {
              return Response.json({
                success:false,
                error:"Existing member is not eligible for admin bootstrap."
              }, {status:403});
            }

            // Sync custom claims to Firebase Auth
            await setFirebaseCustomClaims(env, user.sub, {
              organisationId: orgId,
              role: "admin",
              factoryIds: [factoryId]
            }, accessToken);

            return Response.json({
              success:true,
              created:false,
              member:existing.fields
            });
          }

          const members = await firestoreList(
            env, `organisations/${orgId}/members`, accessToken
          );

          if (members.length) {
            await firestoreRollback(accessToken, transaction);
            return Response.json({
              success:false,
              error:"Organisation already has members; bootstrap denied."
            }, {status:403});
          }

        const fields = {
          uid:{stringValue:user.sub},
          name:{stringValue:name},
          email:{stringValue:email},
          role:{stringValue:"admin"},
          allowedModules:{stringValue:"ALL"},
          active:{booleanValue:true},
          organisationId:{stringValue:orgId},
          factoryIds:{arrayValue:{values:[{stringValue:factoryId}]}},
          createdAt:{timestampValue:new Date().toISOString()},
          updatedAt:{timestampValue:new Date().toISOString()},
          createdBy:{stringValue:user.sub}
        };

        await firestoreCommit(accessToken, transaction, [
          {
            update: {
              name: `projects/${PROJECT_ID}/databases/(default)/documents/${lockPath}`,
              fields: {
                completed: {booleanValue:true},
                adminUid: {stringValue:user.sub},
                createdAt: {timestampValue:new Date().toISOString()}
              }
            },
            currentDocument: {exists:false}
          },
          {
            update: {
              name: `projects/${PROJECT_ID}/databases/(default)/documents/${memberPath}`,
              fields
            },
            currentDocument: {exists:false}
          }
        ]);

        // Set Firebase Auth custom claims for the bootstrapped administrator
        await setFirebaseCustomClaims(env, user.sub, {
          organisationId: orgId,
          role: "admin",
          factoryIds: [factoryId]
        }, accessToken);

        return Response.json({success:true,created:true,member:fields});
        } catch (txError) {
          await firestoreRollback(accessToken, transaction).catch(() => {});
          throw txError;
        }
      } catch(error) {
        console.error("Bootstrap admin error:",error);

        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        const emailNotVerified = error?.message === "AUTH_EMAIL_NOT_VERIFIED";
        const invalidJson = error instanceof SyntaxError;

        return Response.json(
          {
            success:false,
            error: emailNotVerified
              ? "Please verify your Firebase email before proceeding."
              : authError
                ? "Firebase authentication is required or expired."
                : invalidJson
                  ? "Invalid JSON request body."
                  : "Admin bootstrap failed."
          },
          {status: emailNotVerified ? 403 : authError ? 401 : invalidJson ? 400 : 500}
        );
      }
    }


    // Provision or update staff membership
    if (request.method === "POST" && url.pathname === "/api/auth/provision-member") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();

        const orgId = String(body.organisationId || "").trim();
        const factoryId = String(body.factoryId || "").trim();
        const targetUid = String(body.uid || "").trim();
        const name = String(body.name || "").trim();
        const email = String(body.email || "").trim().toLowerCase();
        const role = String(body.role || "").trim().toLowerCase();
        const allowedModules = String(body.allowedModules || "ALL").trim();
        const active = body.active === true;
        const createdBy = user.sub;

        const allowedRoles = [
          "admin", "manager", "production_staff", "packing_staff",
          "storekeeper", "dispatch_staff", "viewer", "operator",
          "production", "packing", "dispatch", "production_operator"
        ];

        if (
          !orgId || orgId.length > 150 || orgId.includes("/") ||
          !factoryId || factoryId.length > 150 || factoryId.includes("/") ||
          !targetUid || targetUid.length > 150 || targetUid.includes("/") ||
          !name || name.length > 150 ||
          !email || email.length > 254 ||
          !/^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$/.test(email) ||
          !allowedRoles.includes(role) ||
          !allowedModules || allowedModules.length > 100
        ) {
          return Response.json(
            {success:false,error:"Invalid member details."},
            {status:400}
          );
        }

        if (targetUid === user.sub) {
          return Response.json({success:false,error:"Cannot provision your own membership."},{status:403});
        }

        const accessToken = await getGoogleAccessToken(env);
        const actorPath = `organisations/${orgId}/members/${user.sub}`;
        const actor = await firestoreGet(env, actorPath, accessToken);
        const actorFields = actor?.fields || {};

        if (actorFields.uid?.stringValue !== user.sub) {
          return Response.json(
            {success:false,error:"Authenticated member UID mismatch."},
            {status:403}
          );
        }

        const actorRole = actorFields.role?.stringValue;
        const actorFactories = actorFields.factoryIds?.arrayValue?.values || [];

        const actorAllowed = actorFields.active?.booleanValue === true &&
          actorFields.organisationId?.stringValue === orgId &&
          ["owner","admin"].includes(actorRole) &&
          actorFactories.some(x => x.stringValue === factoryId);

        if (!actorAllowed) {
          return Response.json({success:false,error:"Only an active owner/admin with factory access can provision staff."},{status:403});
        }

        if (role === "admin" && actorRole !== "owner") {
          return Response.json({success:false,error:"Only owner can assign admin role."},{status:403});
        }

        const memberPath = `organisations/${orgId}/members/${targetUid}`;
        const now = new Date().toISOString();
        const fields = {
          uid:{stringValue:targetUid},
          name:{stringValue:name},
          email:{stringValue:email},
          role:{stringValue:role},
          allowedModules:{stringValue:allowedModules},
          active:{booleanValue:active},
          organisationId:{stringValue:orgId},
          factoryIds:{arrayValue:{values:[{stringValue:factoryId}]}},
          updatedAt:{timestampValue:now},
          updatedBy:{stringValue:user.sub}
        };

        const existing = await firestoreGet(env, memberPath, accessToken);
        const existingFields = existing?.fields || {};
        const existingRole = existingFields.role?.stringValue;

        if (existingFields.uid?.stringValue &&
            existingFields.uid.stringValue !== targetUid) {
          return Response.json(
            {success:false,error:"Member UID mismatch."},
            {status:409}
          );
        }

        if (existingRole === "owner") {
          return Response.json(
            {success:false,error:"Owner membership cannot be modified here."},
            {status:403}
          );
        }

        if (existingRole === "admin" && actorRole !== "owner") {
          return Response.json(
            {success:false,error:"Only owner can modify an admin."},
            {status:403}
          );
        }

        if (!existing?.fields) {
          fields.createdAt = {timestampValue:now};
          fields.createdBy = {stringValue:createdBy};
        }

        await firestorePatch(env, memberPath, fields, accessToken);

        // Synchronize custom claims on Firebase Auth
        await setFirebaseCustomClaims(env, targetUid, {
          organisationId: orgId,
          role,
          factoryIds: [factoryId]
        }, accessToken);

        return Response.json({success:true,uid:targetUid,role,active});
      } catch(error) {
        console.error("Provision member error:",error);

        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        const invalidJson = error instanceof SyntaxError;

        return Response.json(
          {
            success:false,
            error: authError
              ? "Firebase authentication is required or expired."
              : invalidJson
                ? "Invalid JSON request body."
                : "Member provisioning failed."
          },
          {status: authError ? 401 : invalidJson ? 400 : 500}
        );
      }
    }

    // Sync local outbox event with idempotency protection
    if (request.method === "POST" && url.pathname === "/api/sync/event") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();

        const orgId = String(body.organisationId || "").trim();
        const eventKey = String(body.eventKey || "").trim();
        const eventType = String(body.eventType || "").trim();
        const payload = String(body.payload || "");

        if (!orgId) {
          return Response.json(
            {success:false,error:"Missing organisation ID."},
            {status:400}
          );
        }

        const validationError =
          validateSyncEventInput(eventKey, eventType, payload);

        if (validationError) {
          return Response.json(
            {success:false,error:validationError},
            {status:400}
          );
        }

        const accessToken = await getGoogleAccessToken(env);
        const memberPath = `organisations/${orgId}/members/${user.sub}`;
        const member = await firestoreGet(env, memberPath, accessToken);
        const mf = member?.fields || {};

        if (mf.active?.booleanValue !== true ||
            mf.organisationId?.stringValue !== orgId) {
          return Response.json({success:false,error:"Active organisation membership required."},{status:403});
        }

        const eventPath = `organisations/${orgId}/events/${eventKey}`;
        const transaction = await firestoreBeginTransaction(accessToken);

        try {
          const existing = await firestoreGet(
            env, eventPath, accessToken, transaction
          );

          if (existing?.fields) {
            const f = existing.fields;
            const existingType = f.eventType?.stringValue || "";
            const existingPayload = f.payload?.stringValue || "";
            const existingOrg = f.organisationId?.stringValue || "";

            await firestoreRollback(accessToken, transaction);

            if (existingType !== eventType ||
                existingPayload !== payload ||
                existingOrg !== orgId) {
              return Response.json(
                {success:false,error:"Idempotency conflict: a different event already uses this key or organisation."},
                {status:409}
              );
            }

            return Response.json({success:true,duplicate:true,eventKey});
          }

          const fields = {
            eventKey:{stringValue:eventKey},
            eventType:{stringValue:eventType},
            payload:{stringValue:payload},
            actorUid:{stringValue:user.sub},
            organisationId:{stringValue:orgId},
            createdAt:{timestampValue:new Date().toISOString()}
          };

          await firestoreCommit(accessToken, transaction, [{
            update: {
              name: `projects/${PROJECT_ID}/databases/(default)/documents/${eventPath}`,
              fields
            },
            currentDocument: { exists: false }
          }]);

          return Response.json({success:true,duplicate:false,eventKey});
        } catch (transactionError) {
          await firestoreRollback(accessToken, transaction).catch(() => {});
          if (String(transactionError.message).includes("ALREADY_EXISTS") ||
              String(transactionError.message).includes("FAILED_PRECONDITION")) {
            return Response.json(
              {success:false,error:"Event was concurrently submitted. Retry safely."},
              {status:409}
            );
          }
          throw transactionError;
        }
      } catch(error) {
        console.error("Sync event error:",error);

        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        const invalidJson = error instanceof SyntaxError;

        return Response.json(
          {
            success:false,
            error: authError
              ? "Firebase authentication is required or expired."
              : invalidJson
                ? "Invalid JSON request body."
                : "Sync event failed."
          },
          {status: authError ? 401 : invalidJson ? 400 : 500}
        );
      }
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
        const user = await verifyFirebaseToken(request);
        let orgId = String(url.searchParams.get("organisationId") || user.organisationId || "").trim();

        const accessToken = await getGoogleAccessToken(env);

        if (!orgId) {
          const memberPath = `organisations/tck-factory-01/members/${user.sub}`;
          const memberDoc = await firestoreGet(env, memberPath, accessToken);
          if (memberDoc?.fields?.active?.booleanValue === true) {
            orgId = "tck-factory-01";
          }
        }

        if (!orgId) {
          return Response.json(
            { success: false, error: "Firebase token is missing organisationId." },
            { status: 403 }
          );
        }

        const memberPath = `organisations/${orgId}/members/${user.sub}`;
        const memberDoc = await firestoreGet(env, memberPath, accessToken);
        const member = memberDoc?.fields;

        if (
          !member ||
          member.uid?.stringValue !== user.sub ||
          member.active?.booleanValue !== true ||
          member.organisationId?.stringValue !== orgId
        ) {
          return Response.json(
            {success:false,error:"Active organisation membership required."},
            {status:403}
          );
        }

        const role = member.role?.stringValue || "";

        if (!["owner","admin"].includes(role)) {
          return Response.json(
            {success:false,error:"Owner or Admin role required."},
            {status:403}
          );
        }

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
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);

        return Response.json(
          {
            success: false,
            error: authError
              ? "Firebase authentication is required or expired."
              : "Firestore connection test failed.",
          },
          { status: authError ? 401 : 500 }
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
          !orgId || orgId.length > 150 || orgId.includes("/") ||
          !factoryId || factoryId.length > 150 || factoryId.includes("/")
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
        const hasFactory = hasExplicitFactory;

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

        const toSafeQuantity = value => {
          const quantity = Number(value ?? 0);
          return Number.isSafeInteger(quantity) && quantity >= 0
            ? quantity
            : null;
        };

        const items = products.map(doc => {
          const productId = doc.name.split("/").pop();
          const p = doc.fields || {};
          const stock = stockById.get(productId) || {};

          return {
            productId,
            name: p.name?.stringValue || productId,
            sizeLabel: p.sizeLabel?.stringValue || "",
            colour: p.colour?.stringValue || "",
            bottlesPerPack: toSafeQuantity(p.bottlesPerPack?.integerValue),
            active: p.active?.booleanValue === true,
            unpackedGoodBottles: toSafeQuantity(stock.unpackedGoodBottles?.integerValue),
            completePacks: toSafeQuantity(stock.completePacks?.integerValue),
            looseBottles: toSafeQuantity(stock.looseBottles?.integerValue),
            updatedAt: stock.updatedAt?.timestampValue || null
          };
        });

        if (items.some(item =>
          item.bottlesPerPack === null ||
          item.unpackedGoodBottles === null ||
          item.completePacks === null ||
          item.looseBottles === null
        )) {
          return Response.json(
            {success:false,error:"Inventory contains invalid quantity data."},
            {status:500}
          );
        }

        return Response.json({
          success: true,
          organisationId: orgId,
          factoryId,
          serverTime: new Date().toISOString(),
          items
        });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);

        return Response.json(
          {
            success: false,
            error: authError
              ? "Firebase authentication is required or expired."
              : "Inventory snapshot request failed."
          },
          { status: authError ? 401 : 500 }
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
          orgId.length > 150 ||
          factoryId.length > 150 ||
          productId.length > 150 ||
          orgId.includes("/") ||
          factoryId.includes("/") ||
          productId.includes("/") ||
          name.trim().length > 150 ||
          sizeLabel.trim().length > 50 ||
          colour.trim().length > 50 ||
          !Number.isSafeInteger(packSize) || packSize <= 0
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

        if (f.uid?.stringValue !== user.sub) {
          return Response.json(
            { success: false, error: "Authenticated member UID mismatch." },
            { status: 403 }
          );
        }

        const role = f.role?.stringValue || "";
        const factoryIds = f.factoryIds?.arrayValue?.values || [];

        const hasFactory =
          factoryIds.some(x => x.stringValue === factoryId);

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

        if (stock && !existing) {
          return Response.json(
            { success: false, error: "Stock exists without its product record. Restore product data before continuing." },
            { status: 409 }
          );
        }

        const oldPackSize = Number(existing?.fields?.bottlesPerPack?.integerValue || 0);
        if (existing && oldPackSize !== packSize && stock) {
          const sf = stock.fields || {};
          const quantities = [
            Number(sf.unpackedGoodBottles?.integerValue || 0),
            Number(sf.completePacks?.integerValue || 0),
            Number(sf.looseBottles?.integerValue || 0)
          ];

          if (!quantities.every(Number.isSafeInteger) ||
              quantities.some(v => v < 0)) {
            return Response.json(
              { success: false, error: "Existing stock contains invalid quantities. Correct the stock before changing pack size." },
              { status: 409 }
            );
          }

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
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json(
          { success: false, error: authError ? "Firebase authentication is required or expired." : "Product upsert failed." },
          { status: authError ? 401 : 500 }
        );
      }
    }

    // Packing posting endpoint
    if (request.method === "POST" && url.pathname === "/api/packing/post") {
      let transaction;

      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();

        const {
          organisationId: orgId,
          factoryId,
          idempotencyKey: key,
          productId,
          packingDate: date,
          bottlesConsumed: consumed
        } = body;

        const remarks = typeof body.remarks === "string"
          ? body.remarks.slice(0, 2000)
          : "";

        const validString = v =>
          typeof v === "string" && v.trim().length > 0;

        if (
          ![orgId, factoryId, key, productId, date].every(validString) ||
          orgId.length > 150 || orgId.includes("/") ||
          factoryId.length > 150 || factoryId.includes("/") ||
          key.length > 150 || key.includes("/") ||
          productId.length > 150 || productId.includes("/") ||
          !Number.isSafeInteger(consumed) ||
          consumed <= 0 ||
          !/^\d{4}-\d{2}-\d{2}$/.test(date)
        ) {
          return Response.json(
            { success: false, error: "Invalid packing input." },
            { status: 400 }
          );
        }

        const fingerprint = JSON.stringify({
          orgId,
          factoryId,
          actorUid: user.sub,
          productId,
          date,
          consumed,
          remarks
        });

        const accessToken = await getGoogleAccessToken(env);
        const member = await firestoreGet(
          env,
          `organisations/${orgId}/members/${user.sub}`,
          accessToken
        );

        const fields = member?.fields || {};

        if (fields.uid?.stringValue !== user.sub) {
          return Response.json(
            { success: false, error: "Authenticated member UID mismatch." },
            { status: 403 }
          );
        }

        const role = fields.role?.stringValue || "";
        const factoryIds = fields.factoryIds?.arrayValue?.values || [];
        const hasFactory =
          factoryIds.some(x => x.stringValue === factoryId);

        if (
          fields.active?.booleanValue !== true ||
          fields.organisationId?.stringValue !== orgId ||
          !hasFactory ||
          !["owner", "admin", "manager", "operator", "packing", "packing_staff"].includes(role)
        ) {
          return Response.json(
            { success: false, error: "Insufficient permissions." },
            { status: 403 }
          );
        }

        const root = `organisations/${orgId}/factories/${factoryId}`;
        const packingPath = `${root}/packings/${key}`;
        const productPath = `${root}/products/${productId}`;
        const stockPath = `${root}/stock/${productId}`;

        transaction = await firestoreBeginTransaction(accessToken);

        const batchResults = await firestoreBatchGet(
          accessToken,
          transaction,
          [packingPath, productPath, stockPath]
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

        const existing = snapshots.get(packingPath) || null;
        const productSnap = snapshots.get(productPath) || null;
        const stockSnap = snapshots.get(stockPath) || null;

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
              { success: false, error: "Idempotency key already used for a different packing." },
              { status: 409 }
            );
          }

          await firestoreRollback(accessToken, transaction);
          transaction = null;
          return Response.json({
            success: true,
            packingId: key,
            status: prior.status?.stringValue || "POSTED",
            duplicate: true
          });
        }

        if (!productSnap || productSnap.fields?.active?.booleanValue !== true) {
          throw new Error("Product is missing or inactive.");
        }

        const product = productSnap.fields;
        const packSize = Number(product.bottlesPerPack?.integerValue);
        if (!Number.isSafeInteger(packSize) || packSize <= 0) {
          throw new Error("Product pack size is not configured.");
        }

        const stock = stockSnap?.fields || {};
        const unpacked = Number(stock.unpackedGoodBottles?.integerValue || 0);
        const completePacks = Number(stock.completePacks?.integerValue || 0);
        const looseBottles = Number(stock.looseBottles?.integerValue || 0);
        const packs = Math.floor(consumed / packSize);
        const loose = consumed % packSize;

        if (
          !Number.isSafeInteger(unpacked) || unpacked < consumed ||
          !Number.isSafeInteger(completePacks) || completePacks < 0 ||
          !Number.isSafeInteger(looseBottles) || looseBottles < 0
        ) {
          throw new Error("Insufficient or invalid unpacked bottle stock.");
        }

        const name = product.name?.stringValue || productId;
        const now = new Date().toISOString();

        const packingFields = {
          organisationId: { stringValue: orgId },
          factoryId: { stringValue: factoryId },
          idempotencyKey: { stringValue: key },
          requestFingerprint: { stringValue: fingerprint },
          productId: { stringValue: productId },
          productNameSnapshot: { stringValue: name },
          packingDate: { stringValue: date },
          bottlesConsumed: { integerValue: String(consumed) },
          completePacks: { integerValue: String(packs) },
          looseBottles: { integerValue: String(loose) },
          remarks: { stringValue: remarks },
          status: { stringValue: "POSTED" },
          actorUid: { stringValue: user.sub },
          actorRole: { stringValue: role },
          createdAt: { timestampValue: now },
          reversalId: { nullValue: null }
        };

        const nextCompletePacks = completePacks + packs;
        const nextLooseBottles = looseBottles + loose;

        if (
          !Number.isSafeInteger(nextCompletePacks) ||
          !Number.isSafeInteger(nextLooseBottles)
        ) {
          throw new Error("Packing stock quantity overflow.");
        }

        const stockFields = {
          unpackedGoodBottles: { integerValue: String(unpacked - consumed) },
          completePacks: { integerValue: String(nextCompletePacks) },
          looseBottles: { integerValue: String(nextLooseBottles) },
          updatedAt: { timestampValue: now }
        };

        const writes = [
          {
            update: {
              name: `projects/${PROJECT_ID}/databases/(default)/documents/${stockPath}`,
              fields: stockFields
            }
          },
          {
            update: {
              name: `projects/${PROJECT_ID}/databases/(default)/documents/${packingPath}`,
              fields: packingFields
            },
            currentDocument: { exists: false }
          }
        ];

        const ledgerBase = {
          organisationId: { stringValue: orgId },
          factoryId: { stringValue: factoryId },
          productId: { stringValue: productId },
          productNameSnapshot: { stringValue: name },
          actorUid: { stringValue: user.sub },
          sourceType: { stringValue: "packing" },
          sourceId: { stringValue: key },
          createdAt: { timestampValue: now }
        };

        const ledgerPath = `${root}/inventoryTransactions`;
        const bottleOut = {
          ...ledgerBase,
          quantity: { integerValue: String(-consumed) },
          unit: { stringValue: "BOTTLE" },
          transactionType: { stringValue: "PACKING_BOTTLES_OUT" },
          idempotencyKey: { stringValue: `${key}:${productId}:bottles-out` }
        };

        writes.push({
          update: {
            name: `projects/${PROJECT_ID}/databases/(default)/documents/${ledgerPath}/${key}-bottles-out`,
            fields: bottleOut
          },
          currentDocument: { exists: false }
        });

        if (packs > 0) {
          writes.push({
            update: {
              name: `projects/${PROJECT_ID}/databases/(default)/documents/${ledgerPath}/${key}-packs-in`,
              fields: {
                ...ledgerBase,
                quantity: { integerValue: String(packs) },
                unit: { stringValue: "PACK" },
                transactionType: { stringValue: "PACKING_PACKS_IN" },
                idempotencyKey: { stringValue: `${key}:${productId}:packs-in` }
              }
            },
            currentDocument: { exists: false }
          });
        }

        if (loose > 0) {
          writes.push({
            update: {
              name: `projects/${PROJECT_ID}/databases/(default)/documents/${ledgerPath}/${key}-loose-in`,
              fields: {
                ...ledgerBase,
                quantity: { integerValue: String(loose) },
                unit: { stringValue: "BOTTLE" },
                transactionType: { stringValue: "PACKING_LOOSE_IN" },
                idempotencyKey: { stringValue: `${key}:${productId}:loose-in` }
              }
            },
            currentDocument: { exists: false }
          });
        }

        await firestoreCommit(accessToken, transaction, writes);
        transaction = null;

        return Response.json({
          success: true,
          packingId: key,
          status: "POSTED",
          duplicate: false,
          completePacks: packs,
          looseBottles: loose,
          bottlesPerPack: packSize
        });

      } catch (error) {
        if (transaction) {
          try {
            const token = await getGoogleAccessToken(env);
            await firestoreRollback(token, transaction);
          } catch (rollbackError) {
            console.error("Packing transaction rollback failed:", rollbackError?.message || rollbackError);
          }
        }

        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        console.error("Packing posting error:", error?.message || error);

        return Response.json({
          success: false,
          error: authError
            ? "Firebase authentication is required or expired."
            : "Packing posting failed."
        }, { status: authError ? 401 : 500 });
      }
    }


    // Dispatch posting endpoint
    if (request.method === "POST" && url.pathname === "/api/dispatch/post") {
      let transaction;
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const orgId = body.organisationId;
        const factoryId = body.factoryId;
        const key = body.idempotencyKey;
        const reference = body.dispatchReference;
        const date = body.dispatchDate;
        const customerId = body.customerId;
        const customerName = body.customerName;
        const remarks = String(body.remarks || "");
        const lines = body.lines;

        const validDate = /^\d{4}-\d{2}-\d{2}$/.test(date) &&
          (() => {
            const [y,m,d] = date.split("-").map(Number);
            const parsed = new Date(Date.UTC(y,m-1,d));
            return parsed.getUTCFullYear()===y &&
              parsed.getUTCMonth()===m-1 &&
              parsed.getUTCDate()===d;
          })();

        if (![orgId, factoryId, key, reference, date, customerId, customerName].every(x => typeof x === "string" && x.trim()) ||
            orgId.length > 128 || factoryId.length > 128 ||
            key.length > 200 || reference.length > 100 ||
            customerId.length > 128 || customerName.length > 200 ||
            !validDate || !Array.isArray(lines) || !lines.length || lines.length > 50 ||
            remarks.length > 2000) {
          return Response.json({ success:false, error:"Invalid dispatch input." }, {status:400});
        }

        const accessToken = await getGoogleAccessToken(env);
        const member = await firestoreGet(env, `organisations/${orgId}/members/${user.sub}`, accessToken);
        const f = member?.fields || {};
        const role = f.role?.stringValue;
        const factories = f.factoryIds?.arrayValue?.values || [];
        if (f.active?.booleanValue !== true || f.organisationId?.stringValue !== orgId ||
            !["owner","admin","manager","dispatch","dispatch_staff"].includes(role) ||
            !factories.some(x=>x.stringValue===factoryId)) {
          return Response.json({success:false,error:"Permission denied."},{status:403});
        }

        const merged = new Map();
        for (const line of lines) {
          if (typeof line.productId !== "string" || !line.productId.trim() ||
              !Number.isSafeInteger(line.packs) || line.packs < 0 ||
              !Number.isSafeInteger(line.looseBottles) || line.looseBottles < 0 ||
              (!line.packs && !line.looseBottles)) {
            return Response.json({success:false,error:"Invalid dispatch line."},{status:400});
          }
          const old = merged.get(line.productId) || {productId:line.productId,packs:0,looseBottles:0};
          old.packs += line.packs;
          old.looseBottles += line.looseBottles;
          if (!Number.isSafeInteger(old.packs) ||
              !Number.isSafeInteger(old.looseBottles)) {
            return Response.json(
              {success:false,error:"Aggregated dispatch quantity exceeds the safe limit."},
              {status:400}
            );
          }
          merged.set(line.productId,old);
        }
        const normalized = [...merged.values()];
        const fingerprint = JSON.stringify({orgId,factoryId,user:user.sub,reference,date,customerId,customerName,lines:normalized,remarks});
        const root = `organisations/${orgId}/factories/${factoryId}`;
        const dispatchPath = `${root}/dispatches/${key}`;
        transaction = await firestoreBeginTransaction(accessToken);
        const customerPath = `${root}/customers/${customerId}`;
        const paths = [dispatchPath,customerPath];
        for (const l of normalized) paths.push(`${root}/products/${l.productId}`,`${root}/stock/${l.productId}`);
        const docs = await firestoreBatchGet(accessToken,transaction,paths);
        const snap = new Map(docs.map(d=>[d.name.split("/documents/")[1],d]));
        const existing = snap.get(dispatchPath);
        if (existing) {
          const oldFp = existing.fields?.requestFingerprint?.stringValue;
          await firestoreRollback(accessToken,transaction); transaction=null;
          if(oldFp!==fingerprint) return Response.json({success:false,error:"Idempotency key conflict."},{status:409});
          return Response.json({success:true,dispatchId:key,status:"POSTED",duplicate:true});
        }
        const customer = snap.get(customerPath);
        if (!customer || customer.fields?.active?.booleanValue===false)
          throw new Error("Customer missing or inactive.");
        const posted=[];
        const writes=[];
        for (const l of normalized) {
          const product=snap.get(`${root}/products/${l.productId}`)?.fields;
          const stock=snap.get(`${root}/stock/${l.productId}`)?.fields;
          if (!product || product.active?.booleanValue!==true) throw new Error(`Product ${l.productId} missing or inactive.`);
          const packSize=Number(product.bottlesPerPack?.integerValue || product.bottlesPerPack?.doubleValue);
          const packs=Number(stock?.completePacks?.integerValue);
          const loose=Number(stock?.looseBottles?.integerValue || 0);
          if (!Number.isSafeInteger(packSize)||packSize<=0||!Number.isSafeInteger(packs)||packs<0||
              !Number.isSafeInteger(loose)||loose<0) throw new Error(`Invalid stock or pack size for ${l.productId}.`);
          if(l.packs>packs||l.looseBottles>loose) throw new Error(`Insufficient stock for ${l.productId}.`);
          const name=product.name?.stringValue||l.productId;
          const bottles = l.packs * packSize + l.looseBottles;
          if (!Number.isSafeInteger(bottles) || bottles < 0)
            throw new Error(`Dispatch bottle quantity exceeds the supported range for ${l.productId}.`);
          posted.push({...l,productName:name,packSizeSnapshot:packSize,bottles});
          writes.push({update:{name:`projects/${PROJECT_ID}/databases/(default)/documents/${root}/stock/${l.productId}`,
            fields:{completePacks:{integerValue:String(packs-l.packs)},looseBottles:{integerValue:String(loose-l.looseBottles)},updatedAt:{timestampValue:new Date().toISOString()}}}});
          for (const [qty, unit, type] of [
            [l.packs, "PACK", "DISPATCH_OUT"],
            [l.looseBottles, "BOTTLE", "DISPATCH_LOOSE_OUT"]
          ]) {
            if (qty <= 0) continue;
            const ledgerId = crypto.randomUUID();
            writes.push({
              update: {
                name: `projects/${PROJECT_ID}/databases/(default)/documents/${root}/inventoryTransactions/${ledgerId}`,
                fields: {
                  organisationId: {stringValue: orgId},
                  factoryId: {stringValue: factoryId},
                  productId: {stringValue: l.productId},
                  productName: {stringValue: name},
                  transactionType: {stringValue: type},
                  quantity: {integerValue: String(qty)},
                  unit: {stringValue: unit},
                  referenceId: {stringValue: key},
                  referenceType: {stringValue: "DISPATCH"},
                  dispatchReference: {stringValue: reference},
                  actorUid: {stringValue: user.sub},
                  actorRole: {stringValue: role},
                  createdAt: {timestampValue: new Date().toISOString()}
                },
                currentDocument: {exists: false}
              }
            });
          }
        }
        const fields={organisationId:{stringValue:orgId},factoryId:{stringValue:factoryId},
          idempotencyKey:{stringValue:key},requestFingerprint:{stringValue:fingerprint},
          dispatchReference:{stringValue:reference},dispatchDate:{stringValue:date},
          customerId:{stringValue:customerId},customerName:{stringValue:customerName},
          lines:{arrayValue:{values:posted.map(l=>({mapValue:{fields:{
            productId:{stringValue:l.productId},productName:{stringValue:l.productName},
            packSizeSnapshot:{integerValue:String(l.packSizeSnapshot)},packs:{integerValue:String(l.packs)},
            looseBottles:{integerValue:String(l.looseBottles)},bottles:{integerValue:String(l.bottles)}
          }}}))}},remarks:{stringValue:remarks},status:{stringValue:"POSTED"},
          actorUid:{stringValue:user.sub},actorRole:{stringValue:role}};
        writes.push({update:{name:`projects/${PROJECT_ID}/databases/(default)/documents/${dispatchPath}`,fields}});
        await firestoreCommit(accessToken,transaction,writes); transaction=null;
        return Response.json({success:true,dispatchId:key,status:"POSTED",duplicate:false});
      } catch(error) {
        if (transaction) {
          try {
            await firestoreRollback(await getGoogleAccessToken(env), transaction);
          } catch (rollbackError) {
            console.error("Dispatch transaction rollback failed:", rollbackError?.message || rollbackError);
          }
        }
        console.error("Dispatch posting error:",error);

        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        const invalidJson = error instanceof SyntaxError;

        return Response.json(
          {
            success:false,
            error: authError
              ? "Firebase authentication is required or expired."
              : invalidJson
                ? "Invalid JSON request body."
                : "Dispatch posting failed."
          },
          {status: authError ? 401 : invalidJson ? 400 : 500}
        );
      }
    }



    // Dispatch reversal endpoint
    if (request.method === "POST" && url.pathname === "/api/dispatch/reverse") {
      let transaction;
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId, dispatchId, reason } = body;
        if (
          ![orgId,factoryId,dispatchId,reason].every(x=>typeof x==="string"&&x.trim())||
          orgId.length>150||orgId.includes("/")||
          factoryId.length>150||factoryId.includes("/")||
          dispatchId.length>150||dispatchId.includes("/")||
          reason.length>500
        )
          return Response.json({success:false,error:"Invalid reversal input."},{status:400});

        const accessToken=await getGoogleAccessToken(env);
        const member=await firestoreGet(env,`organisations/${orgId}/members/${user.sub}`,accessToken);
        const f=member?.fields||{}, role=f.role?.stringValue, factories=f.factoryIds?.arrayValue?.values||[];
        if(f.active?.booleanValue!==true||f.organisationId?.stringValue!==orgId||
          !["owner","admin","manager"].includes(role)||
          !factories.some(x=>x.stringValue===factoryId))
          return Response.json({success:false,error:"Permission denied."},{status:403});

        const root=`organisations/${orgId}/factories/${factoryId}`;
        const dispatchPath=`${root}/dispatches/${dispatchId}`;
        const reversalPath=`${root}/dispatchReversals/${dispatchId}`;
        transaction=await firestoreBeginTransaction(accessToken);
        const docs=await firestoreBatchGet(accessToken,transaction,[dispatchPath,reversalPath]);
        const map=new Map(docs.map(d=>[d.name.split("/documents/")[1],d]));
        const d=map.get(dispatchPath);
        if(!d) throw new Error("Dispatch not found.");
        if(map.get(reversalPath)){
          await firestoreRollback(accessToken,transaction);transaction=null;
          return Response.json({success:true,reversed:false,duplicate:true});
        }
        const df=d.fields||{};

        if(df.actorUid?.stringValue!==user.sub)
          throw new Error("Only the original dispatch creator can reverse this record.");

        if(df.status?.stringValue!=="POSTED"||df.organisationId?.stringValue!==orgId||df.factoryId?.stringValue!==factoryId)
          throw new Error("Only a posted dispatch in this factory can be reversed.");
        const lines=df.lines?.arrayValue?.values||[];
        if(!lines.length) throw new Error("Dispatch lines missing.");
        const parsedRaw=lines.map(x=>x.mapValue.fields);
        const merged=new Map();
        for(const l of parsedRaw){
          const id=l.productId?.stringValue;
          if(typeof id!=="string"||!id.trim()||id.length>150||id.includes("/"))
            throw new Error("Invalid product ID in dispatch reversal.");
          const packs=Number(l.packs?.integerValue);
          const loose=Number(l.looseBottles?.integerValue||0);
          if(!id||![packs,loose].every(Number.isSafeInteger)||packs<0||loose<0)
            throw new Error("Invalid dispatch reversal line.");
          const old=merged.get(id)||{...l,productId:{stringValue:id},packs:{integerValue:"0"},looseBottles:{integerValue:"0"}};
          const nextPacks=Number(old.packs.integerValue)+packs;
          const nextLoose=Number(old.looseBottles.integerValue)+loose;
          if(![nextPacks,nextLoose].every(Number.isSafeInteger))
            throw new Error("Aggregated dispatch reversal quantity exceeds the safe limit.");
          old.packs={integerValue:String(nextPacks)};
          old.looseBottles={integerValue:String(nextLoose)};
          merged.set(id,old);
        }
        const parsed=[...merged.values()];
        const stockPaths=parsed.map(l=>`${root}/stock/${l.productId.stringValue}`);
        const stockDocs=await firestoreBatchGet(accessToken,transaction,stockPaths);
        const stocks=new Map(stockDocs.map(x=>[x.name.split("/documents/")[1],x]));
        const writes=[{update:{name:`projects/${PROJECT_ID}/databases/(default)/documents/${dispatchPath}`,
          fields:{status:{stringValue:"REVERSED"},reversalId:{stringValue:dispatchId},reversalReason:{stringValue:reason},reversedBy:{stringValue:user.sub},reversedAt:{timestampValue:new Date().toISOString()}}}},
          {update:{name:`projects/${PROJECT_ID}/databases/(default)/documents/${reversalPath}`,
          fields:{organisationId:{stringValue:orgId},factoryId:{stringValue:factoryId},dispatchId:{stringValue:dispatchId},
          reason:{stringValue:reason},actorUid:{stringValue:user.sub},actorRole:{stringValue:role},createdAt:{timestampValue:new Date().toISOString()}}},currentDocument:{exists:false}}];
        for(const l of parsed){
          const id=l.productId.stringValue, packs=Number(l.packs.integerValue), loose=Number(l.looseBottles?.integerValue||0);
          const path=`${root}/stock/${id}`, st=stocks.get(path)?.fields;
          const cp=Number(st?.completePacks?.integerValue), cl=Number(st?.looseBottles?.integerValue||0);
          if(!st||![packs,loose,cp,cl].every(Number.isSafeInteger)||packs<0||loose<0||cp<0||cl<0)
            throw new Error(`Invalid stock balance for ${id}.`);
          const nextPacks = cp + packs;
          const nextLoose = cl + loose;
          if (![nextPacks, nextLoose].every(Number.isSafeInteger))
            throw new Error(`Dispatch reversal quantity exceeds the supported range for ${id}.`);

          writes.push({update:{name:`projects/${PROJECT_ID}/databases/(default)/documents/${path}`,
            fields:{completePacks:{integerValue:String(nextPacks)},looseBottles:{integerValue:String(nextLoose)},updatedAt:{timestampValue:new Date().toISOString()}}}});
          for(const [qty,unit,type,suffix] of [[packs,"PACK","DISPATCH_REVERSAL_IN","packs"],[loose,"BOTTLE","DISPATCH_LOOSE_REVERSAL_IN","loose"]]){
            if(qty>0) writes.push({update:{name:`projects/${PROJECT_ID}/databases/(default)/documents/${root}/inventoryTransactions/dispatch-reversal-${dispatchId}-${id}-${suffix}`,
              fields:{organisationId:{stringValue:orgId},factoryId:{stringValue:factoryId},productId:{stringValue:id},
              productNameSnapshot:l.productName,quantity:{integerValue:String(qty)},unit:{stringValue:unit},
              transactionType:{stringValue:type},sourceType:{stringValue:"dispatchReversal"},sourceId:{stringValue:dispatchId},
              idempotencyKey:{stringValue:`reverse:${dispatchId}:${id}:${suffix}`},reason:{stringValue:reason},actorUid:{stringValue:user.sub},
              createdAt:{timestampValue:new Date().toISOString()}}},currentDocument:{exists:false}});
          }
        }
        await firestoreCommit(accessToken,transaction,writes);transaction=null;
        return Response.json({success:true,reversed:true,duplicate:false});
      }catch(error){
        if (transaction) {
          try {
            await firestoreRollback(await getGoogleAccessToken(env), transaction);
          } catch (rollbackError) {
            console.error("Reversal transaction rollback failed:", rollbackError?.message || rollbackError);
          }
        }
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        const invalidJson = error instanceof SyntaxError;

        return Response.json(
          {
            success:false,
            error: authError
              ? "Firebase authentication is required or expired."
              : invalidJson
                ? "Invalid JSON request body."
                : "Dispatch reversal failed."
          },
          {status: authError ? 401 : invalidJson ? 400 : 500}
        );
      }
    }

    // Packing reversal endpoint
    if (request.method === "POST" && url.pathname === "/api/packing/reverse") {
      let transaction;
      try {
        const user=await verifyFirebaseToken(request);
        const body=await request.json();
        const {organisationId:orgId,factoryId,packingId,reason}=body;
        if(
          ![orgId,factoryId,packingId,reason].every(x=>typeof x==="string"&&x.trim())||
          orgId.length>150||orgId.includes("/")||
          factoryId.length>150||factoryId.includes("/")||
          packingId.length>150||packingId.includes("/")||
          reason.length>500
        )
          return Response.json({success:false,error:"Invalid reversal input."},{status:400});
        const accessToken=await getGoogleAccessToken(env);
        const member=await firestoreGet(env,`organisations/${orgId}/members/${user.sub}`,accessToken);
        const f=member?.fields||{},role=f.role?.stringValue,factories=f.factoryIds?.arrayValue?.values||[];
        if(f.active?.booleanValue!==true||f.organisationId?.stringValue!==orgId||
          !["owner","admin","manager"].includes(role)||
          !factories.some(x=>x.stringValue===factoryId))
          return Response.json({success:false,error:"Permission denied."},{status:403});
        const root=`organisations/${orgId}/factories/${factoryId}`;
        const packingPath=`${root}/packings/${packingId}`, reversalPath=`${root}/packingReversals/${packingId}`;
        transaction=await firestoreBeginTransaction(accessToken);
        const docs=await firestoreBatchGet(accessToken,transaction,[packingPath,reversalPath]);
        const map=new Map(docs.map(d=>[d.name.split("/documents/")[1],d]));
        const packing=map.get(packingPath);
        if(!packing)throw new Error("Packing record not found.");
        if(map.get(reversalPath)){await firestoreRollback(accessToken,transaction);transaction=null;return Response.json({success:true,reversed:false,duplicate:true});}
        const pf=packing.fields||{};

        if(pf.actorUid?.stringValue!==user.sub)
          throw new Error("Only the original packing creator can reverse this record.");

        if(pf.status?.stringValue!=="POSTED"||pf.organisationId?.stringValue!==orgId||pf.factoryId?.stringValue!==factoryId)
          throw new Error("Only a posted packing record in this factory can be reversed.");
        const id=pf.productId?.stringValue, consumed=Number(pf.bottlesConsumed?.integerValue),packs=Number(pf.completePacks?.integerValue),loose=Number(pf.looseBottles?.integerValue);
        if(
          !id||id.length>150||id.includes("/")||
          ![consumed,packs,loose].every(Number.isSafeInteger)||
          consumed<=0||packs<0||loose<0
        )
          throw new Error("Packing quantities invalid.");
        const stockPath=`${root}/stock/${id}`, stockDocs=await firestoreBatchGet(accessToken,transaction,[stockPath]);
        const st=stockDocs[0]?.fields;
        const unpacked=Number(st?.unpackedGoodBottles?.integerValue),cp=Number(st?.completePacks?.integerValue),cl=Number(st?.looseBottles?.integerValue||0);
        if(!st||![unpacked,cp,cl].every(Number.isSafeInteger)||unpacked<0||cp<packs||cl<loose)
          throw new Error("Cannot reverse packing: finished stock has been dispatched or adjusted.");
        const nextUnpacked = unpacked + consumed;
        const nextCompletePacks = cp - packs;
        const nextLooseBottles = cl - loose;

        if (
          !Number.isSafeInteger(nextUnpacked) ||
          !Number.isSafeInteger(nextCompletePacks) ||
          !Number.isSafeInteger(nextLooseBottles) ||
          nextCompletePacks < 0 ||
          nextLooseBottles < 0
        )
          throw new Error("Packing reversal exceeds the supported stock quantity range.");

        const writes=[
          {update:{name:`projects/${PROJECT_ID}/databases/(default)/documents/${packingPath}`,fields:{status:{stringValue:"REVERSED"},reversalId:{stringValue:packingId},reversalReason:{stringValue:reason},reversedBy:{stringValue:user.sub},reversedAt:{timestampValue:new Date().toISOString()}}}},
          {update:{name:`projects/${PROJECT_ID}/databases/(default)/documents/${reversalPath}`,fields:{organisationId:{stringValue:orgId},factoryId:{stringValue:factoryId},packingId:{stringValue:packingId},reason:{stringValue:reason},actorUid:{stringValue:user.sub},actorRole:{stringValue:role},createdAt:{timestampValue:new Date().toISOString()}}},currentDocument:{exists:false}},
          {update:{name:`projects/${PROJECT_ID}/databases/(default)/documents/${stockPath}`,fields:{unpackedGoodBottles:{integerValue:String(nextUnpacked)},completePacks:{integerValue:String(nextCompletePacks)},looseBottles:{integerValue:String(nextLooseBottles)},updatedAt:{timestampValue:new Date().toISOString()}}}}
        ];
        const name=pf.productNameSnapshot||{stringValue:id};
        for(const [qty,unit,type,suffix] of [[consumed,"BOTTLE","PACKING_REVERSAL_BOTTLES_IN","bottles"],[packs,"PACK","PACKING_REVERSAL_PACKS_OUT","packs"],[loose,"BOTTLE","PACKING_REVERSAL_LOOSE_OUT","loose"]]){
          if(qty>0)writes.push({update:{name:`projects/${PROJECT_ID}/databases/(default)/documents/${root}/inventoryTransactions/${packingId}-${id}-${suffix}`,fields:{
            organisationId:{stringValue:orgId},factoryId:{stringValue:factoryId},productId:{stringValue:id},productNameSnapshot:name,
            quantity:{integerValue:String(type.includes("BOTTLES_IN")?qty:-qty)},unit:{stringValue:unit},transactionType:{stringValue:type},
            sourceType:{stringValue:"packingReversal"},sourceId:{stringValue:packingId},idempotencyKey:{stringValue:`reverse:${packingId}:${id}:${suffix}`},
            reason:{stringValue:reason},actorUid:{stringValue:user.sub},createdAt:{timestampValue:new Date().toISOString()}}},currentDocument:{exists:false}});
        }
        await firestoreCommit(accessToken,transaction,writes);transaction=null;
        return Response.json({success:true,reversed:true,duplicate:false});
      }catch(error){
        if (transaction) {
          try {
            await firestoreRollback(await getGoogleAccessToken(env), transaction);
          } catch (rollbackError) {
            console.error("Reversal transaction rollback failed:", rollbackError?.message || rollbackError);
          }
        }
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        const invalidJson = error instanceof SyntaxError;

        return Response.json(
          {
            success:false,
            error: authError
              ? "Firebase authentication is required or expired."
              : invalidJson
                ? "Invalid JSON request body."
                : "Packing reversal failed."
          },
          {status: authError ? 401 : invalidJson ? 400 : 500}
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
          factoryIds.some(x => x.stringValue === factoryId);

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
          } catch (rollbackError) {
            console.error("Production transaction rollback failed:", rollbackError?.message || rollbackError);
          }
        }

        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        console.error("Production posting error:", error?.message || error);
        return Response.json(
          {
            success: false,
            error: authError
              ? "Firebase authentication is required or expired."
              : "Production posting failed."
          },
          { status: authError ? 401 : 500 }
        );
      }
    }



    // Production reversal endpoint
    if (request.method === "POST" && url.pathname === "/api/production/reverse") {
      let transaction;
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId, productionId, reason } = body;

        if (![orgId, factoryId, productionId, reason].every(
          x => typeof x === "string" && x.trim()
        ) || productionId.length > 150 || reason.length > 500) {
          return Response.json(
            { success: false, error: "Invalid production reversal input." },
            { status: 400 }
          );
        }

        const accessToken = await getGoogleAccessToken(env);
        const member = await firestoreGet(
          env, `organisations/${orgId}/members/${user.sub}`, accessToken
        );
        const f = member?.fields || {};
        const role = f.role?.stringValue;
        const factories = f.factoryIds?.arrayValue?.values || [];

        if (
          f.active?.booleanValue !== true ||
          f.organisationId?.stringValue !== orgId ||
          !["owner", "admin", "manager"].includes(role) ||
          !factories.some(x => x.stringValue === factoryId)
        ) {
          return Response.json(
            { success: false, error: "Permission denied." },
            { status: 403 }
          );
        }

        const root = `organisations/${orgId}/factories/${factoryId}`;
        const productionPath = `${root}/productions/${productionId}`;
        const reversalPath = `${root}/productionReversals/${productionId}`;

        transaction = await firestoreBeginTransaction(accessToken);
        const docs = await firestoreBatchGet(
          accessToken, transaction, [productionPath, reversalPath]
        );
        const map = new Map(
          docs.map(d => [d.name.split("/documents/")[1], d])
        );
        const productionDoc = map.get(productionPath);

        if (!productionDoc) throw new Error("Production record not found.");

        if (map.get(reversalPath)) {
          await firestoreRollback(accessToken, transaction);
          transaction = null;
          return Response.json({
            success: true, reversed: false, duplicate: true
          });
        }

        const pf = productionDoc.fields || {};
        if (
          pf.status?.stringValue !== "POSTED" ||
          pf.organisationId?.stringValue !== orgId ||
          pf.factoryId?.stringValue !== factoryId
        ) {
          throw new Error("Only a posted production record can be reversed.");
        }

        const productId = pf.productId?.stringValue;
        const good = Number(pf.goodBottles?.integerValue);
        const rejected = Number(pf.rejectedBottles?.integerValue || 0);

        if (!productId ||
          !Number.isSafeInteger(good) || good < 0 ||
          !Number.isSafeInteger(rejected) || rejected < 0) {
          throw new Error("Invalid production quantities.");
        }

        const stockPath = `${root}/stock/${productId}`;
        const stockDocs = await firestoreBatchGet(
          accessToken, transaction, [stockPath]
        );
        const stockDoc = stockDocs.find(
          d => d.name.split("/documents/")[1] === stockPath
        );
        const stock = stockDoc?.fields || {};
        const unpacked = Number(stock.unpackedGoodBottles?.integerValue);

        if (!stockDoc || !Number.isSafeInteger(unpacked) || unpacked < good) {
          throw new Error(
            "Cannot reverse: some good bottles have already been packed or stock is insufficient."
          );
        }

        const now = new Date().toISOString();
        const reversalKey = `production-reversal:${productionId}`;

        const writes = [
          {
            update: {
              name: `projects/${PROJECT_ID}/databases/(default)/documents/${productionPath}`,
              fields: {
                status: { stringValue: "REVERSED" },
                reversalId: { stringValue: productionId },
                reversalReason: { stringValue: reason },
                reversedAt: { timestampValue: now },
                reversedBy: { stringValue: user.sub }
              }
            }
          },
          {
            update: {
              name: `projects/${PROJECT_ID}/databases/(default)/documents/${reversalPath}`,
              fields: {
                organisationId: { stringValue: orgId },
                factoryId: { stringValue: factoryId },
                productionId: { stringValue: productionId },
                productId: { stringValue: productId },
                goodBottles: { integerValue: String(good) },
                rejectedBottles: { integerValue: String(rejected) },
                reason: { stringValue: reason },
                actorUid: { stringValue: user.sub },
                actorRole: { stringValue: role },
                createdAt: { timestampValue: now }
              }
            },
            currentDocument: { exists: false }
          },
          {
            update: {
              name: `projects/${PROJECT_ID}/databases/(default)/documents/${stockPath}`,
              fields: {
                organisationId: stock.organisationId || { stringValue: orgId },
                factoryId: stock.factoryId || { stringValue: factoryId },
                productId: stock.productId || { stringValue: productId },
                unpackedGoodBottles: { integerValue: String(unpacked - good) },
                completePacks: stock.completePacks || { integerValue: "0" },
                looseBottles: stock.looseBottles || { integerValue: "0" },
                updatedAt: { timestampValue: now }
              }
            }
          }
        ];

        if (good > 0) {
          writes.push({
            update: {
              name: `projects/${PROJECT_ID}/databases/(default)/documents/${root}/inventoryTransactions/${crypto.randomUUID()}`,
              fields: {
                organisationId: { stringValue: orgId },
                factoryId: { stringValue: factoryId },
                productId: { stringValue: productId },
                productNameSnapshot: pf.productNameSnapshot || pf.productName || { stringValue: "" },
                quantity: { integerValue: String(-good) },
                unit: { stringValue: "BOTTLE" },
                transactionType: { stringValue: "PRODUCTION_REVERSAL_OUT" },
                sourceType: { stringValue: "productionReversal" },
                sourceId: { stringValue: productionId },
                idempotencyKey: { stringValue: `${reversalKey}:${productId}:good` },
                reason: { stringValue: reason },
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
              name: `projects/${PROJECT_ID}/databases/(default)/documents/${root}/inventoryTransactions/${crypto.randomUUID()}`,
              fields: {
                organisationId: { stringValue: orgId },
                factoryId: { stringValue: factoryId },
                productId: { stringValue: productId },
                productNameSnapshot: pf.productNameSnapshot || pf.productName || { stringValue: "" },
                quantity: { integerValue: String(rejected) },
                unit: { stringValue: "BOTTLE" },
                transactionType: { stringValue: "REJECTED_PRODUCTION_REVERSAL" },
                sourceType: { stringValue: "productionReversal" },
                sourceId: { stringValue: productionId },
                idempotencyKey: { stringValue: `${reversalKey}:${productId}:rejected` },
                reason: { stringValue: reason },
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
          success: true, reversed: true, duplicate: false
        });
      } catch (error) {
        if (transaction) {
          try {
            await firestoreRollback(await getGoogleAccessToken(env), transaction);
          } catch (rollbackError) {
            console.error(
              "Production reversal rollback failed:",
              rollbackError?.message || rollbackError
            );
          }
        }

        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        const invalidJson = error instanceof SyntaxError;
        console.error("Production reversal error:", error?.message || error);
        return Response.json(
          {
            success: false,
            error: authError
              ? "Firebase authentication is required or expired."
              : invalidJson
                ? "Invalid JSON request body."
                : "Production reversal failed."
          },
          { status: authError ? 401 : 500 }
        );
      }
    }


    // Manual stock adjustment endpoint
    if (request.method === "POST" && url.pathname === "/api/stock/adjust") {
      let transaction;
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const {
          organisationId: orgId, factoryId, productId,
          quantity, unit, adjustmentType, reason, idempotencyKey: key
        } = body;

        if (
          ![orgId, factoryId, productId, unit, adjustmentType, reason, key]
            .every(x => typeof x === "string" && x.trim()) ||
          !Number.isSafeInteger(quantity) || quantity <= 0 ||
          !["PACK", "BOTTLE"].includes(unit) ||
          !["ADD", "REMOVE"].includes(adjustmentType) ||
          reason.trim().length > 500 ||
          key.length > 150
        ) {
          return Response.json(
            { success: false, error: "Invalid stock adjustment input." },
            { status: 400 }
          );
        }

        const accessToken = await getGoogleAccessToken(env);
        const member = await firestoreGet(
          env, `organisations/${orgId}/members/${user.sub}`, accessToken
        );
        const f = member?.fields || {};
        const role = f.role?.stringValue;
        const factories = f.factoryIds?.arrayValue?.values || [];

        if (
          f.active?.booleanValue !== true ||
          f.organisationId?.stringValue !== orgId ||
          !["owner", "admin", "manager", "storekeeper"].includes(role) ||
          !factories.some(x => x.stringValue === factoryId)
        ) {
          return Response.json(
            { success: false, error: "Permission denied." },
            { status: 403 }
          );
        }

        const root = `organisations/${orgId}/factories/${factoryId}`;
        const productPath = `${root}/products/${productId}`;
        const stockPath = `${root}/stock/${productId}`;
        const ledgerPath = `${root}/inventoryTransactions/${key}`;

        transaction = await firestoreBeginTransaction(accessToken);
        const docs = await firestoreBatchGet(
          accessToken, transaction, [productPath, stockPath, ledgerPath]
        );
        const map = new Map(docs.map(
          d => [d.name.split("/documents/")[1], d]
        ));
        const product = map.get(productPath);
        const stockDoc = map.get(stockPath);

        if (!product) throw new Error("Product not found.");
        if (product.fields?.active?.booleanValue === false)
          throw new Error("Cannot adjust stock for an archived product.");

        if (map.get(ledgerPath)) {
          await firestoreRollback(accessToken, transaction);
          transaction = null;
          return Response.json({
            success: true, duplicate: true, adjusted: false
          });
        }

        if (!stockDoc) throw new Error("Stock record not found.");

        const sf = stockDoc.fields || {};
        const packs = Number(sf.completePacks?.integerValue || 0);
        const loose = Number(sf.looseBottles?.integerValue || 0);

        if (![packs, loose].every(Number.isSafeInteger) || packs < 0 || loose < 0)
          throw new Error("Invalid current stock balance.");

        const delta = adjustmentType === "ADD" ? quantity : -quantity;
        const nextPacks = unit === "PACK" ? packs + delta : packs;
        const nextLoose = unit === "BOTTLE" ? loose + delta : loose;

        if (![nextPacks, nextLoose].every(Number.isSafeInteger))
          throw new Error("Stock adjustment exceeds the supported quantity range.");

        if (nextPacks < 0 || nextLoose < 0)
          throw new Error("Insufficient stock. Adjustment cannot make balance negative.");

        const now = new Date().toISOString();
        const name = product.fields?.name?.stringValue ||
                    product.fields?.productName?.stringValue || productId;

        const writes = [
          {
            update: {
              name: `projects/${PROJECT_ID}/databases/(default)/documents/${stockPath}`,
              fields: {
                organisationId: sf.organisationId || { stringValue: orgId },
                factoryId: sf.factoryId || { stringValue: factoryId },
                productId: sf.productId || { stringValue: productId },
                unpackedGoodBottles: sf.unpackedGoodBottles || { integerValue: "0" },
                completePacks: { integerValue: String(nextPacks) },
                looseBottles: { integerValue: String(nextLoose) },
                updatedAt: { timestampValue: now }
              }
            }
          },
          {
            update: {
              name: `projects/${PROJECT_ID}/databases/(default)/documents/${ledgerPath}`,
              fields: {
                organisationId: { stringValue: orgId },
                factoryId: { stringValue: factoryId },
                productId: { stringValue: productId },
                productNameSnapshot: { stringValue: name },
                quantity: { integerValue: String(delta) },
                unit: { stringValue: unit },
                transactionType: {
                  stringValue: adjustmentType === "ADD"
                    ? "MANUAL_STOCK_ADJUSTMENT_IN"
                    : "MANUAL_STOCK_ADJUSTMENT_OUT"
                },
                sourceType: { stringValue: "manualStockAdjustment" },
                sourceId: { stringValue: key },
                idempotencyKey: { stringValue: key },
                reason: { stringValue: reason },
                actorUid: { stringValue: user.sub },
                actorRole: { stringValue: role },
                createdAt: { timestampValue: now }
              }
            },
            currentDocument: { exists: false }
          }
        ];

        await firestoreCommit(accessToken, transaction, writes);
        transaction = null;

        return Response.json({
          success: true, adjusted: true, duplicate: false,
          completePacks: nextPacks, looseBottles: nextLoose
        });
      } catch (error) {
        if (transaction) {
          try {
            await firestoreRollback(await getGoogleAccessToken(env), transaction);
          } catch (rollbackError) {
            console.error("Stock adjustment rollback failed:", rollbackError?.message || rollbackError);
          }
        }

        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        console.error("Stock adjustment error:", error?.message || error);
        return Response.json(
          {
            success: false,
            error: authError
              ? "Firebase authentication is required or expired."
              : "Stock adjustment failed."
          },
          { status: authError ? 401 : 500 }
        );
      }
    }

    // ==========================================
    // CUSTOMERS MODULE
    // ==========================================
    if (request.method === "POST" && url.pathname === "/api/customers/upsert") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId, customerId, name, phone, address, gstNumber, active } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, ["owner", "admin", "manager", "dispatch", "dispatch_staff", "storekeeper"], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        if (!name || typeof name !== "string" || !name.trim()) {
          return Response.json({ success: false, error: "Customer name is required." }, { status: 400 });
        }

        const id = String(customerId || name.trim().toLowerCase().replace(/[^a-z0-9]/g, "-")).slice(0, 100);
        const accessToken = await getGoogleAccessToken(env);
        const docPath = `organisations/${orgId}/factories/${factoryId}/customers/${id}`;
        const existing = await firestoreGet(env, docPath, accessToken);
        const now = new Date().toISOString();
        const createdAt = existing?.fields?.createdAt?.timestampValue || now;

        await firestorePatch(env, docPath, objectToFirestoreFields({
          customerId: id,
          name: name.trim(),
          phone: phone ? String(phone).trim() : "",
          address: address ? String(address).trim() : "",
          gstNumber: gstNumber ? String(gstNumber).trim() : "",
          active: active !== false,
          createdAt,
          updatedAt: now,
          updatedBy: user.sub
        }), accessToken);

        return Response.json({ success: true, customerId: id, active: active !== false });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Customer upsert failed.") }, { status: authError ? 401 : 500 });
      }
    }

    if (request.method === "POST" && url.pathname === "/api/customers/archive") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId, customerId } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, ["owner", "admin", "manager", "dispatch", "dispatch_staff", "storekeeper"], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        if (!customerId) return Response.json({ success: false, error: "customerId is required." }, { status: 400 });

        const accessToken = await getGoogleAccessToken(env);
        const docPath = `organisations/${orgId}/factories/${factoryId}/customers/${customerId}`;
        const now = new Date().toISOString();

        await firestorePatch(env, docPath, objectToFirestoreFields({
          active: false,
          updatedAt: now,
          updatedBy: user.sub
        }), accessToken);

        return Response.json({ success: true, archived: true, customerId });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Archive customer failed.") }, { status: authError ? 401 : 500 });
      }
    }

    if (request.method === "POST" && url.pathname === "/api/customers/restore") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId, customerId } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, ["owner", "admin", "manager", "dispatch", "dispatch_staff", "storekeeper"], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        if (!customerId) return Response.json({ success: false, error: "customerId is required." }, { status: 400 });

        const accessToken = await getGoogleAccessToken(env);
        const docPath = `organisations/${orgId}/factories/${factoryId}/customers/${customerId}`;
        const now = new Date().toISOString();

        await firestorePatch(env, docPath, objectToFirestoreFields({
          active: true,
          updatedAt: now,
          updatedBy: user.sub
        }), accessToken);

        return Response.json({ success: true, restored: true, customerId });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Restore customer failed.") }, { status: authError ? 401 : 500 });
      }
    }

    if (request.method === "POST" && url.pathname === "/api/customers/list") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, [], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        const accessToken = await getGoogleAccessToken(env);
        const docs = await firestoreList(env, `organisations/${orgId}/factories/${factoryId}/customers`, accessToken);
        const customers = docs.map(fromFirestoreDoc);

        return Response.json({ success: true, customers });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Customer list failed.") }, { status: authError ? 401 : 500 });
      }
    }

    // ==========================================
    // SALES ORDERS MODULE
    // ==========================================
    if (request.method === "POST" && url.pathname === "/api/orders/create") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId, orderNumber, customerId, customerName, orderDate, dueDate, notes, lines } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, ["owner", "admin", "manager", "dispatch", "dispatch_staff"], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        if (!orderNumber || !customerId || !Array.isArray(lines) || lines.length === 0) {
          return Response.json({ success: false, error: "orderNumber, customerId, and order lines are required." }, { status: 400 });
        }

        const validLines = lines.map(line => ({
          productId: String(line.productId || "").trim(),
          productNameSnapshot: String(line.productNameSnapshot || line.productId || "").trim(),
          packsOrdered: Number(line.packsOrdered) || 0,
          packsDispatched: 0,
          packSizeSnapshot: Number(line.packSizeSnapshot) || 1
        }));

        if (validLines.some(l => !l.productId || l.packsOrdered <= 0)) {
          return Response.json({ success: false, error: "All order lines must specify a productId and positive packsOrdered." }, { status: 400 });
        }

        const cleanOrderNumber = String(orderNumber).trim().replace(/[^a-zA-Z0-9_-]/g, "_");
        const docPath = `organisations/${orgId}/factories/${factoryId}/orders/${cleanOrderNumber}`;
        const accessToken = await getGoogleAccessToken(env);
        const existing = await firestoreGet(env, docPath, accessToken);
        if (existing) {
          return Response.json({ success: false, error: "Order number already exists." }, { status: 409 });
        }

        const now = new Date().toISOString();
        await firestorePatch(env, docPath, objectToFirestoreFields({
          orderNumber: cleanOrderNumber,
          customerId: String(customerId),
          customerNameSnapshot: String(customerName || ""),
          orderDate: String(orderDate || now.slice(0, 10)),
          dueDate: String(dueDate || orderDate || now.slice(0, 10)),
          status: "OPEN",
          notes: String(notes || ""),
          lines: validLines,
          createdBy: user.sub,
          createdAt: now,
          updatedAt: now
        }), accessToken);

        return Response.json({ success: true, orderNumber: cleanOrderNumber });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Order creation failed.") }, { status: authError ? 401 : 500 });
      }
    }

    if (request.method === "POST" && url.pathname === "/api/orders/edit") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId, orderNumber, dueDate, notes, lines } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, ["owner", "admin", "manager", "dispatch", "dispatch_staff"], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        if (!orderNumber) return Response.json({ success: false, error: "orderNumber is required." }, { status: 400 });

        const cleanOrderNumber = String(orderNumber).trim().replace(/[^a-zA-Z0-9_-]/g, "_");
        const docPath = `organisations/${orgId}/factories/${factoryId}/orders/${cleanOrderNumber}`;
        const accessToken = await getGoogleAccessToken(env);
        const existing = await firestoreGet(env, docPath, accessToken);
        if (!existing) return Response.json({ success: false, error: "Order not found." }, { status: 404 });

        const doc = fromFirestoreDoc(existing);
        if (doc.status === "CANCELLED") {
          return Response.json({ success: false, error: "Cancelled orders cannot be edited." }, { status: 409 });
        }

        const existingLines = Array.isArray(doc.lines) ? doc.lines : [];
        const existingDispatchedMap = new Map(existingLines.map(l => [l.productId, l.packsDispatched || 0]));

        const updatedLines = (Array.isArray(lines) ? lines : existingLines).map(line => {
          const productId = String(line.productId || "").trim();
          const dispatched = existingDispatchedMap.get(productId) || 0;
          const ordered = Number(line.packsOrdered) || 0;
          if (ordered < dispatched) {
            throw new Error(`Cannot reduce packsOrdered below already dispatched packs (${dispatched}) for product ${productId}.`);
          }
          return {
            productId,
            productNameSnapshot: String(line.productNameSnapshot || productId).trim(),
            packsOrdered: ordered,
            packsDispatched: dispatched,
            packSizeSnapshot: Number(line.packSizeSnapshot) || 1
          };
        });

        const now = new Date().toISOString();
        await firestorePatch(env, docPath, objectToFirestoreFields({
          dueDate: dueDate ? String(dueDate) : (doc.dueDate || now.slice(0, 10)),
          notes: notes !== undefined ? String(notes) : (doc.notes || ""),
          lines: updatedLines,
          updatedAt: now,
          updatedBy: user.sub
        }), accessToken);

        return Response.json({ success: true, updated: true, orderNumber: cleanOrderNumber });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Order edit failed.") }, { status: authError ? 401 : 500 });
      }
    }

    if (request.method === "POST" && url.pathname === "/api/orders/cancel") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId, orderNumber, reason } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, ["owner", "admin", "manager", "dispatch", "dispatch_staff"], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        if (!orderNumber) return Response.json({ success: false, error: "orderNumber is required." }, { status: 400 });

        const cleanOrderNumber = String(orderNumber).trim().replace(/[^a-zA-Z0-9_-]/g, "_");
        const docPath = `organisations/${orgId}/factories/${factoryId}/orders/${cleanOrderNumber}`;
        const accessToken = await getGoogleAccessToken(env);
        const existing = await firestoreGet(env, docPath, accessToken);
        if (!existing) return Response.json({ success: false, error: "Order not found." }, { status: 404 });

        const doc = fromFirestoreDoc(existing);
        const existingLines = Array.isArray(doc.lines) ? doc.lines : [];
        const hasDispatches = existingLines.some(l => (l.packsDispatched || 0) > 0);
        if (hasDispatches) {
          return Response.json({ success: false, error: "Cannot cancel order with active dispatches. Reverse dispatches first." }, { status: 409 });
        }

        const now = new Date().toISOString();
        await firestorePatch(env, docPath, objectToFirestoreFields({
          status: "CANCELLED",
          cancelReason: String(reason || ""),
          cancelledBy: user.sub,
          updatedAt: now
        }), accessToken);

        return Response.json({ success: true, cancelled: true, orderNumber: cleanOrderNumber });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Order cancel failed.") }, { status: authError ? 401 : 500 });
      }
    }

    if (request.method === "POST" && url.pathname === "/api/orders/list") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, [], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        const accessToken = await getGoogleAccessToken(env);
        const docs = await firestoreList(env, `organisations/${orgId}/factories/${factoryId}/orders`, accessToken);
        const orders = docs.map(fromFirestoreDoc);

        return Response.json({ success: true, orders });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Orders list failed.") }, { status: authError ? 401 : 500 });
      }
    }

    // ==========================================
    // PRODUCTION PLANS MODULE
    // ==========================================
    if (request.method === "POST" && url.pathname === "/api/plans/create") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId, planId, planDate, shift, productId, productNameSnapshot, targetBottles, assignedMachineId, lineId, notes } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, ["owner", "admin", "manager", "operator", "production", "production_staff"], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        const target = Number(targetBottles);
        if (!productId || !target || target <= 0) {
          return Response.json({ success: false, error: "productId and targetBottles (>0) are required." }, { status: 400 });
        }

        const now = new Date().toISOString();
        const id = String(planId || `plan-${Date.now()}`).replace(/[^a-zA-Z0-9_-]/g, "_");
        const docPath = `organisations/${orgId}/factories/${factoryId}/plans/${id}`;
        const accessToken = await getGoogleAccessToken(env);

        await firestorePatch(env, docPath, objectToFirestoreFields({
          planId: id,
          planDate: String(planDate || now.slice(0, 10)),
          shift: String(shift || "Day"),
          productId: String(productId),
          productNameSnapshot: String(productNameSnapshot || productId),
          targetBottles: target,
          assignedMachineId: assignedMachineId !== undefined && assignedMachineId !== null ? String(assignedMachineId) : null,
          lineId: lineId !== undefined && lineId !== null ? String(lineId) : null,
          status: "PLANNED",
          notes: String(notes || ""),
          createdBy: user.sub,
          createdAt: now,
          updatedAt: now
        }), accessToken);

        return Response.json({ success: true, planId: id });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Plan creation failed.") }, { status: authError ? 401 : 500 });
      }
    }

    if (request.method === "POST" && url.pathname === "/api/plans/edit") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId, planId, shift, targetBottles, assignedMachineId, lineId, notes } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, ["owner", "admin", "manager", "operator", "production", "production_staff"], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        if (!planId) return Response.json({ success: false, error: "planId is required." }, { status: 400 });

        const id = String(planId).replace(/[^a-zA-Z0-9_-]/g, "_");
        const docPath = `organisations/${orgId}/factories/${factoryId}/plans/${id}`;
        const accessToken = await getGoogleAccessToken(env);
        const existing = await firestoreGet(env, docPath, accessToken);
        if (!existing) return Response.json({ success: false, error: "Plan not found." }, { status: 404 });

        const doc = fromFirestoreDoc(existing);
        if (doc.status !== "PLANNED") {
          return Response.json({ success: false, error: `Plan in '${doc.status}' state cannot be edited.` }, { status: 409 });
        }

        const now = new Date().toISOString();
        const patchData = { updatedAt: now, updatedBy: user.sub };
        if (shift !== undefined) patchData.shift = String(shift);
        if (targetBottles !== undefined && Number(targetBottles) > 0) patchData.targetBottles = Number(targetBottles);
        if (assignedMachineId !== undefined) patchData.assignedMachineId = assignedMachineId ? String(assignedMachineId) : null;
        if (lineId !== undefined) patchData.lineId = lineId ? String(lineId) : null;
        if (notes !== undefined) patchData.notes = String(notes);

        await firestorePatch(env, docPath, objectToFirestoreFields(patchData), accessToken);

        return Response.json({ success: true, updated: true, planId: id });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Plan edit failed.") }, { status: authError ? 401 : 500 });
      }
    }

    if (request.method === "POST" && url.pathname === "/api/plans/status") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId, planId, status } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, ["owner", "admin", "manager", "operator", "production", "production_staff"], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        if (!planId || !status) return Response.json({ success: false, error: "planId and status are required." }, { status: 400 });

        const statusMap = {
          "START": "IN_PROGRESS",
          "COMPLETE": "COMPLETED",
          "CANCEL": "CANCELLED",
          "IN_PROGRESS": "IN_PROGRESS",
          "COMPLETED": "COMPLETED",
          "CANCELLED": "CANCELLED",
          "PLANNED": "PLANNED"
        };
        const nextStatus = statusMap[String(status).toUpperCase()];
        if (!nextStatus) return Response.json({ success: false, error: `Invalid status: ${status}` }, { status: 400 });

        const id = String(planId).replace(/[^a-zA-Z0-9_-]/g, "_");
        const docPath = `organisations/${orgId}/factories/${factoryId}/plans/${id}`;
        const accessToken = await getGoogleAccessToken(env);
        const existing = await firestoreGet(env, docPath, accessToken);
        if (!existing) return Response.json({ success: false, error: "Plan not found." }, { status: 404 });

        const doc = fromFirestoreDoc(existing);
        if (nextStatus === "CANCELLED" && doc.status !== "PLANNED") {
          return Response.json({ success: false, error: "Only PLANNED production plans can be cancelled." }, { status: 409 });
        }
        if (nextStatus === "IN_PROGRESS" && doc.status !== "PLANNED") {
          return Response.json({ success: false, error: "Only PLANNED production plans can be started." }, { status: 409 });
        }
        if (doc.status === "COMPLETED" || doc.status === "CANCELLED") {
          return Response.json({ success: false, error: `Plan is already in terminal state '${doc.status}'.` }, { status: 409 });
        }

        const now = new Date().toISOString();
        await firestorePatch(env, docPath, objectToFirestoreFields({
          status: nextStatus,
          updatedAt: now,
          updatedBy: user.sub
        }), accessToken);

        return Response.json({ success: true, planId: id, status: nextStatus });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Plan status update failed.") }, { status: authError ? 401 : 500 });
      }
    }

    if (request.method === "POST" && url.pathname === "/api/plans/list") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, [], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        const accessToken = await getGoogleAccessToken(env);
        const docs = await firestoreList(env, `organisations/${orgId}/factories/${factoryId}/plans`, accessToken);
        const plans = docs.map(fromFirestoreDoc);

        return Response.json({ success: true, plans });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Plans list failed.") }, { status: authError ? 401 : 500 });
      }
    }

    // ==========================================
    // PRODUCTION LINES MODULE
    // ==========================================
    if (request.method === "POST" && url.pathname === "/api/lines/upsert") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId, lineCode, name, description, targetCapacity, capacityUnit, status } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, ["owner", "admin", "manager"], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        if (!lineCode || !name) {
          return Response.json({ success: false, error: "lineCode and name are required." }, { status: 400 });
        }

        const id = String(lineCode).trim().replace(/[^a-zA-Z0-9_-]/g, "_");
        const docPath = `organisations/${orgId}/factories/${factoryId}/lines/${id}`;
        const accessToken = await getGoogleAccessToken(env);
        const existing = await firestoreGet(env, docPath, accessToken);
        const now = new Date().toISOString();
        const createdAt = existing?.fields?.createdAt?.timestampValue || now;

        await firestorePatch(env, docPath, objectToFirestoreFields({
          lineCode: id,
          name: String(name).trim(),
          description: description ? String(description).trim() : "",
          targetCapacity: Number(targetCapacity) || 0,
          capacityUnit: String(capacityUnit || "BOTTLES_PER_HOUR"),
          status: String(status || "ACTIVE").toUpperCase(),
          createdAt,
          updatedAt: now,
          updatedBy: user.sub
        }), accessToken);

        return Response.json({ success: true, lineCode: id });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Line upsert failed.") }, { status: authError ? 401 : 500 });
      }
    }

    if (request.method === "POST" && url.pathname === "/api/lines/delete") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId, lineCode } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, ["owner", "admin", "manager"], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        if (!lineCode) return Response.json({ success: false, error: "lineCode is required." }, { status: 400 });

        const id = String(lineCode).trim().replace(/[^a-zA-Z0-9_-]/g, "_");
        const accessToken = await getGoogleAccessToken(env);

        // Check if machines are running on this line
        const machines = await firestoreList(env, `organisations/${orgId}/factories/${factoryId}/machines`, accessToken);
        const runningOnLine = machines.map(fromFirestoreDoc).some(m => m.lineCode === id && m.status === "RUNNING");
        if (runningOnLine) {
          return Response.json({ success: false, error: "Stop all running machines on this line before deleting it." }, { status: 409 });
        }

        await firestoreDelete(env, `organisations/${orgId}/factories/${factoryId}/lines/${id}`, accessToken);

        return Response.json({ success: true, deleted: true, lineCode: id });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Line delete failed.") }, { status: authError ? 401 : 500 });
      }
    }

    if (request.method === "POST" && url.pathname === "/api/lines/list") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, [], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        const accessToken = await getGoogleAccessToken(env);
        const docs = await firestoreList(env, `organisations/${orgId}/factories/${factoryId}/lines`, accessToken);
        const lines = docs.map(fromFirestoreDoc);

        return Response.json({ success: true, lines });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Lines list failed.") }, { status: authError ? 401 : 500 });
      }
    }

    // ==========================================
    // MACHINES MODULE
    // ==========================================
    if (request.method === "POST" && url.pathname === "/api/machines/upsert") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId, machineCode, name, machineType, location, status, notes, lineCode } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, ["owner", "admin", "manager"], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        if (!machineCode || !name) {
          return Response.json({ success: false, error: "machineCode and name are required." }, { status: 400 });
        }

        const id = String(machineCode).trim().replace(/[^a-zA-Z0-9_-]/g, "_");
        const docPath = `organisations/${orgId}/factories/${factoryId}/machines/${id}`;
        const accessToken = await getGoogleAccessToken(env);
        const existing = await firestoreGet(env, docPath, accessToken);
        const now = new Date().toISOString();
        const createdAt = existing?.fields?.createdAt?.timestampValue || now;

        await firestorePatch(env, docPath, objectToFirestoreFields({
          machineCode: id,
          name: String(name).trim(),
          machineType: String(machineType || "BLOW_MOULDING"),
          location: location ? String(location).trim() : "",
          status: String(status || "STOPPED").toUpperCase(),
          notes: notes ? String(notes).trim() : "",
          lineCode: lineCode ? String(lineCode).trim() : "",
          createdAt,
          updatedAt: now,
          updatedBy: user.sub
        }), accessToken);

        return Response.json({ success: true, machineCode: id });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Machine upsert failed.") }, { status: authError ? 401 : 500 });
      }
    }

    if (request.method === "POST" && url.pathname === "/api/machines/delete") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId, machineCode } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, ["owner", "admin", "manager"], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        if (!machineCode) return Response.json({ success: false, error: "machineCode is required." }, { status: 400 });

        const id = String(machineCode).trim().replace(/[^a-zA-Z0-9_-]/g, "_");
        const docPath = `organisations/${orgId}/factories/${factoryId}/machines/${id}`;
        const accessToken = await getGoogleAccessToken(env);
        const existing = await firestoreGet(env, docPath, accessToken);
        if (!existing) return Response.json({ success: true, deleted: true });

        const doc = fromFirestoreDoc(existing);
        if (doc.status === "RUNNING") {
          return Response.json({ success: false, error: "Stop the machine before deleting it." }, { status: 409 });
        }

        // Check open machine events
        const events = await firestoreList(env, `organisations/${orgId}/factories/${factoryId}/machineEvents`, accessToken);
        const hasOpen = events.map(fromFirestoreDoc).some(e => e.machineCode === id && e.status === "OPEN");
        if (hasOpen) {
          return Response.json({ success: false, error: "Close all open machine events before deleting this machine." }, { status: 409 });
        }

        await firestoreDelete(env, docPath, accessToken);

        return Response.json({ success: true, deleted: true, machineCode: id });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Machine delete failed.") }, { status: authError ? 401 : 500 });
      }
    }

    if (request.method === "POST" && url.pathname === "/api/machines/assign-line") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId, machineCode, lineCode } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, ["owner", "admin", "manager"], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        if (!machineCode) return Response.json({ success: false, error: "machineCode is required." }, { status: 400 });

        const id = String(machineCode).trim().replace(/[^a-zA-Z0-9_-]/g, "_");
        const docPath = `organisations/${orgId}/factories/${factoryId}/machines/${id}`;
        const accessToken = await getGoogleAccessToken(env);
        const now = new Date().toISOString();

        await firestorePatch(env, docPath, objectToFirestoreFields({
          lineCode: lineCode ? String(lineCode).trim() : "",
          updatedAt: now,
          updatedBy: user.sub
        }), accessToken);

        return Response.json({ success: true, assigned: true, machineCode: id });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Machine line assignment failed.") }, { status: authError ? 401 : 500 });
      }
    }

    if (request.method === "POST" && url.pathname === "/api/machines/status") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId, machineCode, status } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, ["owner", "admin", "manager", "operator", "production", "production_staff"], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        if (!machineCode || !status) return Response.json({ success: false, error: "machineCode and status are required." }, { status: 400 });

        const nextStatus = String(status).trim().toUpperCase();
        const id = String(machineCode).trim().replace(/[^a-zA-Z0-9_-]/g, "_");
        const docPath = `organisations/${orgId}/factories/${factoryId}/machines/${id}`;
        const accessToken = await getGoogleAccessToken(env);
        const now = new Date().toISOString();

        await firestorePatch(env, docPath, objectToFirestoreFields({
          status: nextStatus,
          updatedAt: now,
          updatedBy: user.sub
        }), accessToken);

        return Response.json({ success: true, status: nextStatus, machineCode: id });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Machine status update failed.") }, { status: authError ? 401 : 500 });
      }
    }

    if (request.method === "POST" && url.pathname === "/api/machines/list") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, [], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        const accessToken = await getGoogleAccessToken(env);
        const docs = await firestoreList(env, `organisations/${orgId}/factories/${factoryId}/machines`, accessToken);
        const machines = docs.map(fromFirestoreDoc);

        return Response.json({ success: true, machines });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Machines list failed.") }, { status: authError ? 401 : 500 });
      }
    }

    // ==========================================
    // MACHINE EVENTS (DOWNTIME & MAINTENANCE)
    // ==========================================
    if (request.method === "POST" && url.pathname === "/api/machines/events/create") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId, eventId, machineCode, machineNameSnapshot, eventType, startedAt, reason, maintenanceType } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, ["owner", "admin", "manager", "operator", "production", "production_staff"], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        if (!machineCode || !eventType) {
          return Response.json({ success: false, error: "machineCode and eventType (DOWNTIME|MAINTENANCE) are required." }, { status: 400 });
        }

        const id = String(eventId || `me-${Date.now()}`).replace(/[^a-zA-Z0-9_-]/g, "_");
        const cleanMachine = String(machineCode).trim().replace(/[^a-zA-Z0-9_-]/g, "_");
        const docPath = `organisations/${orgId}/factories/${factoryId}/machineEvents/${id}`;
        const accessToken = await getGoogleAccessToken(env);
        const now = new Date().toISOString();

        await firestorePatch(env, docPath, objectToFirestoreFields({
          eventId: id,
          machineCode: cleanMachine,
          machineNameSnapshot: String(machineNameSnapshot || cleanMachine),
          eventType: String(eventType).toUpperCase(),
          startedAt: startedAt ? String(startedAt) : now,
          endedAt: null,
          reason: String(reason || ""),
          maintenanceType: String(maintenanceType || ""),
          status: "OPEN",
          performedBy: user.sub,
          createdAt: now,
          updatedAt: now
        }), accessToken);

        // Update machine status accordingly
        const machineStatus = String(eventType).toUpperCase() === "MAINTENANCE" ? "MAINTENANCE" : "STOPPED";
        await firestorePatch(env, `organisations/${orgId}/factories/${factoryId}/machines/${cleanMachine}`, objectToFirestoreFields({
          status: machineStatus,
          updatedAt: now,
          updatedBy: user.sub
        }), accessToken);

        return Response.json({ success: true, eventId: id, machineStatus });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Machine event create failed.") }, { status: authError ? 401 : 500 });
      }
    }

    if (request.method === "POST" && url.pathname === "/api/machines/events/close") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId, eventId, completionNotes, partsUsed, finalMachineStatus } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, ["owner", "admin", "manager", "operator", "production", "production_staff"], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        if (!eventId) return Response.json({ success: false, error: "eventId is required." }, { status: 400 });

        const id = String(eventId).trim().replace(/[^a-zA-Z0-9_-]/g, "_");
        const docPath = `organisations/${orgId}/factories/${factoryId}/machineEvents/${id}`;
        const accessToken = await getGoogleAccessToken(env);
        const existing = await firestoreGet(env, docPath, accessToken);
        if (!existing) return Response.json({ success: false, error: "Machine event not found." }, { status: 404 });

        const eventDoc = fromFirestoreDoc(existing);
        const now = new Date().toISOString();

        await firestorePatch(env, docPath, objectToFirestoreFields({
          status: "CLOSED",
          endedAt: now,
          completionNotes: String(completionNotes || ""),
          partsUsed: String(partsUsed || ""),
          completedBy: user.sub,
          updatedAt: now
        }), accessToken);

        // Update machine status
        if (eventDoc.machineCode) {
          const nextMachineStatus = String(finalMachineStatus || "STOPPED").toUpperCase();
          await firestorePatch(env, `organisations/${orgId}/factories/${factoryId}/machines/${eventDoc.machineCode}`, objectToFirestoreFields({
            status: nextMachineStatus,
            updatedAt: now,
            updatedBy: user.sub
          }), accessToken);
        }

        return Response.json({ success: true, closed: true, eventId: id });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Close machine event failed.") }, { status: authError ? 401 : 500 });
      }
    }

    if (request.method === "POST" && url.pathname === "/api/machines/events/list") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, [], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        const accessToken = await getGoogleAccessToken(env);
        const docs = await firestoreList(env, `organisations/${orgId}/factories/${factoryId}/machineEvents`, accessToken);
        const events = docs.map(fromFirestoreDoc);

        return Response.json({ success: true, events });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Machine events list failed.") }, { status: authError ? 401 : 500 });
      }
    }

    // ==========================================
    // QUALITY INSPECTIONS MODULE
    // ==========================================
    if (request.method === "POST" && url.pathname === "/api/quality/post") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId, inspectionId, productionEntryId, productId, productNameSnapshot, inspectionDate, inspectedBottles, passedBottles, failedBottles, defectCategory, severity, notes } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, ["owner", "admin", "manager", "operator", "production", "production_staff"], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        const inspected = Number(inspectedBottles);
        const passed = Number(passedBottles);
        const failed = Number(failedBottles);

        if (!productId || !inspected || inspected <= 0 || inspected !== (passed + failed)) {
          return Response.json({ success: false, error: "Invalid inspection quantities: inspected must equal passed + failed." }, { status: 400 });
        }

        const now = new Date().toISOString();
        const id = String(inspectionId || `qi-${Date.now()}`).replace(/[^a-zA-Z0-9_-]/g, "_");
        const docPath = `organisations/${orgId}/factories/${factoryId}/quality/${id}`;
        const accessToken = await getGoogleAccessToken(env);

        await firestorePatch(env, docPath, objectToFirestoreFields({
          inspectionId: id,
          productionEntryId: productionEntryId ? String(productionEntryId) : null,
          productId: String(productId),
          productNameSnapshot: String(productNameSnapshot || productId),
          inspectionDate: String(inspectionDate || now.slice(0, 10)),
          inspectedBottles: inspected,
          passedBottles: passed,
          failedBottles: failed,
          defectCategory: String(defectCategory || "NONE"),
          severity: String(severity || "MINOR").toUpperCase(),
          notes: String(notes || ""),
          inspectorUid: user.sub,
          createdAt: now
        }), accessToken);

        return Response.json({ success: true, inspectionId: id });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Quality inspection post failed.") }, { status: authError ? 401 : 500 });
      }
    }

    if (request.method === "POST" && url.pathname === "/api/quality/list") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, [], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        const accessToken = await getGoogleAccessToken(env);
        const docs = await firestoreList(env, `organisations/${orgId}/factories/${factoryId}/quality`, accessToken);
        const inspections = docs.map(fromFirestoreDoc);

        return Response.json({ success: true, inspections });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Quality list failed.") }, { status: authError ? 401 : 500 });
      }
    }

    // ==========================================
    // ELECTRICITY METER READINGS MODULE
    // ==========================================
    if (request.method === "POST" && url.pathname === "/api/electricity/post") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId, readingId, meterId, readingDate, startReading, endReading, remarks } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, ["owner", "admin", "manager", "operator", "production", "production_staff"], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        const start = Number(startReading);
        const end = Number(endReading);

        if (isNaN(start) || start < 0 || isNaN(end) || end < start) {
          return Response.json({ success: false, error: "Invalid reading: end reading must be greater than or equal to start reading." }, { status: 400 });
        }

        const consumption = Math.round((end - start) * 100) / 100;
        const now = new Date().toISOString();
        const dateStr = String(readingDate || now.slice(0, 10));
        const meter = String(meterId || "MAIN");
        const id = String(readingId || `${meter}_${dateStr}`).replace(/[^a-zA-Z0-9_-]/g, "_");
        const docPath = `organisations/${orgId}/factories/${factoryId}/electricity/${id}`;
        const accessToken = await getGoogleAccessToken(env);

        await firestorePatch(env, docPath, objectToFirestoreFields({
          readingId: id,
          meterId: meter,
          readingDate: dateStr,
          startReading: start,
          endReading: end,
          consumption,
          remarks: String(remarks || ""),
          status: "COMPLETED",
          recordedBy: user.sub,
          createdAt: now
        }), accessToken);

        return Response.json({ success: true, readingId: id, consumption });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Electricity post failed.") }, { status: authError ? 401 : 500 });
      }
    }

    if (request.method === "POST" && url.pathname === "/api/electricity/list") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, [], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        const accessToken = await getGoogleAccessToken(env);
        const docs = await firestoreList(env, `organisations/${orgId}/factories/${factoryId}/electricity`, accessToken);
        const readings = docs.map(fromFirestoreDoc);

        return Response.json({ success: true, readings });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Electricity list failed.") }, { status: authError ? 401 : 500 });
      }
    }

    // ==========================================
    // AUTHORITATIVE REPORTS QUERY MODULE
    // ==========================================
    if (request.method === "POST" && url.pathname === "/api/reports/query") {
      try {
        const user = await verifyFirebaseToken(request);
        const body = await request.json();
        const { organisationId: orgId, factoryId, fromDate, toDate } = body;

        const auth = await authorizeMember(env, user, orgId, factoryId, [], await getGoogleAccessToken(env));
        if (!auth.ok) return Response.json({ success: false, error: auth.error }, { status: auth.status });

        const accessToken = await getGoogleAccessToken(env);
        const root = `organisations/${orgId}/factories/${factoryId}`;

        const [productsDocs, stocksDocs, electricityDocs, qualityDocs] = await Promise.all([
          firestoreList(env, `${root}/products`, accessToken),
          firestoreList(env, `${root}/stock`, accessToken),
          firestoreList(env, `${root}/electricity`, accessToken),
          firestoreList(env, `${root}/quality`, accessToken)
        ]);

        const products = productsDocs.map(fromFirestoreDoc);
        const stocks = stocksDocs.map(fromFirestoreDoc);
        const electricity = electricityDocs.map(fromFirestoreDoc);
        const quality = qualityDocs.map(fromFirestoreDoc);

        // Filter date ranges if specified
        const inDateRange = (dateStr) => {
          if (!dateStr) return true;
          if (fromDate && dateStr < fromDate) return false;
          if (toDate && dateStr > toDate) return false;
          return true;
        };

        const filteredElectricity = electricity.filter(e => inDateRange(e.readingDate));
        const filteredQuality = quality.filter(q => inDateRange(q.inspectionDate));

        const totalElectricityConsumption = filteredElectricity.reduce((sum, e) => sum + (Number(e.consumption) || 0), 0);
        const totalInspected = filteredQuality.reduce((sum, q) => sum + (Number(q.inspectedBottles) || 0), 0);
        const totalPassed = filteredQuality.reduce((sum, q) => sum + (Number(q.passedBottles) || 0), 0);
        const totalFailed = filteredQuality.reduce((sum, q) => sum + (Number(q.failedBottles) || 0), 0);

        const stockByProduct = new Map(stocks.map(s => [s.id, s]));
        let totalCompletePacks = 0;
        let totalLooseBottles = 0;
        let totalUnpackedBottles = 0;

        const stockBreakdown = products.map(p => {
          const s = stockByProduct.get(p.id) || {};
          const completePacks = Number(s.completePacks) || 0;
          const looseBottles = Number(s.looseBottles) || 0;
          const unpackedGoodBottles = Number(s.unpackedGoodBottles) || 0;

          totalCompletePacks += completePacks;
          totalLooseBottles += looseBottles;
          totalUnpackedBottles += unpackedGoodBottles;

          return {
            productId: p.id,
            name: p.name || p.id,
            sizeLabel: p.sizeLabel || "",
            colour: p.colour || "",
            bottlesPerPack: Number(p.bottlesPerPack) || 0,
            completePacks,
            looseBottles,
            unpackedGoodBottles
          };
        });

        return Response.json({
          success: true,
          report: {
            fromDate: fromDate || null,
            toDate: toDate || null,
            totals: {
              totalElectricityConsumption: Math.round(totalElectricityConsumption * 100) / 100,
              totalInspected,
              totalPassed,
              totalFailed,
              totalCompletePacks,
              totalLooseBottles,
              totalUnpackedBottles
            },
            stock: stockBreakdown
          }
        });
      } catch (error) {
        const authError = ["AUTH_MISSING", "AUTH_INVALID"].includes(error?.message);
        return Response.json({ success: false, error: authError ? "Authentication required." : (error?.message || "Report query failed.") }, { status: authError ? 401 : 500 });
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
