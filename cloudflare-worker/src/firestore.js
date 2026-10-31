export async function firestoreGet(env, documentPath, accessToken) {
  const url =
    `https://firestore.googleapis.com/v1/projects/${"tck-admin-ac864"}/databases/(default)/documents/${documentPath}`;

  const response = await fetch(url, {
    headers: {
      Authorization: `Bearer ${accessToken}`,
    },
  });

  if (response.status === 404) return null;

  const result = await response.json();

  if (!response.ok) {
    throw new Error("FIRESTORE_GET_FAILED");
  }

  return result;
}

export async function firestoreList(env, collectionPath, accessToken) {
  const url =
    `https://firestore.googleapis.com/v1/projects/tck-admin-ac864/databases/(default)/documents/${collectionPath}?pageSize=100`;

  const response = await fetch(url, {
    headers: {
      Authorization: `Bearer ${accessToken}`,
    },
  });

  const result = await response.json();

  if (!response.ok) {
    throw new Error("FIRESTORE_LIST_FAILED");
  }

  return result.documents || [];
}

export async function firestorePatch(env, documentPath, fields, accessToken) {
  const url =
    `https://firestore.googleapis.com/v1/projects/tck-admin-ac864/databases/(default)/documents/${documentPath}`;

  const response = await fetch(url, {
    method: "PATCH",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ fields }),
  });

  const result = await response.json();

  if (!response.ok) {
    throw new Error("FIRESTORE_PATCH_FAILED");
  }

  return result;
}

const FIRESTORE_BASE =
  "https://firestore.googleapis.com/v1/projects/tck-admin-ac864/databases/(default)/documents";

export async function firestoreBeginTransaction(accessToken) {
  const response = await fetch(
    "https://firestore.googleapis.com/v1/projects/tck-admin-ac864/databases/(default)/documents:beginTransaction",
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({}),
    }
  );

  const result = await response.json();
  if (!response.ok) throw new Error("FIRESTORE_BEGIN_TRANSACTION_FAILED");
  return result.transaction;
}

export async function firestoreCommit(accessToken, transaction, writes) {
  const response = await fetch(
    "https://firestore.googleapis.com/v1/projects/tck-admin-ac864/databases/(default)/documents:commit",
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ transaction, writes }),
    }
  );

  const result = await response.json();

  if (!response.ok) {
    const code = result?.error?.status || "FIRESTORE_COMMIT_FAILED";
    throw new Error(code);
  }

  return result;
}

export async function firestoreRollback(accessToken, transaction) {
  await fetch(
    "https://firestore.googleapis.com/v1/projects/tck-admin-ac864/databases/(default)/documents:rollback",
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ transaction }),
    }
  );
}


export async function firestoreBatchGet(accessToken, transaction, documentPaths) {
  const baseUrl =
    "https://firestore.googleapis.com/v1/projects/tck-admin-ac864/databases/(default)/documents:batchGet";

  const documents = documentPaths.map(
    path => `projects/tck-admin-ac864/databases/(default)/documents/${path}`
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
      else if (ch === "\\\\") escaped = true;
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

  return parsed;
}
