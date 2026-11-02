export async function firestoreGet(env, documentPath, accessToken, transaction = null) {
  const url =
    `${FIRESTORE_BASE}/${documentPath}` +
    (transaction ? `?transaction=${encodeURIComponent(transaction)}` : "");

  const response = await fetch(url, {
    headers: {
      Authorization: `Bearer ${accessToken}`,
    },
  });

  if (response.status === 404) return null;

  const result = await parseFirestoreResponse(response, "FIRESTORE_GET_FAILED");

  return result;
}

export async function firestoreList(env, collectionPath, accessToken) {
  const documents = [];
  let nextPageToken = null;

  do {
    const params = new URLSearchParams({ pageSize: "100" });
    if (nextPageToken) params.set("pageToken", nextPageToken);

    const url =
      `${FIRESTORE_BASE}/${collectionPath}?${params}`;

    const response = await fetch(url, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
      },
    });

    const result = await parseFirestoreResponse(response, "FIRESTORE_LIST_FAILED");

    documents.push(...(result.documents || []));
    nextPageToken = result.nextPageToken || null;
  } while (nextPageToken);

  return documents;
}

export async function firestorePatch(env, documentPath, fields, accessToken) {
  const url =
    `${FIRESTORE_BASE}/${documentPath}`;

  const response = await fetch(url, {
    method: "PATCH",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ fields }),
  });

  const result = await parseFirestoreResponse(response, "FIRESTORE_PATCH_FAILED");

  return result;
}

const PROJECT_ID = "tck-admin-ac864";
const FIRESTORE_BASE =
  `https://firestore.googleapis.com/v1/projects/${PROJECT_ID}/databases/(default)/documents`;

async function parseFirestoreResponse(response, errorCode) {
  const body = await response.text();

  if (!response.ok) {
    console.error(`Firestore ${errorCode} HTTP error:`, response.status, body.slice(0, 500));
    throw new Error(errorCode);
  }

  try {
    return body ? JSON.parse(body) : {};
  } catch {
    throw new Error(`${errorCode}_INVALID_RESPONSE`);
  }
}

export async function firestoreBeginTransaction(accessToken) {
  const response = await fetch(
    `${FIRESTORE_BASE}:beginTransaction`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({}),
    }
  );

  const result = await parseFirestoreResponse(response, "FIRESTORE_BEGIN_TRANSACTION_FAILED");
  return result.transaction;
}

export async function firestoreCommit(accessToken, transaction, writes) {
  const response = await fetch(
    `${FIRESTORE_BASE}:commit`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ transaction, writes }),
    }
  );

  const result = await parseFirestoreResponse(
    response,
    "FIRESTORE_COMMIT_FAILED"
  );

  return result;
}

export async function firestoreRollback(accessToken, transaction) {
  const response = await fetch(
    `${FIRESTORE_BASE}:rollback`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ transaction }),
    }
  );

  if (!response.ok) {
    const body = await response.text();
    console.error("Firestore rollback failed:", response.status, body.slice(0, 500));
    throw new Error("FIRESTORE_ROLLBACK_FAILED");
  }
}


export async function firestoreBatchGet(accessToken, transaction, documentPaths) {
  const baseUrl =
    `${FIRESTORE_BASE}:batchGet`;

  const documents = documentPaths.map(
    path => `projects/${PROJECT_ID}/databases/(default)/documents/${path}`
  );

  const response = await fetch(baseUrl, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ documents, transaction }),
  });

  const body = await response.text();

  if (!response.ok) {
    console.error("Firestore batchGet HTTP error:", response.status, body.slice(0, 1000));
    throw new Error(`FIRESTORE_BATCH_GET_FAILED: HTTP ${response.status}: ${body.slice(0, 500)}`);
  }

  const parsed = [];
  let depth = 0;
  let start = -1;
  let inString = false;
  let escaped = false;

  for (let i = 0; i < body.length; i++) {
    const ch = body[i];

    if (inString) {
      if (escaped) escaped = false;
      else if (ch === "\\") escaped = true;
      else if (ch === '"') inString = false;
      continue;
    }

    if (ch === '"') {
      inString = true;
    } else if (ch === "{") {
      if (depth === 0) start = i;
      depth++;
    } else if (ch === "}") {
      depth--;
      if (depth === 0 && start !== -1) {
        parsed.push(JSON.parse(body.slice(start, i + 1)));
        start = -1;
      }
    }
  }

  if (depth !== 0 || inString || start !== -1) {
    throw new Error("FIRESTORE_BATCH_GET_INVALID_RESPONSE");
  }

  if (body.trim() && parsed.length === 0) {
    throw new Error("FIRESTORE_BATCH_GET_EMPTY_RESPONSE");
  }

  return parsed;
}
