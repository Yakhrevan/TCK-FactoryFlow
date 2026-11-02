import test from "node:test";
import assert from "node:assert/strict";
import { toFirestoreValue, fromFirestoreValue, fromFirestoreDoc } from "../src/firestore.js";

test("toFirestoreValue handles primitive types correctly", () => {
  assert.deepEqual(toFirestoreValue("test"), { stringValue: "test" });
  assert.deepEqual(toFirestoreValue(42), { integerValue: "42" });
  assert.deepEqual(toFirestoreValue(3.14), { doubleValue: 3.14 });
  assert.deepEqual(toFirestoreValue(true), { booleanValue: true });
  assert.deepEqual(toFirestoreValue(false), { booleanValue: false });
  assert.deepEqual(toFirestoreValue(null), { nullValue: null });
  assert.deepEqual(toFirestoreValue([1, "a"]), {
    arrayValue: {
      values: [{ integerValue: "1" }, { stringValue: "a" }]
    }
  });
  assert.deepEqual(toFirestoreValue({ name: "TCK", count: 10 }), {
    mapValue: {
      fields: {
        name: { stringValue: "TCK" },
        count: { integerValue: "10" }
      }
    }
  });
});

test("fromFirestoreValue reconstructs values accurately", () => {
  assert.equal(fromFirestoreValue({ stringValue: "hello" }), "hello");
  assert.equal(fromFirestoreValue({ integerValue: "100" }), 100);
  assert.equal(fromFirestoreValue({ doubleValue: 9.99 }), 9.99);
  assert.equal(fromFirestoreValue({ booleanValue: true }), true);
  assert.equal(fromFirestoreValue({ nullValue: null }), null);
  assert.deepEqual(fromFirestoreValue({
    arrayValue: { values: [{ stringValue: "x" }, { stringValue: "y" }] }
  }), ["x", "y"]);
  assert.deepEqual(fromFirestoreValue({
    mapValue: { fields: { key: { stringValue: "val" } } }
  }), { key: "val" });
});

test("fromFirestoreDoc converts document with ID correctly", () => {
  const doc = {
    name: "projects/tck-admin-ac864/databases/(default)/documents/organisations/tck-factory-01/factories/factory-01/products/prod-01",
    fields: {
      name: { stringValue: "500ml Bottle" },
      bottlesPerPack: { integerValue: "24" },
      active: { booleanValue: true }
    }
  };
  const parsed = fromFirestoreDoc(doc);
  assert.equal(parsed.id, "prod-01");
  assert.equal(parsed.name, "500ml Bottle");
  assert.equal(parsed.bottlesPerPack, 24);
  assert.equal(parsed.active, true);
});

test("custom claims payload serializes correctly for Firebase Identity Toolkit", () => {
  const claims = {
    organisationId: "tck-factory-01",
    role: "admin",
    factoryIds: ["factory-01"]
  };
  const body = {
    localId: "uid-123",
    customAttributes: JSON.stringify(claims)
  };
  assert.equal(body.localId, "uid-123");
  const parsed = JSON.parse(body.customAttributes);
  assert.equal(parsed.organisationId, "tck-factory-01");
  assert.equal(parsed.role, "admin");
  assert.deepEqual(parsed.factoryIds, ["factory-01"]);
});

test("organisation resolution prioritizes explicit input or falls back to known organisation", () => {
  function resolveOrg(bodyOrg, tokenOrg, defaultOrg = "tck-factory-01") {
    const org = String(bodyOrg || tokenOrg || "").trim();
    return org || defaultOrg;
  }

  assert.equal(resolveOrg("custom-org", "token-org"), "custom-org");
  assert.equal(resolveOrg("", "token-org"), "token-org");
  assert.equal(resolveOrg("", ""), "tck-factory-01");
});

