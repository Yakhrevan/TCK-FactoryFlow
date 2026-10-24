import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";
import {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc } from "firebase/firestore";
import test from "node:test";

const here = dirname(fileURLToPath(import.meta.url));
const rules = readFileSync(resolve(here, "../../firestore.rules"), "utf8");
let env;

test("Firestore rules enforce organisation membership and server-owned stock", async (t) => {
  env = await initializeTestEnvironment({
    projectId: "factoryflow-rules-test",
    firestore: { rules },
  });
  await env.withSecurityRulesDisabled(async (context) => {
    const adminDb = context.firestore();
    await setDoc(doc(adminDb, "organisations/org-a/members/alice"), {
      uid: "alice", organisationId: "org-a", role: "owner", active: true,
    });
    await setDoc(doc(adminDb, "organisations/org-b/members/bob"), {
      uid: "bob", organisationId: "org-b", role: "owner", active: true,
    });
    await setDoc(doc(adminDb, "organisations/org-a/factories/factory-1/stock/product-1"), {
      completePacks: 10,
    });
  });

  await t.test("active member can read records in their own organisation", async () => {
    const db = env.authenticatedContext("alice", { organisationId: "org-a" }).firestore();
    await assertSucceeds(getDoc(doc(db, "organisations/org-a/factories/factory-1/stock/product-1")));
  });

  await t.test("cross-organisation reads are denied", async () => {
    const db = env.authenticatedContext("alice", { organisationId: "org-a" }).firestore();
    await assertFails(getDoc(doc(db, "organisations/org-b/factories/factory-1/stock/product-1")));
  });

  await t.test("clients cannot directly modify authoritative stock", async () => {
    const db = env.authenticatedContext("alice", { organisationId: "org-a" }).firestore();
    await assertFails(setDoc(doc(db, "organisations/org-a/factories/factory-1/stock/product-1"), {
      completePacks: 9999,
    }));
  });

  await t.test("inactive membership denies reads even when token claim remains", async () => {
    await env.withSecurityRulesDisabled(async (context) => {
      await setDoc(doc(context.firestore(), "organisations/org-a/members/alice"), {
        uid: "alice", organisationId: "org-a", role: "owner", active: false,
      });
    });
    const db = env.authenticatedContext("alice", { organisationId: "org-a" }).firestore();
    await assertFails(getDoc(doc(db, "organisations/org-a/factories/factory-1/stock/product-1")));
  });

  await t.test("unauthenticated reads are denied", async () => {
    const db = env.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(db, "organisations/org-a/factories/factory-1/stock/product-1")));
  });

  await env.cleanup();
});
